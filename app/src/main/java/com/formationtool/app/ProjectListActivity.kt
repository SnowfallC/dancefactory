package com.formationtool.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.formationtool.app.data.DataManager
import com.formationtool.app.model.DanceGroup
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.SimpleDateFormat
import java.util.*

class ProjectListActivity : AppCompatActivity() {

    private lateinit var dataManager: DataManager
    private lateinit var adapter: ProjectAdapter
    private var projects = mutableListOf<DanceGroup>()
    private var currentProjectId: String? = null  // 当前工程ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_project_list)

        dataManager = DataManager(this)

        // 获取当前工程ID
        currentProjectId = intent.getStringExtra("current_project_id")
        android.util.Log.d("ProjectListActivity", "currentProjectId: $currentProjectId")

        setupToolbar()
        setupRecyclerView()
        setupButtons()
        loadProjects()
    }

    private fun setupToolbar() {
        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupRecyclerView() {
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerViewProjects)
        adapter = ProjectAdapter(
            projects = projects,
            currentProjectId = currentProjectId,
            onLoadClick = { project ->
                // 载入工程：提示是否保存当前工程
                showLoadConfirmDialog(project)
            },
            onRenameClick = { project ->
                showRenameDialog(project)
            },
            onDeleteClick = { project ->
                showDeleteDialog(project)
            }
        )
        // 使用网格布局，3列
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        recyclerView.adapter = adapter
    }

    /**
     * 显示载入确认对话框，询问是否保存当前工程
     */
    private fun showLoadConfirmDialog(project: DanceGroup) {
        MaterialAlertDialogBuilder(this)
            .setTitle("载入工程")
            .setMessage("是否保存当前工程？")
            .setPositiveButton("保存并载入") { _, _ ->
                val intent = Intent().apply {
                    putExtra("action", "load_with_save")
                    putExtra("project_id", project.id)
                }
                setResult(RESULT_OK, intent)
                finish()
            }
            .setNegativeButton("不保存") { _, _ ->
                val intent = Intent().apply {
                    putExtra("project_id", project.id)
                }
                setResult(RESULT_OK, intent)
                finish()
            }
            .setNeutralButton("取消", null)
            .show()
    }

    private fun setupButtons() {
        // 新建工程
        findViewById<Button>(R.id.btnNewProject).setOnClickListener {
            showNewProjectDialog()
        }

        // 导入工程
        findViewById<Button>(R.id.btnImportProject).setOnClickListener {
            // 返回MainActivity处理导入
            val intent = Intent().apply {
                putExtra("action", "import_project")
            }
            setResult(RESULT_OK, intent)
            finish()
        }

        // 分享工程
        findViewById<Button>(R.id.btnShareProject).setOnClickListener {
            if (projects.isEmpty()) {
                android.widget.Toast.makeText(this, "没有已保存的工程可以分享", android.widget.Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 显示工程列表供用户选择
            val projectNames = projects.map { it.name }.toTypedArray()

            MaterialAlertDialogBuilder(this)
                .setTitle("选择要分享的工程")
                .setItems(projectNames) { _, which ->
                    val selectedProject = projects[which]
                    // 返回MainActivity处理分享
                    val intent = Intent().apply {
                        putExtra("action", "share_project")
                        putExtra("project_id", selectedProject.id)
                    }
                    setResult(RESULT_OK, intent)
                    finish()
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun loadProjects() {
        projects.clear()
        val allProjects = dataManager.getAllDanceGroups()

        // 当前工程置顶
        val currentProject = allProjects.find { it.id == currentProjectId }
        val otherProjects = allProjects.filter { it.id != currentProjectId }

        if (currentProject != null) {
            projects.add(currentProject)
        }
        projects.addAll(otherProjects)

        adapter.notifyDataSetChanged()

        android.util.Log.d("ProjectListActivity", "Loaded ${projects.size} projects")
    }

    private fun showNewProjectDialog() {
        val input = EditText(this).apply {
            hint = "工程名称"
            setText("新工程")
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("新建工程")
            .setView(input)
            .setPositiveButton("创建") { _, _ ->
                val name = input.text.toString().ifEmpty { "新工程" }
                // 返回新工程名称给MainActivity
                val intent = Intent().apply {
                    putExtra("new_project_name", name)
                }
                setResult(RESULT_OK, intent)
                finish()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showRenameDialog(project: DanceGroup) {
        val input = EditText(this).apply {
            hint = "工程名称"
            setText(project.name)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("重命名工程")
            .setView(input)
            .setPositiveButton("确定") { _, _ ->
                val newName = input.text.toString()
                if (newName.isNotEmpty()) {
                    project.name = newName
                    project.modifiedTime = System.currentTimeMillis()
                    dataManager.saveDanceGroup(project)
                    loadProjects()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showDeleteDialog(project: DanceGroup) {
        // 不能删除当前工程
        if (project.id == currentProjectId) {
            android.widget.Toast.makeText(this, "不能删除当前正在编辑的工程", android.widget.Toast.LENGTH_SHORT).show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("删除工程")
            .setMessage("确定要删除工程 \"${project.name}\" 吗？此操作无法撤销。")
            .setPositiveButton("删除") { _, _ ->
                dataManager.deleteDanceGroup(project.id)
                loadProjects()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    class ProjectAdapter(
        private val projects: List<DanceGroup>,
        private val currentProjectId: String?,
        private val onLoadClick: (DanceGroup) -> Unit,
        private val onRenameClick: (DanceGroup) -> Unit,
        private val onDeleteClick: (DanceGroup) -> Unit
    ) : RecyclerView.Adapter<ProjectAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cardProject: MaterialCardView = view.findViewById(R.id.cardProject)
            val tvProjectName: TextView = view.findViewById(R.id.tvProjectName)
            val tvCurrentTag: TextView = view.findViewById(R.id.tvCurrentTag)
            val tvDancerCount: TextView = view.findViewById(R.id.tvDancerCount)
            val tvKeyframeCount: TextView = view.findViewById(R.id.tvKeyframeCount)
            val tvModifiedTime: TextView = view.findViewById(R.id.tvModifiedTime)
            val btnLoad: Button = view.findViewById(R.id.btnLoad)
            val btnRename: ImageButton = view.findViewById(R.id.btnRename)
            val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_project, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val project = projects[position]
            val isCurrentProject = project.id == currentProjectId

            holder.tvProjectName.text = project.name
            holder.tvDancerCount.text = "${project.dancerCount}个舞者"
            holder.tvKeyframeCount.text = "${project.formations.size}个关键帧"

            val dateFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
            holder.tvModifiedTime.text = "保存: ${dateFormat.format(Date(project.modifiedTime))}"

            // 当前工程标记
            if (isCurrentProject) {
                holder.tvCurrentTag.visibility = View.VISIBLE
                holder.cardProject.setCardBackgroundColor(0xFF3A3A4F.toInt())  // 灰色背景
                holder.btnLoad.isEnabled = false
                holder.btnLoad.alpha = 0.5f
                holder.btnDelete.isEnabled = false
                holder.btnDelete.alpha = 0.5f
            } else {
                holder.tvCurrentTag.visibility = View.GONE
                holder.cardProject.setCardBackgroundColor(0xFF2C2C3E.toInt())  // 正常背景
                holder.btnLoad.isEnabled = true
                holder.btnLoad.alpha = 1.0f
                holder.btnDelete.isEnabled = true
                holder.btnDelete.alpha = 1.0f
            }

            // 载入按钮
            holder.btnLoad.setOnClickListener {
                if (!isCurrentProject) {
                    onLoadClick(project)
                }
            }

            holder.btnRename.setOnClickListener {
                onRenameClick(project)
            }

            holder.btnDelete.setOnClickListener {
                if (!isCurrentProject) {
                    onDeleteClick(project)
                }
            }
        }

        override fun getItemCount() = projects.size
    }
}
