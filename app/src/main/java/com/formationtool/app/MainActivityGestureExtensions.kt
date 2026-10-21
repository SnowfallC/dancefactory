package com.formationtool.app

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.formationtool.app.model.backstageWidth
import com.formationtool.app.utils.DragCoordinates
import com.formationtool.app.viewmodel.createKeyframe
import com.formationtool.app.viewmodel.insertKeyframeWithPositions


internal fun MainActivity.setupGestures() {
        var isDraggingDancer = false
        var selectedDancerId: String? = null
        var lastTouchX = 0f
        var lastTouchY = 0f
        var originalDancerPositions: Map<String, Triple<Float, Float, Float>>? = null  // 保存拖动前的原始位置
        var hasMoved = false  // 标记是否真的发生了拖动
        var isDoubleTap = false  // 标记是否是双击

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // 点击选中舞者
                val dancer = findDancerAtScreenPoint(e.x, e.y)
                if (dancer != null) {
                    viewModel.toggleDancerSelection(dancer.id)
                    return true
                }
                return false
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (!isDraggingDancer) {
                    if (is3DMode) {
                        // 3D模式：允许旋转相机
                        // distanceX > 0 表示向左拖动，场景应该向左旋转（相机向右旋转，yaw增大）
                        // distanceY > 0 表示向上拖动，应该俯视角增大（pitch增大，更从上往下看）
                        val camera = viewModel.cameraView.value
                        viewModel.updateCamera(
                            yaw = camera.yaw + distanceX * 0.1f,
                            pitch = (camera.pitch + distanceY * 0.1f).coerceIn(-89f, 89f)
                        )
                    } else {
                        // 相机偏移独立于舞台翻转，确保画面跟随手指平移。
                        val camera = viewModel.cameraView.value
                        val worldDelta = DragCoordinates.cameraPanDelta(
                            distanceX, distanceY, camera, glSurfaceView.width.toFloat(), glSurfaceView.height.toFloat()
                        )
                        viewModel.updateCamera(
                            targetX = camera.targetX + worldDelta.first,
                            targetY = camera.targetY + worldDelta.second
                        )
                    }
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                // 标记双击，防止 ACTION_UP 时误触发 createKeyframe
                isDoubleTap = true

                // 根据模式重置相机
                if (is3DMode) {
                    // 3D模式：重置为45度斜视角
                    viewModel.updateCamera(pitch = -30f, yaw = 0f, distance = 15f, targetX = 0f, targetY = 0f)
                } else {
                    // 2D模式：重置为俯视角
                    viewModel.updateCamera(pitch = -89f, yaw = 0f, distance = 15f, targetX = 0f, targetY = 0f)
                }
                return true
            }
        })

        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val camera = viewModel.cameraView.value
                val newDistance = (camera.z / detector.scaleFactor).coerceIn(5f, 50f)
                viewModel.updateCamera(distance = newDistance)
                return true
            }
        })

        glSurfaceView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                    hasMoved = false  // 重置移动标记
                    isDoubleTap = false  // 重置双击标记

                    // 先检查是否有已选中的舞者（从列表点击选中）
                    val group = viewModel.currentGroup.value
                    val formation = group?.formations?.getOrNull(viewModel.currentFormationIndex.value)
                    val selectedDancer = formation?.dancers?.find { it.isSelected }

                    if (selectedDancer != null) {
                        // 如果已有选中的舞者，直接进入拖动模式
                        isDraggingDancer = true
                        selectedDancerId = selectedDancer.id

                        // 创建快照副本（与PC端一致：从当前显示的插值位置创建，而不是当前帧）
                        dragDisplayDancers = getInterpolatedDancers()?.toMutableList()

                        // 初始化原始拖动位置（用于吸附时的跟手拖动）
                        val snapshotDancer = dragDisplayDancers?.find { it.id == selectedDancer.id }
                        rawDragX = snapshotDancer?.x ?: 0f
                        rawDragY = snapshotDancer?.y ?: 0f

                        // 保存当前帧的原始位置（用于关键帧逻辑）
                        originalDancerPositions = formation?.dancers?.associate {
                            it.id to Triple(it.x, it.y, it.z)
                        }

                        // 立即设置渲染器使用快照数据，避免跳变
                        if (dragDisplayDancers != null) {
                            val tempFormation = com.formationtool.app.model.Formation(
                                id = "temp_drag",
                                name = "temp",
                                dancers = dragDisplayDancers!!.toMutableList(),
                                timestamp = viewModel.currentTime.value,
                                holdDuration = 0L,
                                duration = 0L
                            )
                            renderer.currentFormation = tempFormation
                            renderer.nextFormation = null
                            renderer.transitionProgress = -1f
                            glSurfaceView.requestRender()
                        }
                    } else {
                        // 没有选中的舞者，检查是否点击到舞者
                        val dancer = findDancerAtScreenPoint(event.x, event.y)
                        if (dancer != null && dancer.isSelected) {
                            isDraggingDancer = true
                            selectedDancerId = dancer.id

                            // 创建快照副本（从插值位置）
                            dragDisplayDancers = getInterpolatedDancers()?.toMutableList()

                            // 初始化原始拖动位置（用于吸附时的跟手拖动）
                            val snapshotDancer = dragDisplayDancers?.find { it.id == dancer.id }
                            rawDragX = snapshotDancer?.x ?: 0f
                            rawDragY = snapshotDancer?.y ?: 0f

                            // 保存原始位置
                            originalDancerPositions = formation?.dancers?.associate {
                                it.id to Triple(it.x, it.y, it.z)
                            }

                            // 立即设置渲染器使用快照数据，避免跳变
                            if (dragDisplayDancers != null) {
                                val tempFormation = com.formationtool.app.model.Formation(
                                    id = "temp_drag",
                                    name = "temp",
                                    dancers = dragDisplayDancers!!.toMutableList(),
                                    timestamp = viewModel.currentTime.value,
                                    holdDuration = 0L,
                                    duration = 0L
                                )
                                renderer.currentFormation = tempFormation
                                renderer.nextFormation = null
                                renderer.transitionProgress = -1f
                                glSurfaceView.requestRender()
                            }
                        }
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isDraggingDancer && selectedDancerId != null && dragDisplayDancers != null) {
                        // 拖动舞者
                        val deltaX = event.x - lastTouchX
                        val deltaY = event.y - lastTouchY

                        // 检查是否真的移动了（降低阈值，更容易触发）
                        if (kotlin.math.abs(deltaX) > 2 || kotlin.math.abs(deltaY) > 2) {
                            hasMoved = true
                        }

                        // 将屏幕移动转换为舞台坐标
                        val worldDelta = screenToWorldDelta(deltaX, deltaY)

                        val group = viewModel.currentGroup.value

                        // 在快照中查找并更新舞者位置
                        val snapshotDancer = dragDisplayDancers?.find { it.id == selectedDancerId }
                        snapshotDancer?.let { dancer ->
                            // 获取舞台边界
                            val halfWidth = (group?.stageWidth ?: 16f) / 2f
                            val halfDepth = (group?.stageDepth ?: 12f) / 2f

                            // 累积移动量到原始位置（不吸附，保证跟手）
                            rawDragX += worldDelta.first
                            rawDragY += worldDelta.second

                            // 限制在舞台范围内
                            rawDragX = rawDragX.coerceIn(-halfWidth - (group?.backstageWidth() ?: 0f), halfWidth + (group?.backstageWidth() ?: 0f))
                            rawDragY = rawDragY.coerceIn(-halfDepth, halfDepth)

                            // 计算显示位置：如果开启吸附则吸附，否则使用原始位置
                            val displayX: Float
                            val displayY: Float
                            if (autoSnap) {
                                displayX = kotlin.math.round(rawDragX * 2) / 2f
                                displayY = kotlin.math.round(rawDragY * 2) / 2f
                            } else {
                                displayX = rawDragX
                                displayY = rawDragY
                            }

                            // 更新舞者位置（使用显示位置）
                            dancer.x = displayX
                            dancer.y = displayY

                            // 使用快照创建临时formation用于渲染（避免修改真实数据）
                            val tempFormation = com.formationtool.app.model.Formation(
                                id = "temp_drag",
                                name = "temp",
                                dancers = dragDisplayDancers!!.toMutableList(),
                                timestamp = viewModel.currentTime.value,
                                holdDuration = 0L,
                                duration = 0L
                            )

                            // 立即刷新渲染器（使用快照数据，禁用插值）
                            renderer.currentFormation = tempFormation
                            renderer.nextFormation = null  // 禁用插值，确保显示快照位置
                            renderer.transitionProgress = -1f
                            glSurfaceView.requestRender()

                            // 同时更新编号覆盖层
                            val nextFormation = if (viewModel.currentFormationIndex.value + 1 < (group?.formations?.size ?: 0)) {
                                group?.formations?.get(viewModel.currentFormationIndex.value + 1)
                            } else null
                            updateDancerLabelOverlay(tempFormation, nextFormation, 0f)
                        }

                        lastTouchX = event.x
                        lastTouchY = event.y
                        return@setOnTouchListener true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 只有真的拖动了且不是双击时才创建关键帧
                    if (isDraggingDancer && hasMoved && !isDoubleTap && dragDisplayDancers != null) {
                        val group = viewModel.currentGroup.value
                        val currentTime = viewModel.currentTime.value

                        // 检查是否在红色过渡区（插值区域）
                        val isInTransition = isCurrentTimeInTransition()

                        if (group?.standardMode == true) {
                            val moved = dragDisplayDancers?.find { it.id == selectedDancerId }
                            if (moved != null) viewModel.updateIntervalDancer(moved.id, moved.x, moved.y)
                        } else if (isInTransition) {
                            // 在红色过渡区：先用插值位置创建新关键帧，然后更新拖动的舞者位置
                            val interpolatedDancers = getInterpolatedDancers()
                            if (interpolatedDancers != null && group != null) {
                                // 创建一个修改后的舞者列表：大部分使用插值位置，拖动的舞者使用新位置
                                val modifiedDancers = interpolatedDancers.map { interpolated ->
                                    // 检查这个舞者是否被拖动（在快照中查找）
                                    val snapshotDancer = dragDisplayDancers?.find { it.id == interpolated.id }
                                    if (snapshotDancer != null) {
                                        // 使用拖动后的位置
                                        com.formationtool.app.model.Dancer(
                                            id = interpolated.id,
                                            name = interpolated.name,
                                            x = snapshotDancer.x,
                                            y = snapshotDancer.y,
                                            z = interpolated.z,
                                            color = interpolated.color,
                                            customStylePath = interpolated.customStylePath,
                                            isSelected = interpolated.isSelected
                                        )
                                    } else {
                                        // 使用插值位置
                                        interpolated
                                    }
                                }

                                // 调用ViewModel插入新关键帧，使用修改后的位置
                                viewModel.insertKeyframeWithPositions(currentTime, modifiedDancers)
                            }
                        } else {
                            // 在绿色保持区：直接更新当前帧的位置
                            val formation = group?.formations?.getOrNull(viewModel.currentFormationIndex.value)

                            dragDisplayDancers?.forEach { snapshotDancer ->
                                formation?.dancers?.find { it.id == snapshotDancer.id }?.let { realDancer ->
                                    realDancer.x = snapshotDancer.x
                                    realDancer.y = snapshotDancer.y
                                }
                            }

                            // 同步并创建关键帧
                            viewModel.syncCurrentGroup()
                            viewModel.createKeyframe(currentTime, originalDancerPositions)
                        }
                    }
                    // 重置所有状态
                    isDraggingDancer = false
                    selectedDancerId = null
                    originalDancerPositions = null
                    dragDisplayDancers = null
                    hasMoved = false
                    isDoubleTap = false
                }
            }

            gestureDetector.onTouchEvent(event)
            scaleDetector.onTouchEvent(event)
            true
        }
    }

