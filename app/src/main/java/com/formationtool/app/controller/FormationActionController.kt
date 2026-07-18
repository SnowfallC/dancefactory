package com.formationtool.app.controller

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.LifecycleCoroutineScope
import com.formationtool.app.audio.AudioManager
import com.formationtool.app.model.Formation
import com.formationtool.app.viewmodel.FormationViewModel
import com.formationtool.app.viewmodel.insertKeyframeSmart
import com.formationtool.app.viewmodel.setDancersToCount
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

object FormationActionController {
    private const val KEYFRAME_MATCH_TOLERANCE_MS = 50L
    private var isSettingDancerCount = false

    fun copyPreviousKeyframe(
        context: Context,
        viewModel: FormationViewModel,
        audioManager: AudioManager,
        lifecycleScope: LifecycleCoroutineScope,
        onScrollToTime: (Long) -> Unit,
        onSyncReferenceVideo: (Long) -> Unit
    ) {
        val group = viewModel.currentGroup.value
        if (group == null || group.formations.isEmpty()) {
            Toast.makeText(context, "请先创建工程", Toast.LENGTH_SHORT).show()
            return
        }

        val formations = group.formations
        val currentTime = viewModel.currentTime.value
        val currentIndex = viewModel.currentFormationIndex.value
        val currentFormation = formations[currentIndex]
        val onKeyframe = abs(currentTime - currentFormation.timestamp) < KEYFRAME_MATCH_TOLERANCE_MS

        if (onKeyframe) {
            if (currentIndex <= 0) {
                Toast.makeText(context, "已经是第一帧，无法复制", Toast.LENGTH_SHORT).show()
                return
            }
            val prevFormation = formations[currentIndex - 1]
            val dancerCount = min(prevFormation.dancers.size, currentFormation.dancers.size)
            for (i in 0 until dancerCount) {
                val prevDancer = prevFormation.dancers[i]
                val currentDancer = currentFormation.dancers[i]
                currentDancer.x = prevDancer.x
                currentDancer.y = prevDancer.y
            }
            viewModel.notifyGroupChanged()
            Toast.makeText(context, "已复制前一帧的舞者位置", Toast.LENGTH_SHORT).show()
            return
        }

        val prevFormation = findPreviousFormation(formations, currentTime)
        if (prevFormation == null) {
            Toast.makeText(context, "当前时间之前没有关键帧", Toast.LENGTH_SHORT).show()
            return
        }

        val dancerPositions = mutableMapOf<String, Triple<Float, Float, Float>>()
        for (dancer in prevFormation.dancers) {
            dancerPositions[dancer.id] = Triple(dancer.x, dancer.y, dancer.z)
        }

        lifecycleScope.launch {
            val newFormation = viewModel.insertKeyframeSmart(currentTime, dancerPositions)
            if (newFormation != null) {
                viewModel.seekTo(currentTime)
                audioManager.seekTo(currentTime)
                onScrollToTime(currentTime)
                onSyncReferenceVideo(currentTime)
            } else {
                Toast.makeText(context, "创建关键帧失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun setDancerCount(
        context: Context,
        viewModel: FormationViewModel,
        lifecycleScope: LifecycleCoroutineScope,
        targetCount: Int,
        getDefaultColor: (Int) -> Int
    ) {
        if (isSettingDancerCount) {
            Log.d("MainActivity", "设置舞者数量请求被防抖拦截")
            return
        }

        lifecycleScope.launch {
            try {
                isSettingDancerCount = true

                val group = viewModel.currentGroup.value
                val currentCount = group?.formations?.firstOrNull()?.dancers?.size ?: 0
                if (currentCount == targetCount) {
                    return@launch
                }

                Log.d("MainActivity", "设置舞者数量: 从 $currentCount 人到 $targetCount 人")

                viewModel.setDancersToCount(targetCount, getDefaultColor)
                delay(200)

                Log.d("MainActivity", "设置舞者数量完成")
            } catch (e: Exception) {
                Log.e("MainActivity", "设置舞者数量失败", e)
                Toast.makeText(context, "操作失败: ${e.message}", Toast.LENGTH_SHORT).show()
            } finally {
                delay(200)
                isSettingDancerCount = false
            }
        }
    }

    private fun findPreviousFormation(formations: List<Formation>, currentTime: Long): Formation? {
        for (i in formations.size - 1 downTo 0) {
            if (formations[i].timestamp < currentTime) {
                return formations[i]
            }
        }
        return null
    }
}
