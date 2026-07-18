package com.formationtool.app.audio

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import kotlin.math.abs

/**
 * 音频管理器 - 负责音频播放和时间同步
 */
class AudioManager(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition: StateFlow<Long> = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    private val _waveformData = MutableStateFlow<FloatArray>(FloatArray(0))
    val waveformData: StateFlow<FloatArray> = _waveformData.asStateFlow()

    /**
     * 加载音频文件
     */
    fun loadAudio(uri: Uri): Boolean {
        return try {
            release()

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, uri)
                prepare()
                _duration.value = duration.toLong()

                setOnCompletionListener {
                    _currentPosition.value = 0L
                    _isPlaying.value = false
                }
            }

            // 异步提取波形数据
            extractWaveform(uri)

            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 提取音频波形数据
     */
    private fun extractWaveform(uri: Uri) {
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)

            // 查找音频轨
            var audioTrackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME)
                if (mime?.startsWith("audio/") == true) {
                    audioTrackIndex = i
                    break
                }
            }

            if (audioTrackIndex < 0) {
                // 没找到音频轨，生成模拟波形
                generateFakeWaveform()
                return
            }

            extractor.selectTrack(audioTrackIndex)

            // 读取音频样本
            val samples = mutableListOf<Float>()
            val buffer = ByteBuffer.allocate(8192)
            val maxSamples = 500 // 波形点数量
            var sampleCount = 0
            var maxAmplitude = 0.001f // 避免除0

            while (sampleCount < maxSamples) {
                val readSize = extractor.readSampleData(buffer, 0)
                if (readSize < 0) break

                buffer.rewind()

                // 计算振幅
                var sum = 0f
                var count = 0
                try {
                    while (buffer.remaining() >= 2 && count < 200) {
                        val sample = abs(buffer.short.toFloat())
                        sum += sample
                        count++
                    }
                } catch (e: Exception) {
                    // 忽略读取错误
                }

                val amplitude = if (count > 0) sum / count else 0f
                samples.add(amplitude)
                maxAmplitude = maxOf(maxAmplitude, amplitude)

                sampleCount++
                buffer.clear()
                extractor.advance()
            }

            extractor.release()

            if (samples.isEmpty()) {
                generateFakeWaveform()
                return
            }

            // 归一化振幅
            val normalized = samples.map {
                (it / maxAmplitude).coerceIn(0.1f, 1f)
            }.toFloatArray()

            _waveformData.value = normalized

        } catch (e: Exception) {
            e.printStackTrace()
            generateFakeWaveform()
        }
    }

    /**
     * 生成模拟波形数据
     */
    private fun generateFakeWaveform() {
        val samples = FloatArray(500) { index ->
            // 生成类似音乐的波形
            val t = index / 500f
            val wave1 = kotlin.math.sin(t * 20 * Math.PI).toFloat() * 0.5f
            val wave2 = kotlin.math.sin(t * 50 * Math.PI).toFloat() * 0.3f
            val envelope = kotlin.math.sin(t * Math.PI).toFloat()
            (abs(wave1 + wave2) * envelope).coerceIn(0.1f, 1f)
        }
        _waveformData.value = samples
    }

    /**
     * 播放音频
     */
    fun play() {
        mediaPlayer?.let {
            if (!it.isPlaying) {
                // 确保当前位置同步（修复第一次播放时的跳帧问题）
                try {
                    val targetPos = _currentPosition.value.toInt()
                    val currentPos = it.currentPosition
                    // 如果位置差异超过50ms，重新seek
                    if (kotlin.math.abs(targetPos - currentPos) > 50) {
                        it.seekTo(targetPos)
                        // 等待seek完成（短暂延迟）
                        Thread.sleep(50)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                it.start()
                _isPlaying.value = true
            }
        }
    }

    /**
     * 暂停播放
     */
    fun pause() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                it.pause()
                _isPlaying.value = false
            }
        }
    }

    /**
     * 跳转到指定位置
     */
    fun seekTo(positionMs: Long) {
        mediaPlayer?.seekTo(positionMs.toInt())
        _currentPosition.value = positionMs
    }

    /**
     * 获取当前播放位置
     */
    fun getCurrentPosition(): Long {
        return mediaPlayer?.currentPosition?.toLong() ?: 0L
    }

    /**
     * 更新当前位置（用于UI同步）
     */
    fun updatePosition() {
        mediaPlayer?.let {
            if (it.isPlaying) {
                _currentPosition.value = it.currentPosition.toLong()
            }
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        mediaPlayer?.apply {
            if (isPlaying) {
                stop()
            }
            release()
        }
        mediaPlayer = null
        _isPlaying.value = false
        _currentPosition.value = 0L
        _duration.value = 0L
    }
}
