package com.formationtool.app.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.media.*
import android.net.Uri
import android.opengl.*
import android.os.Build
import android.util.Log
import android.view.Surface
import com.formationtool.app.model.*
import com.formationtool.app.renderer.FormationRenderer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface
import kotlin.math.roundToInt

/**
 * 视频导出器 - 支持多种布局模式和视角缩放
 */
class VideoExporter(
    internal val context: Context,
    internal val outputFile: File,
    internal val width: Int = 1280,
    internal val height: Int = 720,
    internal val fps: Int = 30,
    internal val bitRate: Int = 8_000_000
) {
    companion object {
        private const val TAG = "VideoExporter"
        private const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_AVC
        private const val TIMEOUT_USEC = 10000L
    }

    // 布局模式
    enum class LayoutMode {
        SINGLE,            // 单屏
        SPLIT_HORIZONTAL,  // 左右分屏
        SPLIT_VERTICAL     // 上下分屏
    }

    // 导出视角类型
    enum class ViewType {
        REFERENCE_VIDEO,  // 参考视频
        VIEW_45_DEGREE,   // 45度角视图 (3D)
        VIEW_2D           // 2D俯视图
    }

    // 导出配置
    data class ExportConfig(
        val layoutMode: LayoutMode = LayoutMode.SPLIT_HORIZONTAL,
        val primaryView: ViewType = ViewType.VIEW_2D,
        val secondaryView: ViewType = ViewType.VIEW_45_DEGREE,
        val viewScale: Float = 1.0f,  // 视角缩放 (0.5 ~ 2.0)
        val flipped: Boolean = false,  // 是否将舞台整体旋转180°导出
        val showDancerNames: Boolean = true  // true=显示名字, false=显示编号
    )

    // 进度回调
    interface ProgressCallback {
        fun onProgress(progress: Float, message: String)
        fun onComplete(success: Boolean, outputFile: File?)
        fun onError(error: String)
    }

    internal var callback: ProgressCallback? = null
    internal var isCancelled = false

    // EGL 相关
    internal var egl: EGL10? = null
    internal var eglDisplay: EGLDisplay? = null
    internal var eglContext: EGLContext? = null
    internal var eglSurface: EGLSurface? = null
    internal var eglConfig: EGLConfig? = null

    // MediaCodec 相关
    internal var encoder: MediaCodec? = null
    internal var muxer: MediaMuxer? = null
    internal var videoTrackIndex = -1
    internal var audioTrackIndex = -1
    internal var muxerStarted = false
    internal var encodedSampleCount = 0
    internal var inputSurface: Surface? = null

    // 渲染器
    internal var leftRenderer: OffscreenRenderer? = null
    internal var rightRenderer: OffscreenRenderer? = null

    fun setCallback(callback: ProgressCallback) {
        this.callback = callback
    }

    fun cancel() {
        isCancelled = true
    }

    /**
     * 导出视频
     * @param leftView 左半屏视角
     * @param rightView 右半屏视角
     * @param formations 所有关键帧
     * @param durationMs 总时长（毫秒）
     * @param stageWidth 舞台宽度
     * @param stageDepth 舞台深度
     * @param referenceVideoUri 参考视频URI（可选）
     * @param audioUri 音频URI（可选）
     * @param inPointMs 入点时间（毫秒），默认0
     * @param outPointMs 出点时间（毫秒），默认为总时长
     */
    fun export(
        leftView: ViewType,
        rightView: ViewType,
        formations: List<Formation>,
        durationMs: Long,
        stageWidth: Float,
        stageDepth: Float,
        referenceVideoUri: Uri? = null,
        audioUri: Uri? = null,
        inPointMs: Long = 0L,
        outPointMs: Long = durationMs
    ): Boolean {
        if (formations.isEmpty()) {
            callback?.onError("没有关键帧数据")
            return false
        }

        try {
            callback?.onProgress(0f, "初始化编码器...")

            // 初始化编码器
            if (!initEncoder()) {
                callback?.onError("初始化编码器失败")
                return false
            }

            // 初始化 EGL
            callback?.onProgress(0.05f, "初始化渲染环境...")
            if (!initEGL()) {
                callback?.onError("初始化 EGL 失败")
                release()
                return false
            }

            // 初始化离屏渲染器（不创建FBO，直接渲染到主surface的视口）
            val halfWidth = width / 2
            leftRenderer = OffscreenRenderer(halfWidth, height, stageWidth, stageDepth, false) // 不创建FBO
            rightRenderer = OffscreenRenderer(halfWidth, height, stageWidth, stageDepth, false)

            leftRenderer?.init()
            rightRenderer?.init()

            // 配置视角
            configureViewType(leftRenderer, leftView)
            configureViewType(rightRenderer, rightView)

            // 计算导出时间范围（入点和出点）- 提前计算，供后续使用
            val exportStartMs = inPointMs.coerceIn(0L, durationMs)
            val exportEndMs = outPointMs.coerceIn(exportStartMs, durationMs)
            val exportDurationMs = exportEndMs - exportStartMs

            // 初始化参考视频提取器和解码器
            var videoExtractor: MediaExtractor? = null
            var videoDecoder: MediaCodec? = null
            var videoDecoderOutputSurface: Surface? = null
            var videoSurfaceTexture: SurfaceTexture? = null
            var videoTextureId = IntArray(1)
            var videoFormat: MediaFormat? = null
            var referenceVideoWidth = 0
            var referenceVideoHeight = 0
            var referenceVideoFps = fps  // 默认使用导出帧率
            var lastVideoTimeUs = -1L  // 上一次解码的时间戳

            if ((leftView == ViewType.REFERENCE_VIDEO || rightView == ViewType.REFERENCE_VIDEO) && referenceVideoUri != null) {
                callback?.onProgress(0.1f, "加载参考视频...")
                try {
                    videoExtractor = MediaExtractor()
                    videoExtractor.setDataSource(context, referenceVideoUri, null)

                    // 找到视频轨道
                    for (i in 0 until videoExtractor.trackCount) {
                        val format = videoExtractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME)
                        if (mime?.startsWith("video/") == true) {
                            videoExtractor.selectTrack(i)
                            videoFormat = format
                            referenceVideoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                            referenceVideoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)

                            // 获取帧率
                            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                                referenceVideoFps = format.getInteger(MediaFormat.KEY_FRAME_RATE)
                                Log.d(TAG, "参考视频帧率: ${referenceVideoFps}fps")
                            }

                            // 创建解码器
                            val codecName = MediaCodec.createDecoderByType(mime!!)
                            videoDecoder = codecName

                            // 创建纹理用于接收解码后的视频帧
                            GLES20.glGenTextures(1, videoTextureId, 0)
                            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId[0])
                            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

                            // 创建SurfaceTexture
                            videoSurfaceTexture = android.graphics.SurfaceTexture(videoTextureId[0])
                            videoDecoderOutputSurface = Surface(videoSurfaceTexture)

                            // 配置解码器
                            videoDecoder.configure(format, videoDecoderOutputSurface, null, 0)
                            videoDecoder.start()

                            // 定位到入点位置
                            videoExtractor.seekTo(exportStartMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

                            Log.d(TAG, "视频解码器初始化成功: ${referenceVideoWidth}x${referenceVideoHeight}, 入点: ${exportStartMs}ms")
                            break
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "加载参考视频失败", e)
                    videoExtractor?.release()
                    videoExtractor = null
                    videoDecoder?.release()
                    videoDecoder = null
                }
            }

            if (exportDurationMs <= 0L) {
                callback?.onError("导出时长必须大于 0")
                return false
            }
            val outputFps = fps.coerceAtLeast(1)
            val totalFrames = kotlin.math.ceil(exportDurationMs * outputFps / 1000.0).toInt()

            callback?.onProgress(0.15f, "开始渲染帧...")

            // 逐帧渲染
            for (frameIndex in 0 until totalFrames) {
                if (isCancelled) {
                    callback?.onError("导出已取消")
                    release()
                    return false
                }

                // 计算当前时间（加上入点偏移）
                val presentationTimeUs = frameIndex * 1_000_000L / outputFps
                val currentTimeMs = exportStartMs + presentationTimeUs / 1000L

                // 计算当前阵型和插值
                val (currentFormation, nextFormation, progress) = getFormationAtTime(formations, currentTimeMs)

                // 更新渲染器阵型数据
                leftRenderer?.setFormation(currentFormation, nextFormation, progress)
                rightRenderer?.setFormation(currentFormation, nextFormation, progress)

                // 清空整个画布
                GLES20.glViewport(0, 0, width, height)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

                // 渲染左半屏（有视频时是原视频，无视频时是3D视角）
                GLES20.glViewport(0, 0, halfWidth, height)
                GLES20.glScissor(0, 0, halfWidth, height)
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)

                if (leftView == ViewType.REFERENCE_VIDEO && videoDecoder != null && videoExtractor != null && videoSurfaceTexture != null) {
                    // 解码并渲染参考视频帧
                    lastVideoTimeUs = decodeAndRenderVideoFrame(
                        videoExtractor,
                        videoDecoder,
                        videoSurfaceTexture,
                        videoTextureId[0],
                        currentTimeMs,
                        lastVideoTimeUs,
                        referenceVideoFps,
                        referenceVideoWidth,
                        referenceVideoHeight,
                        halfWidth,
                        height
                    )
                } else {
                    leftRenderer?.renderDirect()
                }

                // 渲染右半屏（有视频时是用户选择的2D/3D，无视频时是2D视角）
                GLES20.glViewport(halfWidth, 0, halfWidth, height)
                GLES20.glScissor(halfWidth, 0, halfWidth, height)

                if (rightView == ViewType.REFERENCE_VIDEO && videoDecoder != null && videoExtractor != null && videoSurfaceTexture != null) {
                    // 解码并渲染参考视频帧
                    lastVideoTimeUs = decodeAndRenderVideoFrame(
                        videoExtractor,
                        videoDecoder,
                        videoSurfaceTexture,
                        videoTextureId[0],
                        currentTimeMs,
                        lastVideoTimeUs,
                        referenceVideoFps,
                        referenceVideoWidth,
                        referenceVideoHeight,
                        halfWidth,
                        height
                    )
                } else {
                    rightRenderer?.renderDirect()
                }

                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)

                // 设置时间戳
                android.opengl.EGLExt.eglPresentationTimeANDROID(
                    android.opengl.EGL14.eglGetCurrentDisplay(),
                    android.opengl.EGL14.eglGetCurrentSurface(android.opengl.EGL14.EGL_DRAW),
                    presentationTimeUs * 1000
                )

                // 交换缓冲，提交到编码器
                egl?.eglSwapBuffers(eglDisplay, eglSurface)

                // 处理编码器输出
                drainEncoder(false)

                // 更新进度
                val exportProgress = 0.15f + 0.8f * (frameIndex.toFloat() / totalFrames)
                if (frameIndex % 10 == 0) {
                    callback?.onProgress(exportProgress, "渲染帧 $frameIndex / $totalFrames")
                }
            }

            // 发送结束信号
            callback?.onProgress(0.95f, "完成编码...")
            drainEncoder(true)
            if (!muxerStarted || encodedSampleCount == 0) {
                throw IllegalStateException("编码器未生成有效视频帧")
            }

            // 停止并释放编码器和muxer（但保留其他资源）
            encoder?.stop()
            encoder?.release()
            encoder = null

            if (muxerStarted) {
                muxer?.stop()
            }
            muxer?.release()
            muxer = null
            muxerStarted = false

            // 释放参考视频资源
            videoDecoder?.stop()
            videoDecoder?.release()
            videoExtractor?.release()

            // 混合音频（如果有）
            val audioSource = audioUri ?: referenceVideoUri
            if (audioSource != null) {
                try {
                    callback?.onProgress(0.98f, "混合音频...")
                    mixAudio(audioSource, exportDurationMs, exportStartMs, exportEndMs)
                } catch (e: Exception) {
                    Log.e(TAG, "混合音频失败，但视频已导出", e)
                    // 音频混合失败不影响视频输出
                }
            } else {
                Log.d(TAG, "无音频源，跳过音频混合")
            }

            callback?.onProgress(1f, "导出完成")
            callback?.onComplete(true, outputFile)

            return true
        } catch (e: Exception) {
            Log.e(TAG, "导出失败", e)
            callback?.onError("导出失败: ${e.message}")
            return false
        } finally {
            release()
        }
    }

    /**
     * 使用配置导出视频
     * 支持单屏、左右分屏、上下分屏等布局模式
     */
    fun exportWithConfig(
        config: ExportConfig,
        formations: List<Formation>,
        durationMs: Long,
        stageWidth: Float,
        stageDepth: Float,
        referenceVideoUri: Uri? = null,
        audioUri: Uri? = null,
        inPointMs: Long = 0L,
        outPointMs: Long = durationMs
    ): Boolean {
        return exportWithConfigImpl(
            config = config,
            formations = formations,
            durationMs = durationMs,
            stageWidth = stageWidth,
            stageDepth = stageDepth,
            referenceVideoUri = referenceVideoUri,
            audioUri = audioUri,
            inPointMs = inPointMs,
            outPointMs = outPointMs
        )
    }

    /**
     * 初始化视频编码器
     */
    internal fun initEncoder(): Boolean {
        try {
            outputFile.parentFile?.mkdirs()
            encodedSampleCount = 0
            muxerStarted = false
            videoTrackIndex = -1
            val format = MediaFormat.createVideoFormat(MIME_TYPE, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }

            encoder = MediaCodec.createEncoderByType(MIME_TYPE)
            encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = encoder?.createInputSurface()
            encoder?.start()

            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            runCatching {
                muxer?.setOrientationHint(0)
            }.onFailure {
                Log.w(TAG, "设置导出方向失败，继续使用默认方向", it)
            }

            Log.d(TAG, "编码器初始化成功: ${width}x${height} @ ${fps}fps")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "初始化编码器失败", e)
            return false
        }
    }

    /**
     * 初始化 EGL 离屏渲染
     */
    internal fun initEGL(): Boolean {
        try {
            egl = EGLContext.getEGL() as EGL10
            eglDisplay = egl?.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)

            val version = IntArray(2)
            egl?.eglInitialize(eglDisplay, version)

            val configAttribs = intArrayOf(
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 16,
                EGL10.EGL_RENDERABLE_TYPE, 4, // EGL_OPENGL_ES2_BIT
                EGL10.EGL_NONE
            )

            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            egl?.eglChooseConfig(eglDisplay, configAttribs, configs, 1, numConfigs)
            eglConfig = configs[0]

            val contextAttribs = intArrayOf(
                0x3098, 2, // EGL_CONTEXT_CLIENT_VERSION
                EGL10.EGL_NONE
            )
            eglContext = egl?.eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, contextAttribs)

            // 创建 Window Surface 从 inputSurface
            eglSurface = egl?.eglCreateWindowSurface(eglDisplay, eglConfig, inputSurface, null)

            if (!egl?.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)!!) {
                Log.e(TAG, "eglMakeCurrent 失败")
                return false
            }

            Log.d(TAG, "EGL 初始化成功")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "初始化 EGL 失败", e)
            return false
        }
    }

    /**
     * 配置渲染器视角
     */
    internal fun configureViewType(renderer: OffscreenRenderer?, viewType: ViewType, scale: Float = 1.0f, flipped: Boolean = false, showDancerNames: Boolean = true) {
        val baseDistance = 20f / scale  // 根据缩放调整距离
        when (viewType) {
            ViewType.VIEW_45_DEGREE -> {
                renderer?.setCameraView(CameraView(pitch = -30f, yaw = 0f, z = baseDistance))
                renderer?.setIs2DMode(false)
                renderer?.setFlipped(flipped)
                renderer?.setShowDancerNames(showDancerNames)
            }
            ViewType.VIEW_2D -> {
                renderer?.setCameraView(CameraView(pitch = -89f, yaw = 0f, z = baseDistance))
                renderer?.setIs2DMode(true)
                renderer?.setFlipped(flipped)
                renderer?.setShowDancerNames(showDancerNames)
            }
            ViewType.REFERENCE_VIDEO -> {
                // 参考视频不需要配置渲染器
            }
        }
    }

    /**
     * 获取指定时间的阵型和插值进度。
     *
     * 与预览保持相同的前红后绿规则：下一关键帧的 duration 是到达该帧前的过渡时长，
     * holdDuration 是到达该帧后的保持时长。
     */
    internal fun getFormationAtTime(formations: List<Formation>, timeMs: Long): Triple<Formation, Formation?, Float> {
        if (formations.isEmpty()) {
            // 返回一个空的默认阵型
            return Triple(Formation(id = "empty", name = "空阵型"), null, 0f)
        }
        if (formations.size == 1) {
            return Triple(formations[0], null, 0f)
        }

        for (i in formations.indices) {
            val current = formations[i]
            val next = formations.getOrNull(i + 1)
            val transitionStartMs = current.timestamp - current.duration
            val holdEndMs = current.timestamp + current.holdDuration

            if (i > 0 && current.duration > 0 && timeMs >= transitionStartMs && timeMs < current.timestamp) {
                val previous = formations[i - 1]
                val progress = ((timeMs - transitionStartMs).toFloat() / current.duration)
                    .coerceIn(0f, 1f)
                return Triple(previous, current, progress)
            }

            if (timeMs >= current.timestamp && timeMs < holdEndMs) {
                return Triple(current, next, 0f)
            }
        }

        // 超出最后一帧：保持最后一个阵型
        return Triple(formations.last(), null, 0f)
    }

    /**
     * 混合音频到输出文件
     * @param audioUri 音频URI
     * @param exportDurationMs 导出时长（毫秒）
     * @param startMs 入点时间（毫秒）
     * @param endMs 出点时间（毫秒）
     */
    internal fun mixAudio(audioUri: Uri, exportDurationMs: Long, startMs: Long, endMs: Long) {
        mixAudioImpl(audioUri, startMs, endMs)
    }

    /**
     * 解码并渲染视频帧（连续解码，避免跳帧）
     * @return 返回当前解码的时间戳
     */
    internal fun decodeAndRenderVideoFrame(
        extractor: MediaExtractor,
        decoder: MediaCodec,
        surfaceTexture: SurfaceTexture,
        textureId: Int,
        targetTimeMs: Long,
        lastTimeUs: Long,
        videoFps: Int,
        videoWidth: Int,
        videoHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        rotationDegrees: Int = 0
    ): Long {
        val targetTimeUs = targetTimeMs * 1000
        var currentTimeUs = lastTimeUs

        try {
            // 如果目标时间距离当前时间过远（超过1秒），重新seek
            if (lastTimeUs < 0 || kotlin.math.abs(targetTimeUs - lastTimeUs) > 1_000_000) {
                extractor.seekTo(targetTimeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                decoder.flush()  // 清空解码器缓冲
                currentTimeUs = -1
                Log.d(TAG, "Seek到 ${targetTimeMs}ms")
            }

            var frameDecoded = false
            var inputEnded = false
            var hasRetainedFrame = false
            var attempts = 0
            val maxAttempts = 30  // 增加尝试次数

            // 连续解码直到找到目标帧
            while (!frameDecoded && attempts < maxAttempts) {
                // 读取并解码一帧
                val inputBufferIndex = if (inputEnded) -1 else decoder.dequeueInputBuffer(TIMEOUT_USEC)
                if (inputBufferIndex >= 0) {
                    val inputBuffer = decoder.getInputBuffer(inputBufferIndex)
                    inputBuffer?.clear()

                    val sampleSize = extractor.readSampleData(inputBuffer!!, 0)
                    if (sampleSize > 0) {
                        val presentationTimeUs = extractor.sampleTime
                        decoder.queueInputBuffer(inputBufferIndex, 0, sampleSize, presentationTimeUs, 0)
                        extractor.advance()
                    } else {
                        // 视频结束时送出结束标记，保留最后一帧，不能回到开头造成画面回跳。
                        decoder.queueInputBuffer(
                            inputBufferIndex,
                            0,
                            0,
                            0L,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        inputEnded = true
                    }
                }

                // 获取解码后的输出
                val bufferInfo = MediaCodec.BufferInfo()
                val outputBufferIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)

                if (outputBufferIndex >= 0) {
                    val frameTimeUs = bufferInfo.presentationTimeUs
                    currentTimeUs = frameTimeUs

                    // 检查是否是我们需要的帧（允许一帧的误差）
                    val frameDuration = if (videoFps > 0) 1_000_000 / videoFps else 33333
                    if (bufferInfo.size > 0 && frameTimeUs >= targetTimeUs - frameDuration / 2) {
                        // 这是我们需要的帧，渲染它
                        decoder.releaseOutputBuffer(outputBufferIndex, true)
                        surfaceTexture.updateTexImage()
                        renderVideoTexture(
                            textureId = textureId,
                            videoWidth = videoWidth,
                            videoHeight = videoHeight,
                            targetWidth = targetWidth,
                            targetHeight = targetHeight,
                            surfaceTexture = surfaceTexture,
                            rotationDegrees = rotationDegrees
                        )
                        frameDecoded = true
                    } else if (bufferInfo.size > 0 && inputEnded) {
                        // 目标时间超出参考视频时，将最后可用帧提交到纹理，供本轮导出保持显示。
                        decoder.releaseOutputBuffer(outputBufferIndex, true)
                        surfaceTexture.updateTexImage()
                        hasRetainedFrame = true
                    } else {
                        // 这帧太早，丢弃它，继续解码
                        decoder.releaseOutputBuffer(outputBufferIndex, false)
                    }
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        break
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    Log.d(TAG, "视频解码器输出格式变化")
                } else if (inputEnded && outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    break
                }

                attempts++
            }

            if (!frameDecoded) {
                if (hasRetainedFrame) {
                    renderVideoTexture(
                        textureId = textureId,
                        videoWidth = videoWidth,
                        videoHeight = videoHeight,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                        surfaceTexture = surfaceTexture,
                        rotationDegrees = rotationDegrees
                    )
                } else {
                    // 没有任何可用帧时才填充黑色。
                    GLES20.glClearColor(0f, 0f, 0f, 1f)
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                    Log.w(TAG, "未能解码到目标帧 ${targetTimeMs}ms")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解码视频帧失败", e)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        }

        return currentTimeUs
    }

    /**
     * 渲染视频纹理到屏幕（保持宽高比，添加黑边）
     */
    internal fun renderVideoTexture(
        textureId: Int,
        videoWidth: Int,
        videoHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        surfaceTexture: SurfaceTexture? = null,
        rotationDegrees: Int = 0
    ) {
        val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
        val isQuarterTurn = normalizedRotation == 90 || normalizedRotation == 270
        val displayVideoWidth = if (isQuarterTurn) videoHeight else videoWidth
        val displayVideoHeight = if (isQuarterTurn) videoWidth else videoHeight

        // 计算保持宽高比的绘制区域
        val videoAspect = displayVideoWidth.toFloat() / displayVideoHeight.toFloat()
        val targetAspect = targetWidth.toFloat() / targetHeight.toFloat()

        var scaleX: Float
        var scaleY: Float

        if (videoAspect > targetAspect) {
            // 视频更宽，以宽度为准，上下有黑边
            scaleX = 1f
            scaleY = targetAspect / videoAspect
        } else {
            // 视频更窄，以高度为准，左右有黑边
            scaleX = videoAspect / targetAspect
            scaleY = 1f
        }

        // 简单的着色器程序，用于渲染外部纹理
        val vertexShaderCode = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        val fragmentShaderCode = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        GLES20.glUseProgram(program)

        // 计算保持宽高比的顶点坐标（NDC坐标系）
        val left = -scaleX
        val right = scaleX
        val bottom = -scaleY
        val top = scaleY

        // 外部纹理基础 UV。SurfaceTexture 的变换矩阵已包含视频帧的坐标转换，不能预先翻转 Y 轴。
        val baseTexCoords = floatArrayOf(
            0f, 0f,
            1f, 0f,
            0f, 1f,
            1f, 1f
        )

        // 应用SurfaceTexture变换矩阵（裁剪/镜像）
        val textureMatrix = FloatArray(16).apply {
            Matrix.setIdentityM(this, 0)
        }
        surfaceTexture?.getTransformMatrix(textureMatrix)

        val transformedTexCoords = FloatArray(8)
        for (i in 0 until 4) {
            val u = baseTexCoords[i * 2]
            val v = baseTexCoords[i * 2 + 1]

            val tu = textureMatrix[0] * u + textureMatrix[4] * v + textureMatrix[12]
            val tv = textureMatrix[1] * u + textureMatrix[5] * v + textureMatrix[13]

            // 旋转以匹配源视频rotation元数据（顺时针）
            val centeredU = tu - 0.5f
            val centeredV = tv - 0.5f
            val rotated = when (normalizedRotation) {
                90 -> Pair(0.5f + centeredV, 0.5f - centeredU)
                180 -> Pair(0.5f - centeredU, 0.5f - centeredV)
                270 -> Pair(0.5f - centeredV, 0.5f + centeredU)
                else -> Pair(tu, tv)
            }
            transformedTexCoords[i * 2] = rotated.first
            transformedTexCoords[i * 2 + 1] = rotated.second
        }

        val vertices = floatArrayOf(
            left, bottom, transformedTexCoords[0], transformedTexCoords[1],
            right, bottom, transformedTexCoords[2], transformedTexCoords[3],
            left, top, transformedTexCoords[4], transformedTexCoords[5],
            right, top, transformedTexCoords[6], transformedTexCoords[7]
        )

        val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)

        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(positionHandle)
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        val textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(textureHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
    }

    /**
     * 合并左右帧
     */
    internal fun mergeFrames(left: IntArray, right: IntArray, output: IntArray, halfWidth: Int, height: Int) {
        for (y in 0 until height) {
            // 复制左半屏
            System.arraycopy(left, y * halfWidth, output, y * width, halfWidth)
            // 复制右半屏
            System.arraycopy(right, y * halfWidth, output, y * width + halfWidth, halfWidth)
        }
    }

    /**
     * 编码一帧
     */
    internal fun encodeFrame(pixels: IntArray, presentationTimeUs: Long) {
        // 设置视口
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // 将像素数据绘制到 Surface
        drawPixelsToSurface(pixels)

        // 设置时间戳（使用EGL14 API）
        android.opengl.EGLExt.eglPresentationTimeANDROID(
            android.opengl.EGL14.eglGetCurrentDisplay(),
            android.opengl.EGL14.eglGetCurrentSurface(android.opengl.EGL14.EGL_DRAW),
            presentationTimeUs * 1000 // 转换为纳秒
        )

        // 交换缓冲，提交到编码器
        egl?.eglSwapBuffers(eglDisplay, eglSurface)

        // 处理编码器输出
        drainEncoder(false)
    }

    /**
     * 将像素数据绘制到 Surface
     */
    internal fun drawPixelsToSurface(pixels: IntArray) {
        // 创建纹理
        val textureId = IntArray(1)
        GLES20.glGenTextures(1, textureId, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId[0])

        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        // 转换像素格式并上传
        val buffer = ByteBuffer.allocateDirect(pixels.size * 4)
        buffer.order(ByteOrder.nativeOrder())
        val intBuffer = buffer.asIntBuffer()
        intBuffer.put(pixels)
        intBuffer.position(0)

        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer
        )

        // 绘制全屏四边形
        drawFullscreenQuad(textureId[0])

        // 删除纹理
        GLES20.glDeleteTextures(1, textureId, 0)
    }

    /**
     * 绘制全屏四边形
     */
    internal fun drawFullscreenQuad(textureId: Int) {
        val vertexShaderCode = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = aTexCoord;
            }
        """.trimIndent()

        val fragmentShaderCode = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vTexCoord;
            void main() {
                gl_FragColor = texture2D(uTexture, vTexCoord);
            }
        """.trimIndent()

        val vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)

        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        GLES20.glUseProgram(program)

        val vertices = floatArrayOf(
            -1f, -1f, 0f, 1f,
            1f, -1f, 1f, 1f,
            -1f, 1f, 0f, 0f,
            1f, 1f, 1f, 0f
        )

        val vertexBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .put(vertices)
        vertexBuffer.position(0)

        val positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        GLES20.glEnableVertexAttribArray(positionHandle)
        vertexBuffer.position(0)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        val texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(texCoordHandle)
        vertexBuffer.position(2)
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 16, vertexBuffer)

        val textureHandle = GLES20.glGetUniformLocation(program, "uTexture")
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId)
        GLES20.glUniform1i(textureHandle, 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(texCoordHandle)
        GLES20.glDeleteProgram(program)
        GLES20.glDeleteShader(vertexShader)
        GLES20.glDeleteShader(fragmentShader)
    }

    internal fun loadShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        return shader
    }

    /**
     * 处理编码器输出
     */
    internal fun drainEncoder(endOfStream: Boolean) {
        val encoder = encoder ?: return

        if (endOfStream) {
            encoder.signalEndOfInputStream()
        }

        val bufferInfo = MediaCodec.BufferInfo()

        while (true) {
            val outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC)

            when {
                outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) break
                }
                outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxerStarted) {
                        Log.w(TAG, "格式变化但 muxer 已启动")
                    } else {
                        val newFormat = encoder.outputFormat
                        videoTrackIndex = muxer?.addTrack(newFormat) ?: -1
                        muxer?.start()
                        muxerStarted = true
                    }
                }
                outputBufferIndex >= 0 -> {
                    val encodedData = encoder.getOutputBuffer(outputBufferIndex)

                    if (encodedData != null && bufferInfo.size > 0 && muxerStarted &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        encodedData.position(bufferInfo.offset)
                        encodedData.limit(bufferInfo.offset + bufferInfo.size)
                        muxer?.writeSampleData(videoTrackIndex, encodedData, bufferInfo)
                        encodedSampleCount++
                    }

                    encoder.releaseOutputBuffer(outputBufferIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break
                    }
                }
            }
        }
    }

    /**
     * 释放资源
     */
    internal fun release() {
        try {
            // encoder和muxer已经在export()中释放，这里只释放其他资源
            encoder?.stop()
            encoder?.release()
            encoder = null

            if (muxerStarted) {
                muxer?.stop()
            }
            muxer?.release()
            muxer = null

            inputSurface?.release()
            inputSurface = null

            leftRenderer?.release()
            rightRenderer?.release()

            egl?.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
            egl?.eglDestroySurface(eglDisplay, eglSurface)
            egl?.eglDestroyContext(eglDisplay, eglContext)
            egl?.eglTerminate(eglDisplay)
        } catch (e: Exception) {
            Log.e(TAG, "释放资源失败", e)
        }
    }
}

/**
 * 离屏渲染器 - 用于渲染单个视角
 */
