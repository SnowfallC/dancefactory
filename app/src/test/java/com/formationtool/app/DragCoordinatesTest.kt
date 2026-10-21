package com.formationtool.app

import com.formationtool.app.model.CameraView
import com.formationtool.app.utils.DragCoordinates
import org.junit.Assert.*
import org.junit.Test

class DragCoordinatesTest {
    @Test fun topViewSceneFollowsFingerInAllFourDirections() {
        val camera = CameraView(pitch = -89f)
        for ((fingerX, fingerY) in listOf(20f to 0f, -20f to 0f, 0f to 20f, 0f to -20f)) {
            val pan = DragCoordinates.cameraPanDelta(-fingerX, -fingerY, camera, 1200f, 800f)
            // 俯视渲染中，targetX 增大会让场景向左，targetY 增大会让场景向下。
            assertEquals(kotlin.math.sign(fingerX), kotlin.math.sign(-pan.first), 0f)
            assertEquals(kotlin.math.sign(fingerY), kotlin.math.sign(pan.second), 0f)
        }
    }

    @Test fun stageFlipChangesDancerDepthButDoesNotChangeCameraPan() {
        val camera = CameraView(pitch = -89f)
        val normal = DragCoordinates.stageDelta(20f, 10f, camera, 1200f, 800f, false)
        val flipped = DragCoordinates.stageDelta(20f, 10f, camera, 1200f, 800f, true)
        assertEquals(normal.first, flipped.first, 0.0001f)
        assertEquals(normal.second, -flipped.second, 0.0001f)
        val pan = DragCoordinates.cameraPanDelta(20f, 10f, camera, 1200f, 800f)
        assertEquals(normal.first, pan.first, 0.0001f)
        assertEquals(-normal.second, pan.second, 0.0001f)
    }

    @Test fun respectsZoomAndHandlesUninitializedViewport() {
        val camera = CameraView(pitch = -89f)
        val near = DragCoordinates.cameraPanDelta(10f, 10f, camera, 1200f, 800f)
        val far = DragCoordinates.cameraPanDelta(10f, 10f, camera.copy(z = camera.z * 2), 1200f, 800f)
        assertEquals(near.first * 2, far.first, 0.0001f)
        assertEquals(near.second * 2, far.second, 0.0001f)
        val uninitialized = DragCoordinates.cameraPanDelta(10f, 10f, camera, 0f, 0f)
        assertEquals(0f, uninitialized.first, 0f)
        assertEquals(0f, uninitialized.second, 0f)
    }
}
