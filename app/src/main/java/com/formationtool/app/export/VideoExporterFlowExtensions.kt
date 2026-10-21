package com.formationtool.app.export

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import com.formationtool.app.export.VideoExporter.ExportConfig
import com.formationtool.app.export.VideoExporter.LayoutMode
import com.formationtool.app.export.VideoExporter.ViewType
import com.formationtool.app.model.Formation
import java.nio.ByteBuffer

private const val TAG = "VideoExporter"

private fun mediaCodecFlags(sampleFlags: Int): Int {
    var flags = 0
    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
        flags = flags or MediaCodec.BUFFER_FLAG_KEY_FRAME
    }
    if (sampleFlags and MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME != 0) {
        flags = flags or MediaCodec.BUFFER_FLAG_PARTIAL_FRAME
    }
    return flags
}


internal fun VideoExporter.exportWithConfigImpl(
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
        if (formations.isEmpty()) {
            callback?.onError("没有关键帧数据")
            return false
        }

        try {
            callback?.onProgress(0f, "初始化编码器...")

            if (!initEncoder()) {
                callback?.onError("初始化编码器失败")
                return false
            }

            referenceVideoMirrored = config.referenceVideoMirrored
            callback?.onProgress(0.05f, "初始化渲染环境...")
            if (!initEGL()) {
                callback?.onError("初始化 EGL 失败")
                release()
                return false
            }

            // 根据布局模式计算渲染区域尺寸
            val (primaryWidth, primaryHeight, secondaryWidth, secondaryHeight) = when (config.layoutMode) {
                LayoutMode.SINGLE -> listOf(width, height, 0, 0)
                LayoutMode.SPLIT_HORIZONTAL -> listOf(width / 2, height, width / 2, height)
                LayoutMode.SPLIT_VERTICAL -> listOf(width, height / 2, width, height / 2)
            }

            // 初始化渲染器
            val primaryRenderer = if (config.primaryView != ViewType.REFERENCE_VIDEO) {
                OffscreenRenderer(primaryWidth, primaryHeight, stageWidth, stageDepth, false).also {
                    it.backstageVisible = backstageVisible
                    it.mirrored = config.mirrored
                    it.dancerScale = config.dancerScale.coerceIn(0.5f, 2f)
                    it.init()
                    configureViewType(it, config.primaryView, config.viewScale, config.flipped, config.showDancerNames)
                }
            } else null

            val secondaryRenderer = if (config.layoutMode != LayoutMode.SINGLE && config.secondaryView != ViewType.REFERENCE_VIDEO) {
                OffscreenRenderer(secondaryWidth, secondaryHeight, stageWidth, stageDepth, false).also {
                    it.backstageVisible = backstageVisible
                    it.mirrored = config.mirrored
                    it.dancerScale = config.dancerScale.coerceIn(0.5f, 2f)
                    it.init()
                    configureViewType(it, config.secondaryView, config.viewScale, config.flipped, config.showDancerNames)
                }
            } else null

            val exportStartMs = inPointMs.coerceIn(0L, durationMs)
            val exportEndMs = outPointMs.coerceIn(exportStartMs, durationMs)
            val exportDurationMs = exportEndMs - exportStartMs

            // 初始化参考视频解码器
            var videoExtractor: MediaExtractor? = null
            var videoDecoder: MediaCodec? = null
            var videoSurfaceTexture: SurfaceTexture? = null
            var videoTextureId = IntArray(1)
            var referenceVideoWidth = 0
            var referenceVideoHeight = 0
            var referenceVideoFps = fps
            var referenceVideoRotationDegrees = 0
            var lastVideoTimeUs = -1L

            val needRefVideo = config.primaryView == ViewType.REFERENCE_VIDEO ||
                              (config.layoutMode != LayoutMode.SINGLE && config.secondaryView == ViewType.REFERENCE_VIDEO)

            if (needRefVideo && referenceVideoUri != null) {
                callback?.onProgress(0.1f, "加载参考视频...")
                try {
                    videoExtractor = MediaExtractor()
                    videoExtractor.setDataSource(context, referenceVideoUri, null)

                    for (i in 0 until videoExtractor.trackCount) {
                        val format = videoExtractor.getTrackFormat(i)
                        val mime = format.getString(MediaFormat.KEY_MIME)
                        if (mime?.startsWith("video/") == true) {
                            videoExtractor.selectTrack(i)
                            referenceVideoWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                            referenceVideoHeight = format.getInteger(MediaFormat.KEY_HEIGHT)

                            if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                                referenceVideoFps = format.getInteger(MediaFormat.KEY_FRAME_RATE)
                            }
                            if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                                referenceVideoRotationDegrees = runCatching {
                                    format.getInteger(MediaFormat.KEY_ROTATION)
                                }.getOrDefault(0)
                                // 解码器输出到 Surface 时会自动应用该旋转，清零后统一由纹理渲染处理。
                                format.setInteger(MediaFormat.KEY_ROTATION, 0)
                            }

                            videoDecoder = MediaCodec.createDecoderByType(mime)

                            GLES20.glGenTextures(1, videoTextureId, 0)
                            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId[0])
                            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

                            videoSurfaceTexture = SurfaceTexture(videoTextureId[0])
                            val videoDecoderOutputSurface = Surface(videoSurfaceTexture)

                            videoDecoder.configure(format, videoDecoderOutputSurface, null, 0)
                            videoDecoder.start()
                            videoExtractor.seekTo(exportStartMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
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
            // One export clock prevents preview/export drift. The reference
            // video's FPS is used only for source-frame selection.
            val outputFps = fps.coerceAtLeast(1)
            val totalFrames = kotlin.math.ceil(exportDurationMs * outputFps / 1000.0).toInt()

            callback?.onProgress(0.15f, "开始渲染帧...")

            for (frameIndex in 0 until totalFrames) {
                if (isCancelled) {
                    callback?.onError("导出已取消")
                    release()
                    return false
                }

                val presentationTimeUs = frameIndex * 1_000_000L / outputFps
                val currentTimeMs = exportStartMs + presentationTimeUs / 1000L

                val (currentFormation, nextFormation, progress) = getFormationAtTime(formations, currentTimeMs)

                primaryRenderer?.setFormation(currentFormation, nextFormation, progress)
                secondaryRenderer?.setFormation(currentFormation, nextFormation, progress)

                GLES20.glViewport(0, 0, width, height)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

                when (config.layoutMode) {
                    LayoutMode.SINGLE -> {
                        GLES20.glViewport(0, 0, width, height)
                        if (config.primaryView == ViewType.REFERENCE_VIDEO && videoDecoder != null && videoExtractor != null && videoSurfaceTexture != null) {
                            lastVideoTimeUs = decodeAndRenderVideoFrame(
                                videoExtractor, videoDecoder, videoSurfaceTexture, videoTextureId[0],
                                currentTimeMs, lastVideoTimeUs, referenceVideoFps,
                                referenceVideoWidth, referenceVideoHeight, width, height,
                                referenceVideoRotationDegrees
                            )
                        } else {
                            primaryRenderer?.renderDirect()
                        }
                    }
                    LayoutMode.SPLIT_HORIZONTAL -> {
                        // 左半屏
                        GLES20.glViewport(0, 0, primaryWidth, height)
                        GLES20.glScissor(0, 0, primaryWidth, height)
                        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                        if (config.primaryView == ViewType.REFERENCE_VIDEO && videoDecoder != null && videoExtractor != null && videoSurfaceTexture != null) {
                            lastVideoTimeUs = decodeAndRenderVideoFrame(
                                videoExtractor, videoDecoder, videoSurfaceTexture, videoTextureId[0],
                                currentTimeMs, lastVideoTimeUs, referenceVideoFps,
                                referenceVideoWidth, referenceVideoHeight, primaryWidth, height,
                                referenceVideoRotationDegrees
                            )
                        } else {
                            primaryRenderer?.renderDirect()
                        }

                        // 右半屏
                        GLES20.glViewport(primaryWidth, 0, secondaryWidth, height)
                        GLES20.glScissor(primaryWidth, 0, secondaryWidth, height)
                        if (config.secondaryView == ViewType.REFERENCE_VIDEO && videoDecoder != null && videoExtractor != null && videoSurfaceTexture != null) {
                            lastVideoTimeUs = decodeAndRenderVideoFrame(
                                videoExtractor, videoDecoder, videoSurfaceTexture, videoTextureId[0],
                                currentTimeMs, lastVideoTimeUs, referenceVideoFps,
                                referenceVideoWidth, referenceVideoHeight, secondaryWidth, height,
                                referenceVideoRotationDegrees
                            )
                        } else {
                            secondaryRenderer?.renderDirect()
                        }
                        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                    }
                    LayoutMode.SPLIT_VERTICAL -> {
                        // 上半屏
                        GLES20.glViewport(0, secondaryHeight, width, primaryHeight)
                        GLES20.glScissor(0, secondaryHeight, width, primaryHeight)
                        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                        if (config.primaryView == ViewType.REFERENCE_VIDEO && videoDecoder != null && videoExtractor != null && videoSurfaceTexture != null) {
                            lastVideoTimeUs = decodeAndRenderVideoFrame(
                                videoExtractor, videoDecoder, videoSurfaceTexture, videoTextureId[0],
                                currentTimeMs, lastVideoTimeUs, referenceVideoFps,
                                referenceVideoWidth, referenceVideoHeight, width, primaryHeight,
                                referenceVideoRotationDegrees
                            )
                        } else {
                            primaryRenderer?.renderDirect()
                        }

                        // 下半屏
                        GLES20.glViewport(0, 0, width, secondaryHeight)
                        GLES20.glScissor(0, 0, width, secondaryHeight)
                        if (config.secondaryView == ViewType.REFERENCE_VIDEO && videoDecoder != null && videoExtractor != null && videoSurfaceTexture != null) {
                            lastVideoTimeUs = decodeAndRenderVideoFrame(
                                videoExtractor, videoDecoder, videoSurfaceTexture, videoTextureId[0],
                                currentTimeMs, lastVideoTimeUs, referenceVideoFps,
                                referenceVideoWidth, referenceVideoHeight, width, secondaryHeight,
                                referenceVideoRotationDegrees
                            )
                        } else {
                            secondaryRenderer?.renderDirect()
                        }
                        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                    }
                }

                android.opengl.EGLExt.eglPresentationTimeANDROID(
                    android.opengl.EGL14.eglGetCurrentDisplay(),
                    android.opengl.EGL14.eglGetCurrentSurface(android.opengl.EGL14.EGL_DRAW),
                    presentationTimeUs * 1000
                )

                egl?.eglSwapBuffers(eglDisplay, eglSurface)
                drainEncoder(false)

                val exportProgress = 0.15f + 0.8f * (frameIndex.toFloat() / totalFrames)
                if (frameIndex % 10 == 0) {
                    callback?.onProgress(exportProgress, "渲染帧 $frameIndex / $totalFrames")
                }
            }

            callback?.onProgress(0.95f, "完成编码...")
            drainEncoder(true)
            if (!muxerStarted || encodedSampleCount == 0) {
                throw IllegalStateException("编码器未生成有效视频帧")
            }

            encoder?.stop()
            encoder?.release()
            encoder = null

            if (muxerStarted) {
                muxer?.stop()
            }
            muxer?.release()
            muxer = null
            muxerStarted = false

            videoDecoder?.stop()
            videoDecoder?.release()
            videoExtractor?.release()

            primaryRenderer?.release()
            secondaryRenderer?.release()

            val audioSource = audioUri ?: referenceVideoUri
            if (audioSource != null) {
                try {
                    callback?.onProgress(0.98f, "混合音频...")
                    mixAudioImpl(audioSource, exportStartMs, exportEndMs)
                } catch (e: Exception) {
                    Log.e(TAG, "混合音频失败，但视频已导出", e)
                }
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

internal fun VideoExporter.mixAudioImpl(audioUri: Uri, startMs: Long, endMs: Long) {
        var tempVideoFile: java.io.File? = null
        var newMuxer: MediaMuxer? = null
        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null

        try {
            // 检查输出文件是否存在
            if (!outputFile.exists()) {
                Log.e(TAG, "输出文件不存在: ${outputFile.absolutePath}")
                return
            }

            // Keep the known-good video in place until the replacement MP4 has
            // been fully finalized. This prevents 0 KB output on remux errors.
            tempVideoFile = java.io.File(outputFile.parent, "${outputFile.nameWithoutExtension}.muxing_${System.currentTimeMillis()}.mp4")

            newMuxer = MediaMuxer(tempVideoFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            runCatching {
                newMuxer?.setOrientationHint(0)
            }.onFailure {
                Log.w(TAG, "设置重封装方向失败，继续使用默认方向", it)
            }

            // 复制视频轨
            videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(outputFile.absolutePath)
            var videoTrackIndex = -1

            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("video/") == true) {
                    videoExtractor.selectTrack(i)
                    if (format.containsKey(MediaFormat.KEY_ROTATION)) {
                        val sourceRotation = runCatching { format.getInteger(MediaFormat.KEY_ROTATION) }.getOrDefault(0)
                        Log.d(TAG, "检测到源视频旋转元数据: ${sourceRotation}°，导出时重置为 0°")
                        runCatching {
                            format.setInteger(MediaFormat.KEY_ROTATION, 0)
                        }.onFailure {
                            Log.w(TAG, "重置视频轨旋转元数据失败，继续使用原始轨道格式", it)
                        }
                    }
                    videoTrackIndex = newMuxer.addTrack(format)
                    Log.d(TAG, "添加视频轨: $mime")
                    break
                }
            }

            if (videoTrackIndex < 0) {
                Log.e(TAG, "找不到视频轨")
                return
            }

            // 添加音频轨
            audioExtractor = MediaExtractor()
            audioExtractor.setDataSource(context, audioUri, null)
            var audioTrackIndex = -1

            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioExtractor.selectTrack(i)
                    audioTrackIndex = newMuxer.addTrack(format)
                    Log.d(TAG, "添加音频轨: $mime")
                    break
                }
            }

            // 定位音频到入点位置
            if (audioTrackIndex >= 0 && startMs > 0) {
                audioExtractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                Log.d(TAG, "音频定位到入点: ${startMs}ms")
            }

            newMuxer.start()

            // 复制视频数据
            val videoBuffer = ByteBuffer.allocate(1024 * 1024)
            val videoBufferInfo = MediaCodec.BufferInfo()

            while (true) {
                videoBuffer.clear()
                val sampleSize = videoExtractor.readSampleData(videoBuffer, 0)
                if (sampleSize < 0) break

                videoBufferInfo.offset = 0
                videoBufferInfo.size = sampleSize
                videoBufferInfo.presentationTimeUs = videoExtractor.sampleTime
                videoBufferInfo.flags = mediaCodecFlags(videoExtractor.sampleFlags)

                newMuxer.writeSampleData(videoTrackIndex, videoBuffer, videoBufferInfo)
                videoExtractor.advance()
            }

            // 复制音频数据（只复制入点到出点的音频）
            if (audioTrackIndex >= 0) {
                val audioBuffer = ByteBuffer.allocate(1024 * 1024)
                val audioBufferInfo = MediaCodec.BufferInfo()
                val startTimeUs = startMs * 1000
                val endTimeUs = endMs * 1000
                var audioTimeOffset = 0L  // 音频时间偏移，用于将音频重新对齐到0

                while (true) {
                    audioBuffer.clear()
                    val sampleSize = audioExtractor.readSampleData(audioBuffer, 0)
                    if (sampleSize < 0) break

                    val originalTimeUs = audioExtractor.sampleTime

                    // 跳过入点之前的音频
                    if (originalTimeUs < startTimeUs) {
                        audioExtractor.advance()
                        continue
                    }

                    // 超过出点，停止
                    if (originalTimeUs >= endTimeUs) break

                    // 记录第一帧的时间偏移
                    if (audioTimeOffset == 0L) {
                        audioTimeOffset = originalTimeUs
                    }

                    // 重新对齐时间戳（从0开始）
                    val adjustedTimeUs = originalTimeUs - audioTimeOffset

                    audioBufferInfo.offset = 0
                    audioBufferInfo.size = sampleSize
                    audioBufferInfo.presentationTimeUs = adjustedTimeUs
                    audioBufferInfo.flags = mediaCodecFlags(audioExtractor.sampleFlags)

                    newMuxer.writeSampleData(audioTrackIndex, audioBuffer, audioBufferInfo)
                    audioExtractor.advance()
                }

                Log.d(TAG, "音频复制完成: ${startMs}ms -> ${endMs}ms")
            } else {
                Log.w(TAG, "找不到音频轨，只复制视频")
            }

            newMuxer.stop()
            newMuxer.release()
            newMuxer = null
            videoExtractor.release()
            videoExtractor = null
            audioExtractor.release()
            audioExtractor = null

            if (!tempVideoFile.exists() || tempVideoFile.length() <= 0L) {
                throw IllegalStateException("音频重封装未生成有效文件")
            }
            val backupVideoFile = java.io.File(outputFile.parent, "${outputFile.nameWithoutExtension}.pre_mux_${System.currentTimeMillis()}.mp4")
            if (!outputFile.renameTo(backupVideoFile)) {
                throw IllegalStateException("无法备份原始视频")
            }
            try {
                if (!tempVideoFile.renameTo(outputFile)) {
                    throw IllegalStateException("无法替换重封装视频")
                }
                backupVideoFile.delete()
            } catch (e: Exception) {
                if (!outputFile.exists() && backupVideoFile.exists()) {
                    backupVideoFile.renameTo(outputFile)
                }
                throw e
            }

            Log.d(TAG, "音频混合完成")
        } catch (e: Exception) {
            Log.e(TAG, "混合音频失败", e)

            // 清理资源
            runCatching { newMuxer?.stop() }
            newMuxer?.release()
            videoExtractor?.release()
            audioExtractor?.release()

            tempVideoFile?.delete()
        }
    }