internal fun MainActivity.findDancerAtScreenPoint(screenX: Float, screenY: Float): com.formationtool.app.model.Dancer? {
        val group = viewModel.currentGroup.value ?: return null
        val formation = group.formations.getOrNull(viewModel.currentFormationIndex.value) ?: return null

        if (formation.dancers.isEmpty()) return null

        // 获取视口尺寸
        val viewWidth = glSurfaceView.width.toFloat()
        val viewHeight = glSurfaceView.height.toFloat()

        if (viewWidth <= 0 || viewHeight <= 0) return null

        // 获取相机参数
        val camera = viewModel.cameraView.value

        // 设置投影矩阵（与渲染器一致）
        val projectionMatrix = FloatArray(16)
        val viewMatrix = FloatArray(16)
        val vpMatrix = FloatArray(16)
        val modelMatrix = FloatArray(16)
        val mvpMatrix = FloatArray(16)
        val resultVec = FloatArray(4)
        val inputVec = floatArrayOf(0f, 0f, 0f, 1f)

        val ratio = viewWidth / viewHeight
        android.opengl.Matrix.perspectiveM(projectionMatrix, 0, camera.fov, ratio, 0.1f, 100f)

        // 设置视图矩阵（与渲染器完全一致）
        val pitchRad = Math.toRadians(-camera.pitch.toDouble())
        val yawRad = Math.toRadians(camera.yaw.toDouble())

        val eyeX = (camera.z * kotlin.math.cos(pitchRad) * kotlin.math.sin(yawRad)).toFloat()
        val eyeY = (camera.z * kotlin.math.sin(pitchRad)).toFloat()
        val eyeZ = (camera.z * kotlin.math.cos(pitchRad) * kotlin.math.cos(yawRad)).toFloat()

        var upX = 0f
        var upY = 1f
        var upZ = 0f

        if (kotlin.math.abs(camera.pitch) > 85f) {
            upY = 0f
            upZ = if (camera.pitch < 0) -1f else 1f
        }

        // 应用观察点偏移（与渲染器保持一致）
        // 注意：targetY 需要取负值，因为舞台坐标Y对应OpenGL坐标Z（深度）
        android.opengl.Matrix.setLookAtM(
            viewMatrix, 0,
            eyeX + camera.targetX, eyeY, eyeZ - camera.targetY,
            camera.targetX, 0f, -camera.targetY,
            upX, upY, upZ
        )

        // 计算 VP 矩阵
        android.opengl.Matrix.multiplyMM(vpMatrix, 0, projectionMatrix, 0, viewMatrix, 0)

        // 对每个舞者，计算其屏幕位置，找到最近的
        var closestDancer: com.formationtool.app.model.Dancer? = null
        var minScreenDistance = Float.MAX_VALUE
        val hitRadius = 60f  // 屏幕像素半径

        formation.dancers.forEach { dancer ->
            // 设置模型矩阵（与渲染器一致：Y 和 Z 互换，并考虑翻转状态）
            android.opengl.Matrix.setIdentityM(modelMatrix, 0)
            // Y轴方向需要与渲染器保持一致
            val renderY = if (renderer.flipped) -dancer.y else dancer.y
            android.opengl.Matrix.translateM(modelMatrix, 0, dancer.x, dancer.z, renderY)

            // 计算 MVP 矩阵
            android.opengl.Matrix.multiplyMM(mvpMatrix, 0, vpMatrix, 0, modelMatrix, 0)

            // 将原点变换到屏幕坐标
            android.opengl.Matrix.multiplyMV(resultVec, 0, mvpMatrix, 0, inputVec, 0)

            // 透视除法
            if (resultVec[3] <= 0) return@forEach

            val ndcX = resultVec[0] / resultVec[3]
            val ndcY = resultVec[1] / resultVec[3]

            // 转换为屏幕坐标
            val dancerScreenX = (ndcX + 1f) / 2f * viewWidth
            val dancerScreenY = (1f - ndcY) / 2f * viewHeight

            // 计算屏幕距离
            val dx = screenX - dancerScreenX
            val dy = screenY - dancerScreenY
            val screenDistance = kotlin.math.sqrt(dx * dx + dy * dy)

            if (screenDistance < minScreenDistance && screenDistance < hitRadius) {
                minScreenDistance = screenDistance
                closestDancer = dancer
            }
        }

        return closestDancer
    }

