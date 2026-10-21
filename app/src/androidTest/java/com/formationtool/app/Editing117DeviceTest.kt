package com.formationtool.app

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.formationtool.app.data.DataManager
import com.formationtool.app.export.VideoExporter
import com.formationtool.app.model.*
import java.io.File
import kotlinx.coroutines.runBlocking
import com.formationtool.app.viewmodel.setDancersToCount
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Editing117DeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context get() = instrumentation.targetContext

    private fun fixture(name: String): File = File(context.filesDir, "qa-$name").also { file ->
        instrumentation.context.assets.open(name).use { input -> file.outputStream().use { input.copyTo(it) } }
    }

    @Test fun missingInstallPermissionOffersSystemAuthorizationInsteadOfOnlyWebsite() {
        if (android.os.Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()) return
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val type = com.formationtool.app.update.AppUpdateChecker::class.java
                val method = type.getDeclaredMethod("downloadAndInstallApk", android.app.Activity::class.java,
                    com.formationtool.app.update.AppUpdateChecker.UpdateInfo::class.java)
                method.isAccessible = true
                method.invoke(com.formationtool.app.update.AppUpdateChecker, activity,
                    com.formationtool.app.update.AppUpdateChecker.UpdateInfo("9.9.9", "https://example.com/test.apk",
                        "test.apk", "0".repeat(64), 1024, "授权测试", false))
            }
            onView(withText("前往授权")).check(matches(isDisplayed()))
            onView(withText("取消")).perform(click())
        }
    }

    @Test fun replacingMediaPersistsNewBytesWithoutLosingFormation() {
        val old = fixture("reference-a.mp4")
        val replacement = fixture("reference-b.mp4")
        val store = DataManager(context)
        val group = DanceGroup("qa-media117", "媒体替换回归", dancerCount = 1,
            formations = mutableListOf(Formation("first", dancers = mutableListOf(Dancer("one", "原成员", 2f, 3f)))))
        group.referenceVideoUri = Uri.fromFile(old).toString()
        group.audioUri = Uri.fromFile(old).toString()
        assertTrue(store.saveDanceGroup(group))
        val oldPath = group.referenceVideoPath
        group.referenceVideoUri = Uri.fromFile(replacement).toString()
        group.audioUri = Uri.fromFile(replacement).toString()
        assertTrue(store.saveDanceGroup(group))
        val reopened = store.loadDanceGroup(group.id)!!
        assertNotEquals(oldPath, reopened.referenceVideoPath)
        assertArrayEquals(replacement.readBytes(), File(reopened.referenceVideoPath!!).readBytes())
        assertArrayEquals(replacement.readBytes(), File(reopened.audioPath!!).readBytes())
        assertEquals(2f, reopened.formations[0].dancers[0].x)
        assertEquals("原成员", reopened.formations[0].dancers[0].name)
    }

    @Test fun activitySupportsThirtyDancersMoveUndoRedoAndReplacement() {
        val firstVideo = fixture("reference-a.mp4")
        val nextVideo = fixture("reference-b.mp4")
        context.getSharedPreferences("app_state", Context.MODE_PRIVATE).edit().clear().commit()
        var originalX = 0f
        var savedId = ""
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.viewModel.createNewGroup("设备30人回归", 30, 32f, 20f)
                val group = activity.viewModel.currentGroup.value!!
                activity.currentProjectId = group.id
                savedId = group.id
                originalX = group.formations[0].dancers[0].x
                activity.loadVideoAfterProjectReady(Uri.fromFile(firstVideo), true)
                activity.loadVideoAfterProjectReady(Uri.fromFile(nextVideo), true)
                assertEquals(30, activity.viewModel.currentGroup.value!!.formations[0].dancers.size)
                activity.viewModel.moveCurrentFormation(1f, 0f)
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(originalX + 1f, activity.viewModel.currentGroup.value!!.formations[0].dancers[0].x, 0.001f)
                activity.findViewById<Button>(R.id.btnUndo).performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(originalX, activity.viewModel.currentGroup.value!!.formations[0].dancers[0].x, 0.001f)
                activity.findViewById<Button>(R.id.btnRedo).performClick()
            }
            instrumentation.waitForIdleSync()
            scenario.onActivity { activity ->
                assertEquals(originalX + 1f, activity.viewModel.currentGroup.value!!.formations[0].dancers[0].x, 0.001f)
                val play = activity.findViewById<Button>(R.id.btnAudioPlayPause)
                assertTrue(play.layoutParams.width >= 48 * activity.resources.displayMetrics.density - 1)
                runBlocking { activity.viewModel.setDancersToCount(5, activity::getDefaultColor) }
                assertEquals(5, activity.viewModel.currentGroup.value!!.dancerCount)
                activity.viewModel.undo()
                activity.saveCurrentProjectSilent()
            }
        }
        val reopened = DataManager(context).loadDanceGroup(savedId)!!
        assertEquals(30, reopened.dancerCount)
        assertArrayEquals(nextVideo.readBytes(), File(reopened.referenceVideoPath!!).readBytes())
    }

    @Test fun encoderExportsThirtyMembersWithTransparentNamesAndMirroredCoordinates() {
        val output = File(context.filesDir, "qa-export117.mp4")
        val formation = Formation("first", holdDuration = 1500, dancers = (1..30).map { number ->
            Dancer(number.toString(), "成员$number", x = ((number - 1) % 10 - 4.5f) * 1.2f,
                y = ((number - 1) / 10 - 1) * 2f, color = 0xFFFF6600.toInt())
        }.toMutableList())
        val exporter = VideoExporter(context, output, width = 640, height = 360, fps = 15)
        assertTrue(exporter.exportWithConfig(VideoExporter.ExportConfig(layoutMode = VideoExporter.LayoutMode.SINGLE,
            primaryView = VideoExporter.ViewType.VIEW_2D, mirrored = true, dancerScale = 1.3f),
            listOf(formation), 1000, 16f, 12f))
        assertTrue(output.length() > 1000)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(output.path)
            assertEquals("640", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
            assertEquals("360", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
            val frame = retriever.getFrameAtTime(0)!!
            File(context.filesDir, "qa-export117.png").outputStream().use { frame.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally { retriever.release() }
    }
}
