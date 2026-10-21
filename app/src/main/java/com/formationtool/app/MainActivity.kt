package com.formationtool.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.GridLayout
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.formationtool.app.adapter.DancerAdapter
import com.formationtool.app.audio.AudioManager
import com.formationtool.app.renderer.FormationRenderer
import com.formationtool.app.viewmodel.FormationViewModel
import com.formationtool.app.view.WaveformView
import com.formationtool.app.model.DanceGroup
import com.formationtool.app.model.Formation
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.graphics.Color as AndroidColor

import android.widget.VideoView
import com.formationtool.app.view.DancerLabelOverlay
import com.formationtool.app.data.DataManager
import com.formationtool.app.update.AppUpdateChecker
import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import com.formationtool.app.controller.DancerDialogController
import com.formationtool.app.controller.ExportDialogController
import com.formationtool.app.controller.FormationActionController
import com.formationtool.app.controller.KeyframeController
import com.formationtool.app.controller.StageSettingsDialogController
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    internal val viewModel: FormationViewModel by viewModels()
    internal lateinit var glSurfaceView: GLSurfaceView
    internal lateinit var renderer: FormationRenderer
    internal lateinit var audioManager: AudioManager
    internal lateinit var dancerLabelOverlay: DancerLabelOverlay
    internal lateinit var referenceVideoView: VideoView
    internal lateinit var dataManager: DataManager  // 数据管理器

    // 自动保存定时器
    internal var autoSaveJob: kotlinx.coroutines.Job? = null
    internal var audioPlayingObserverJob: Job? = null
    internal var waveformObserverJob: Job? = null
    internal var videoPlaybackSyncJob: Job? = null
    internal var videoSeekSyncJob: Job? = null
    internal val AUTO_SAVE_INTERVAL = 30_000L  // 30秒

    // 防抖标志
    internal var isDeletingDancer = false  // 防止连续快速删除舞者

    internal lateinit var gestureDetector: GestureDetector
    internal lateinit var scaleDetector: ScaleGestureDetector
    internal var is3DMode = true // 当前是否为3D模式
    internal var autoSnap = false  // 是否自动吸附到网格（手机端默认关闭，提升跟手性）

    // 拖动快照机制（与PC端一致）
    internal var dragDisplayDancers: MutableList<com.formationtool.app.model.Dancer>? = null  // 拖动时的舞者位置快照
    internal var rawDragX = 0f  // 拖动时的原始位置（未吸附），用于跟手拖动
    internal var rawDragY = 0f
    internal var hasReferenceVideo = false
    internal var referenceVideoUri: Uri? = null  // 参考视频URI
    internal var currentAudioUri: Uri? = null    // 当前音频URI
    internal var currentProjectId: String? = null  // 当前工程ID
    internal var pendingStyleDancerId: String? = null
    internal var isPreviewMode = false  // 预览模式
    internal var isScrollingProgrammatically = false  // 标记是否为程序控制滚动
    internal var keyframeController: KeyframeController? = null  // F模式控制器

    // 权限请求码
    internal val PERMISSION_REQUEST_CODE = 100

    // 时间轴缩放
    internal var previousStandardMode: Boolean? = null
    internal var timelineZoom = 1f // 1.0 ~ 15.0
    internal lateinit var waveformView: WaveformView
    internal lateinit var timelineContent: FrameLayout

    // 导出范围（入点和出点）
    internal var exportInPointMs: Long = 0L
    internal var exportOutPointMs: Long = 0L

    // LoveLive 团体预设数据
    data class DancerPreset(val name: String, val color: Int)

    internal val museDancers = listOf(
        DancerPreset("穗乃果", AndroidColor.parseColor("#FF6600")),
        DancerPreset("绘里", AndroidColor.parseColor("#7FFFFF")),
        DancerPreset("小鸟", AndroidColor.parseColor("#C8C8C8")),
        DancerPreset("海未", AndroidColor.parseColor("#1569C7")),
        DancerPreset("凛", AndroidColor.parseColor("#FFF000")),
        DancerPreset("真姬", AndroidColor.parseColor("#FF3333")),
        DancerPreset("希", AndroidColor.parseColor("#744896")),
        DancerPreset("花阳", AndroidColor.parseColor("#5BAE23")),
        DancerPreset("妮可", AndroidColor.parseColor("#FF60AF"))
    )

    internal val aqoursDancers = listOf(
        DancerPreset("千歌", AndroidColor.parseColor("#FF7F27")),
        DancerPreset("梨子", AndroidColor.parseColor("#FF9EAC")),
        DancerPreset("果南", AndroidColor.parseColor("#00D29E")),
        DancerPreset("黛雅", AndroidColor.parseColor("#F23B4C")),
        DancerPreset("曜", AndroidColor.parseColor("#5AC7FA")),
        DancerPreset("善子", AndroidColor.parseColor("#C0C0C0")),
        DancerPreset("花丸", AndroidColor.parseColor("#FFDD00")),
        DancerPreset("鞠莉", AndroidColor.parseColor("#AE59F5")),
        DancerPreset("露比", AndroidColor.parseColor("#FF6FCF"))
    )

    internal val nijiDancers = listOf(
        DancerPreset("步梦", AndroidColor.parseColor("#FFAAC8")),
        DancerPreset("霞", AndroidColor.parseColor("#FFDC00")),
        DancerPreset("雫", AndroidColor.parseColor("#A0D8EF")),
        DancerPreset("果林", AndroidColor.parseColor("#4682B4")),
        DancerPreset("爱", AndroidColor.parseColor("#FF6600")),
        DancerPreset("彼方", AndroidColor.parseColor("#9966CC")),
        DancerPreset("雪菜", AndroidColor.parseColor("#EE0000")),
        DancerPreset("艾玛", AndroidColor.parseColor("#88CC66")),
        DancerPreset("璃奈", AndroidColor.parseColor("#C8C8C8")),
        DancerPreset("栞子", AndroidColor.parseColor("#00D0B1")),
        DancerPreset("米娅", AndroidColor.parseColor("#B0C4DE")),
        DancerPreset("钟岚珠", AndroidColor.parseColor("#F8C8DC")),
        DancerPreset("高咲侑", AndroidColor.parseColor("#DC143C"))
    )

    internal val liellaDancers = listOf(
        DancerPreset("香音", AndroidColor.parseColor("#FF7F27")),
        DancerPreset("可可", AndroidColor.parseColor("#5EC8F5")),
        DancerPreset("千砂都", AndroidColor.parseColor("#FF6FCF")),
        DancerPreset("堇", AndroidColor.parseColor("#74DF00")),
        DancerPreset("恋", AndroidColor.parseColor("#7B68EE")),
        DancerPreset("希奈子", AndroidColor.parseColor("#FFF44F")),
        DancerPreset("芽衣", AndroidColor.parseColor("#EE1166")),
        DancerPreset("四季", AndroidColor.parseColor("#9CA5B0")),
        DancerPreset("夏美", AndroidColor.parseColor("#BB88FF")),
        DancerPreset("薇恩", AndroidColor.parseColor("#CC0033")),
        DancerPreset("冬毬", AndroidColor.parseColor("#FFB6C1"))
    )

    internal val hasunosoraDancers = listOf(
        DancerPreset("花帆", AndroidColor.parseColor("#E2A9A1")),
        DancerPreset("沙耶香", AndroidColor.parseColor("#5B8DBF")),
        DancerPreset("乙宗梢", AndroidColor.parseColor("#976E9A")),
        DancerPreset("缀理", AndroidColor.parseColor("#F8C8C4")),
        DancerPreset("瑠璃乃", AndroidColor.parseColor("#6BB6BB")),
        DancerPreset("藤岛慈", AndroidColor.parseColor("#C3D825")),
        DancerPreset("吟子", AndroidColor.parseColor("#F8E577")),
        DancerPreset("小铃", AndroidColor.parseColor("#EE86A7")),
        DancerPreset("姬芽", AndroidColor.parseColor("#F39E7D")),
        DancerPreset("塞拉斯", AndroidColor.parseColor("#A77BCA")),
        DancerPreset("桂城泉", AndroidColor.parseColor("#9CA5B0"))
    )

    // 颜色选择预设（20色）
    internal val presetColors = listOf(
        AndroidColor.parseColor("#FF0000"), // 红
        AndroidColor.parseColor("#FF7F00"), // 橙
        AndroidColor.parseColor("#FFFF00"), // 黄
        AndroidColor.parseColor("#00FF00"), // 绿
        AndroidColor.parseColor("#00FFFF"), // 青
        AndroidColor.parseColor("#0000FF"), // 蓝
        AndroidColor.parseColor("#9900FF"), // 紫
        AndroidColor.parseColor("#FF99CC"), // 粉
        AndroidColor.parseColor("#FF6FCF"), // 深粉
        AndroidColor.parseColor("#FF3333"), // 深红
        AndroidColor.parseColor("#FF8800"), // 深橙
        AndroidColor.parseColor("#FFDD00"), // 金黄
        AndroidColor.parseColor("#88CC66"), // 草绿
        AndroidColor.parseColor("#00D0B1"), // 青绿
        AndroidColor.parseColor("#5AC7FA"), // 天蓝
        AndroidColor.parseColor("#4682B4"), // 钢蓝
        AndroidColor.parseColor("#9966CC"), // 紫罗兰
        AndroidColor.parseColor("#FFFFFF"), // 白
        AndroidColor.parseColor("#C8C8C8"), // 灰
        AndroidColor.parseColor("#888888")  // 深灰
    )

    // 使用系统文件选择器导出预设，不申请额外存储权限。
    internal val presetExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) writeCustomTeamPresetExport(uri)
    }

    internal val presetImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) importCustomTeamPresets(uri)
    }

    // 音频文件选择器
    internal val audioPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleAudioFile(uri, true)
            }
        }
    }

    // 视频文件选择器
    internal val videoPickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleVideoFile(uri, true)
            }
        }
    }

    internal val dancerStylePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleDancerStyleImage(uri)
            }
        }
    }

    // 工程列表启动器
    internal val projectListLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        android.util.Log.d("MainActivity", "projectListLauncher: result received, resultCode=${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) {
            val action = result.data?.getStringExtra("action")
            val projectId = result.data?.getStringExtra("project_id")
            val newProjectName = result.data?.getStringExtra("new_project_name")

            android.util.Log.d("MainActivity", "projectListLauncher: action=$action, projectId=$projectId, newProjectName=$newProjectName")

            when (action) {
                "import_project" -> {
                    // 导入工程
                    importProjectFromFile()
                }
                "share_project" -> {
                    // 分享工程
                    if (projectId != null) {
                        val project = dataManager.loadDanceGroup(projectId)
                        if (project != null) {
                            shareProject(project)
                        }
                    }
                }
                "load_with_save" -> {
                    // 保存当前工程后载入新工程
                    if (projectId != null) {
                        saveCurrentProjectSilent()
                        proceedLoadProject(projectId)
                    }
                }
                else -> {
                    // 默认行为：加载或新建工程
                    if (projectId != null) {
                        // 直接加载工程（不保存）
                        proceedLoadProject(projectId)
                    } else if (newProjectName != null) {
                        // 创建新工程（空工程，0个舞者）
                        viewModel.createNewGroup(newProjectName, 0, 16f, 12f)
                        currentProjectId = null

                        // 立即保存新工程
                        saveNewProject(newProjectName)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 设置全屏显示
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )

        setContentView(R.layout.activity_main)

        // 请求存储权限
        requestStoragePermissions()

        dataManager = DataManager(this)  // 初始化数据管理器
        audioManager = AudioManager(this)
        setupGLSurfaceView()
        setupDancerGrid()
        setupUI()
        setupGestures()
        setupTimeline()
        observeViewModel()

        // 检查是否有保存的状态需要恢复（切后台返回的情况）
        val savedProjectId = savedInstanceState?.getString("current_project_id")
            ?: getLastProjectIdFromPrefs()

        if (savedProjectId != null) {
            // 有保存的工程ID，自动恢复
            android.util.Log.d("MainActivity", "[状态恢复] 检测到保存的工程ID: $savedProjectId")
            restoreLastProject(savedProjectId)
        } else {
            // 没有保存的状态，创建空舞团
            viewModel.createNewGroup("未命名工程", 0, 16f, 12f)
        }

        // 启动自动保存定时器
        startAutoSave()

        // 检查应用更新（后台异步）
        checkForAppUpdate()

        // 调试信息：显示启动信息
        android.util.Log.d("MainActivity", "========== 应用启动 ==========")
        android.util.Log.d("MainActivity", "DataManager initialized at: ${dataManager.getFormationsDir().absolutePath}")
    }

    /**
     * 保存实例状态（处理Activity重建）
     */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // 保存当前工程ID
        currentProjectId?.let { outState.putString("current_project_id", it) }
        // 保存当前时间
        outState.putLong("current_time", viewModel.currentTime.value)
        android.util.Log.d("MainActivity", "[状态保存] onSaveInstanceState: projectId=$currentProjectId")
    }

    /**
     * 从 SharedPreferences 获取上次的工程ID
     */

    /**
     * 保存当前工程ID到 SharedPreferences
     */

    /**
     * 恢复上次的工程
     */

    /**
     * 清除保存的工程ID
     */

    /**
     * 请求存储权限
     */

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 移除Toast：权限授予后继续导出，不需要提示
            } else {
                // 只在拒绝权限时提示（重要）
                android.widget.Toast.makeText(this, "需要存储权限才能导出视频", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // 重新设置全屏模式
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    private fun checkForAppUpdate() {
        if (packageName.endsWith(".preview")) return
        lifecycleScope.launch {
            val info = AppUpdateChecker.checkForUpdate(this@MainActivity)
            if (info != null) {
                AppUpdateChecker.showUpdateDialog(this@MainActivity, info)
            }
        }
    }


    internal var isSidebarVisible = true  // 侧边栏是否可见

    /**
     * 获取侧边栏宽度（200dp + 切换按钮）
     * 新布局中时间轴在底部，返回0以使用全屏宽度计算
     */



    internal fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }


    // 悬浮菜单状态
    internal var isMenuExpanded = false


    /**
     * 导入音频
     */

    /**
     * 加载预设团体
     */

    /**
     * 导入视频
     */

    /**
     * 启动视频导入
     */

    /**
     * 显示导出配置对话框
     */

    /**
     * 导出预览视图
     */
    internal var videoExporter: com.formationtool.app.export.VideoExporter? = null
    internal var exportProgressDialog: androidx.appcompat.app.AlertDialog? = null
    internal var exportProgressBar: android.widget.ProgressBar? = null
    internal var exportProgressText: android.widget.TextView? = null

    /**
     * 开始导出视频
     */

    /**
     * 导出前保存工程
     */

    /**
     * 继续导出流程
     */

    /**
     * 显示导出进度对话框
     */

    /**
     * 关闭导出进度对话框
     */

    /**
     * 分享视频文件（带回调和提示）
     */

    // 分享视频启动器
    internal var pendingShareFile: java.io.File? = null
    internal val shareVideoLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        // 分享完成后，显示选项对话框
        val file = pendingShareFile
        if (file != null && file.exists()) {
            MaterialAlertDialogBuilder(this)
                .setTitle("分享完成")
                .setMessage("视频已分享，请选择后续操作：")
                .setPositiveButton("保存到相册") { _, _ ->
                    lifecycleScope.launch(Dispatchers.IO) {
                        val savedUri = saveVideoToGallery(file)
                        withContext(Dispatchers.Main) {
                            if (savedUri != null) {
                                // 移除Toast：系统相册会有通知
                            } else {
                                // 保留失败提示
                                android.widget.Toast.makeText(this@MainActivity, "保存到相册失败", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                .setNegativeButton("删除临时文件") { _, _ ->
                    file.delete()
                    // 移除Toast：删除操作用户已知
                }
                .setNeutralButton("留在应用") { _, _ ->
                    // 移除Toast：用户点击这个按钮就知道保留了
                }
                .show()
        }
        pendingShareFile = null
    }

    /**
     * 分享视频文件（简单版）
     */

    /**
     * 保存视频到相册
     */

    /**
     * 触发媒体扫描，确保相册能立即显示新保存的视频
     * 解决红米等部分手机相册不更新的问题
     */

    /**
     * 保存视频到应用内部存储
     */

    /**
     * 分享视频URI
     */


    /**
     * 在屏幕点查找舞者
     * 将舞者世界坐标投影到屏幕，找到离点击点最近的舞者
     */

    /**
     * 获取当前时间的插值舞者位置（与PC端一致）
     * 这是用户当前看到的位置，而不是当前帧的原始位置
     */

    /**
     * 在两个关键帧之间插值舞者位置
     */

    /**
     * 检查当前时间是否在红色过渡区（插值区域）
     */

    /**
     * 将屏幕移动转换为舞台坐标移动
     * 考虑相机角度、距离和翻转状态的影响
     */


    /**
     * 更新时间轴缩放
     */

    /**
     * 根据滚动位置更新播放时间
     */

    /**
     * 拖动时间轴时实时更新播放位置和参考视频（不更新音频位置避免卡顿）
     */

    /**
     * 同步参考视频到指定时间位置
     */

    /**
     * 滚动到指定时间
     */

    /**
     * 基于当前时间刷新渲染器状态
     * 这个方法可以在数据变化时调用，确保渲染器显示最新数据
     */





    /**
     * 显示舞台设置对话框（包含尺寸、C位线、翻转、名字、吸附等设置）
     */


    /**
     * 显示工程管理菜单
     */

    /**
     * 打开工程列表
     */


    /**
     * 复制前一个关键帧的舞者位置
     * - 如果当前在关键帧上，则复制上一个关键帧的位置到当前关键帧
     * - 如果当前不在关键帧上，则在当前时间使用智能插入创建新关键帧并复制前一帧的位置
     */







    /**
     * 媒体导入后显示工程名输入对话框
     */

    /**
     * 工程准备好后加载音频
     */

    /**
     * 处理视频文件导入
     * 自动提取视频音频到音频轨道
     */

    /**
     * 工程准备好后加载视频
     */

    /**
     * 显示删除确认对话框
     */

    /**
     * 删除参考视频和音频，恢复到导入状态
     */

    /**
     * 设置 AudioManager 的监听器
     */

    /**
     * 更新舞者编号覆盖层
     */


    /**
     * 更新关键帧标签（在波形上方对应位置显示）
     */

    /**
     * 显示调整保持时长对话框
     */

    /**
     * 显示调整变换时长对话框
     */

    /**
     * 显示删除关键帧确认对话框
     */

    // ========== 关键帧多选对话框 ==========

    /**
     * 显示关键帧多选对话框
     */

    /**
     * 关键帧列表 ViewHolder
     */
    internal class KeyframeViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val checkbox: android.widget.CheckBox = view.findViewById(R.id.checkboxKeyframe)
        val tvIndex: TextView = view.findViewById(R.id.tvKeyframeIndex)
        val tvTime: TextView = view.findViewById(R.id.tvKeyframeTime)
        val tvInfo: TextView = view.findViewById(R.id.tvKeyframeInfo)
        val tvCurrent: TextView = view.findViewById(R.id.tvCurrentIndicator)
        val btnDelete: Button = view.findViewById(R.id.btnDeleteSingleKeyframe)
    }

    override fun onPause() {
        super.onPause()
        glSurfaceView.onPause()
        audioManager.pause()
        if (hasReferenceVideo) {
            referenceVideoView.pause()
        }

        // 保存当前工程ID到SharedPreferences，以便切后台返回时恢复
        saveProjectIdToPrefs()

        // 自动保存：如果有未保存的修改且已有工程ID，静默保存
        if (viewModel.isDirty.value && currentProjectId != null) {
            val group = viewModel.currentGroup.value
            if (group != null) {
                group.referenceVideoUri = referenceVideoUri?.toString()
                group.audioUri = currentAudioUri?.toString()
                group.modifiedTime = System.currentTimeMillis()
                val success = dataManager.saveDanceGroup(group)
                if (success) {
                    viewModel.clearDirty()
                    android.util.Log.d("MainActivity", "[自动保存] 工程已自动保存")
                }
            }
        }

        android.util.Log.d("MainActivity", "[onPause] 状态已保存, projectId=$currentProjectId")
    }

    override fun onResume() {
        super.onResume()
        glSurfaceView.onResume()

        // 切屏回来后重新同步音视频位置，防止不同步
        if (hasReferenceVideo) {
            val currentTime = viewModel.currentTime.value
            // 同步视频位置到当前时间
            referenceVideoView.seekTo(currentTime.toInt())
            // 同步音频位置
            audioManager.seekTo(currentTime)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // 停止自动保存
        stopAutoSave()
        audioPlayingObserverJob?.cancel()
        audioPlayingObserverJob = null
        waveformObserverJob?.cancel()
        waveformObserverJob = null
        videoPlaybackSyncJob?.cancel()
        videoPlaybackSyncJob = null
        videoSeekSyncJob?.cancel()
        videoSeekSyncJob = null

        keyframeController?.destroy()
        audioManager.release()
        if (hasReferenceVideo) {
            referenceVideoView.stopPlayback()
        }
    }

    /**
     * 启动自动保存定时器，每30秒自动保存一次
     */

    /**
     * 停止自动保存定时器
     */

    /**
     * 执行自动保存
     */


    /**
     * 显示工程管理菜单
     */
    /**
     * 切换预览模式
     */

    /**
     * 设置预览控制条
     */

    /**
     * 显示新建工程对话框
     */

    /**
     * 显示创建新工程输入框
     */

    /**
     * 清除媒体数据并创建新工程
     */

    /**
     * 保存当前工程
     */

    /**
     * 保存新建的工程（从项目列表创建时调用）
     */

    /**
     * 保存新建的工程（媒体导入时调用）
     */

    /**
     * 加载工程
     */

    /**
     * 静默保存当前工程（不显示对话框）
     */

    /**
     * 执行加载工程操作
     */

    /**
     * 分享工程 - 从工程列表中选择
     */

    /**
     * 分享指定的工程
     */

    /**
     * 从文件导入工程
     */

    // 工程导入启动器
    internal val projectImportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleProjectImport(uri)
            }
        }
    }

    /**
     * 处理工程导入
     */
}