internal fun MainActivity.getInterpolatedDancers(): List<com.formationtool.app.model.Dancer>? {
        val group = viewModel.currentGroup.value ?: return null
        val formations = group.formations
        val currentTime = viewModel.currentTime.value
        val currentFormationIndex = viewModel.currentFormationIndex.value

        if (formations.isEmpty()) return null

        // 查找当前时间点所在的区间
        var displayFormation: com.formationtool.app.model.Formation? = null  // 要显示的帧
        var sourceFormation: com.formationtool.app.model.Formation? = null   // 插值的起始帧
        var progress = -1f  // -1 表示不需要插值

        for (i in formations.indices) {
            val f = formations[i]
            val timestamp = f.timestamp
            val redStart = timestamp - f.duration  // 红色区开始
            val greenEnd = timestamp + f.holdDuration  // 绿色区结束

            // 检查是否在红色区内（前面的过渡区）- 第一帧无红色区
            if (i > 0 && f.duration > 0 && currentTime >= redStart && currentTime < timestamp) {
                // 在红色区内，需要插值（从前一帧到当前帧）
                sourceFormation = formations[i - 1]  // 插值起点：前一帧
                displayFormation = f  // 插值终点：当前帧
                val elapsed = currentTime - redStart
                val duration = f.duration
                progress = if (duration > 0) minOf(1f, elapsed.toFloat() / duration) else 0f
                break
            }

            // 检查是否在绿色区内（保持区）
            if (currentTime >= timestamp && currentTime < greenEnd) {
                displayFormation = f
                sourceFormation = null
                progress = -1f
                break
            }
        }

        // 如果没找到，说明在最后一帧之后
        if (displayFormation == null) {
            displayFormation = formations.last()
        }

        // 计算最终结果
        return if (sourceFormation != null && progress >= 0f) {
            // 在红色过渡区：进行插值
            interpolateDancers(sourceFormation, displayFormation, progress)
        } else {
            // 在绿色保持区或第一帧：直接复制当前帧的舞者
            displayFormation.dancers.map { d ->
                com.formationtool.app.model.Dancer(
                    id = d.id,
                    name = d.name,
                    x = d.x,
                    y = d.y,
                    z = d.z,
                    color = d.color,
                    customStylePath = d.customStylePath,
                    isSelected = d.isSelected
                )
            }
        }
    }

internal fun MainActivity.interpolateDancers(
        from: com.formationtool.app.model.Formation,
        to: com.formationtool.app.model.Formation,
        progress: Float
    ): List<com.formationtool.app.model.Dancer> {
        val result = mutableListOf<com.formationtool.app.model.Dancer>()
        val safeProgress = progress.coerceIn(0f, 1f)

        from.dancers.forEach { fromDancer ->
            // 通过ID查找对应的下一帧舞者
            val toDancer = to.dancers.find { it.id == fromDancer.id }

            val (x, y, z) = if (toDancer != null) {
                Triple(
                    fromDancer.x + (toDancer.x - fromDancer.x) * safeProgress,
                    fromDancer.y + (toDancer.y - fromDancer.y) * safeProgress,
                    fromDancer.z + (toDancer.z - fromDancer.z) * safeProgress
                )
            } else {
                Triple(fromDancer.x, fromDancer.y, fromDancer.z)
            }

            result.add(com.formationtool.app.model.Dancer(
                id = fromDancer.id,
                name = fromDancer.name,
                x = x,
                y = y,
                z = z,
                color = fromDancer.color,
                customStylePath = fromDancer.customStylePath,
                isSelected = fromDancer.isSelected
            ))
        }

        return result
    }

internal fun MainActivity.isCurrentTimeInTransition(): Boolean {
        val group = viewModel.currentGroup.value ?: return false
        val formations = group.formations
        val currentTime = viewModel.currentTime.value

        if (formations.isEmpty()) return false

        for (i in formations.indices) {
            val f = formations[i]
            val timestamp = f.timestamp
            val redStart = timestamp - f.duration

            // 检查是否在红色区内（第一帧无红色区）
            if (i > 0 && f.duration > 0 && currentTime >= redStart && currentTime < timestamp) {
                return true
            }
        }

        return false
    }

internal fun MainActivity.screenToWorldDelta(deltaX: Float, deltaY: Float): Pair<Float, Float> =
    DragCoordinates.stageDelta(
        deltaX, deltaY, viewModel.cameraView.value,
        glSurfaceView.width.toFloat(), glSurfaceView.height.toFloat(), renderer.flipped
    )
