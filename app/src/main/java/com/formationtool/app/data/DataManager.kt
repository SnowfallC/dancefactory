package com.formationtool.app.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.formationtool.app.model.DanceGroup
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 数据管理器 - 负责数据的保存、加载、导入和导出
 */
class DataManager(private val context: Context) {

    companion object {
        private const val MAX_ARCHIVE_ENTRIES = 256
        private const val MAX_PROJECT_JSON_BYTES = 2L * 1024 * 1024
        private const val MAX_STYLE_BYTES = 20L * 1024 * 1024
        private const val MAX_MEDIA_BYTES = 2L * 1024 * 1024 * 1024
        private const val MAX_ARCHIVE_BYTES = 3L * 1024 * 1024 * 1024
        private const val MAX_FORMATIONS = 10_000
        private const val MAX_DANCERS_PER_FORMATION = 2_000
        private val SAFE_ID = Regex("^[A-Za-z0-9_-]{1,128}$")
        private val AUDIO_EXTENSIONS = setOf("mp3", "wav", "m4a", "aac", "ogg", "flac")
        private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "mkv")
    }

    private val gson: Gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()  // 禁用HTML转义，避免中文被转义
        .create()

    private val dataDir = File(context.filesDir, "formations")
    private val audioDir = File(context.filesDir, "project_audio")
    private val videoDir = File(context.filesDir, "project_video")
    private val dancerStyleRootDir = File(context.filesDir, "project_dancer_styles")

    init {
        if (!dataDir.exists()) {
            dataDir.mkdirs()
        }
        if (!audioDir.exists()) {
            audioDir.mkdirs()
        }
        if (!videoDir.exists()) {
            videoDir.mkdirs()
        }
        if (!dancerStyleRootDir.exists()) {
            dancerStyleRootDir.mkdirs()
        }
    }

    /**
     * 获取数据目录
     */
    fun getFormationsDir(): File {
        return dataDir
    }

    /**
     *
     * @param uri Content URI
     * @param destDir 目标目录
     *
     * @return 内部存储文件路径，失败返回null
     */
    private fun copyUriToInternalStorage(uri: Uri, destDir: File, filePrefix: String): String? {
        return try {
            // 获取原始文件名
            val fileName = getFileNameFromUri(uri) ?: "file"
            val extension = getFileExtension(fileName)

            // 生成唯一文件名
            val timestamp = System.currentTimeMillis()
            val destFileName = if (extension.isNotEmpty()) {
                "${filePrefix}_${timestamp}.$extension"
            } else {
                "${filePrefix}_${timestamp}"
            }

            val destFile = File(destDir, destFileName)

            // 复制文件到内部存储
            (context.contentResolver.openInputStream(uri) ?: throw IOException("无法读取媒体文件")).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            android.util.Log.d("DataManager", "Copied file from $uri to ${destFile.absolutePath}, size=${destFile.length()} bytes")
            destFile.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("DataManager", "Error copying file from $uri", e)
            e.printStackTrace()
            null
        }
    }

    private fun getDancerStyleDir(groupId: String): File {
        val dir = File(dancerStyleRootDir, groupId)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun getDancerStyleFile(groupId: String, dancerId: String): File {
        return File(getDancerStyleDir(groupId), "${dancerId}.png")
    }

    fun saveDancerStyleBitmap(groupId: String, dancerId: String, bitmap: Bitmap): String? {
        return try {
            val outputFile = getDancerStyleFile(groupId, dancerId)
            FileOutputStream(outputFile).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            outputFile.absolutePath
        } catch (e: Exception) {
            android.util.Log.e("DataManager", "saveDancerStyleBitmap failed: group=$groupId dancer=$dancerId", e)
            null
        }
    }

    fun deleteDancerStyle(groupId: String, dancerId: String): Boolean {
        return try {
            val file = getDancerStyleFile(groupId, dancerId)
            !file.exists() || file.delete()
        } catch (e: Exception) {
            android.util.Log.e("DataManager", "deleteDancerStyle failed: group=$groupId dancer=$dancerId", e)
            false
        }
    }

    /**
     * 保存舞团数据到JSON文件
     *
     */
    fun saveDanceGroup(group: DanceGroup): Boolean {
        return try {
            android.util.Log.d("DataManager", "saveDanceGroup: start saving group '${group.name}'")
            android.util.Log.d("DataManager", "saveDanceGroup: id=${group.id}")
            android.util.Log.d("DataManager", "saveDanceGroup: dancers=${group.dancerCount}, formations=${group.formations.size}")
            android.util.Log.d("DataManager", "saveDanceGroup: audioUri=${group.audioUri}")
            android.util.Log.d("DataManager", "saveDanceGroup: videoUri=${group.referenceVideoUri}")

            // 处理音频文件：复制到内部存储
            if (group.audioUri != null && group.audioUri!!.isNotEmpty()) {
                try {
                    val audioUri = Uri.parse(group.audioUri)
                    // 检查音频是否已复制到内部存储
                    if (shouldCopyMedia(group.audioUri, group.audioPath, group.audioSourceUri)) {
                        android.util.Log.d("DataManager", "Copying audio file to internal storage...")
                        val internalPath = copyUriToInternalStorage(audioUri, audioDir, "audio_${group.id}")
                        if (internalPath != null) {
                            group.audioPath = internalPath
                            group.audioSourceUri = group.audioUri
                            android.util.Log.d("DataManager", "Audio saved to: $internalPath")
                        } else {
                            throw IOException("音频保存失败，请重新选择文件")
                        }
                    } else {
                        android.util.Log.d("DataManager", "Audio already exists at: ${group.audioPath}")
                    }
                } catch (e: Exception) {
                    throw IOException("无法保存音频", e)
                }
            }

            // 处理视频文件：复制到内部存储
            if (group.referenceVideoUri != null && group.referenceVideoUri!!.isNotEmpty()) {
                try {
                    val videoUri = Uri.parse(group.referenceVideoUri)
                    // 检查视频是否已复制到内部存储
                    if (shouldCopyMedia(group.referenceVideoUri, group.referenceVideoPath, group.videoSourceUri)) {
                        android.util.Log.d("DataManager", "Copying video file to internal storage...")
                        val internalPath = copyUriToInternalStorage(videoUri, videoDir, "video_${group.id}")
                        if (internalPath != null) {
                            group.referenceVideoPath = internalPath
                            group.videoSourceUri = group.referenceVideoUri
                            android.util.Log.d("DataManager", "Video saved to: $internalPath")
                        } else {
                            throw IOException("视频保存失败，请重新选择文件")
                        }
                    } else {
                        android.util.Log.d("DataManager", "Video already exists at: ${group.referenceVideoPath}")
                    }
                } catch (e: Exception) {
                    throw IOException("无法保存视频", e)
                }
            }

            // 检查formations是否有数据
            if (group.formations.isNotEmpty()) {
                val firstFormation = group.formations[0]
                android.util.Log.d("DataManager", "saveDanceGroup: first formation has ${firstFormation.dancers.size} dancers")
                if (firstFormation.dancers.isNotEmpty()) {
                    val firstDancer = firstFormation.dancers[0]
                    android.util.Log.d("DataManager", "saveDanceGroup: first dancer: ${firstDancer.name} at (${firstDancer.x}, ${firstDancer.y}, ${firstDancer.z})")
                }
            } else {
                android.util.Log.w("DataManager", "saveDanceGroup: WARNING - formations list is EMPTY!")
            }

            val file = File(dataDir, "${group.id}.json")
            val json = gson.toJson(group)
            android.util.Log.d("DataManager", "saveDanceGroup: JSON length=${json.length} characters")

            // 写入JSON文件
            file.writeText(json, Charsets.UTF_8)
            android.util.Log.d("DataManager", "Saved group ${group.name} to ${file.absolutePath}, size: ${file.length()} bytes")
            android.util.Log.d("DataManager", "Group data: dancers=${group.dancerCount}, formations=${group.formations.size}")
            true
        } catch (e: Exception) {
            android.util.Log.e("DataManager", "Error saving group ${group.name}", e)
            e.printStackTrace()
            false
        }
    }

    /**
     * 加载舞团数据
     */
    fun loadDanceGroup(groupId: String): DanceGroup? {
        return try {
            val file = File(dataDir, "$groupId.json")
            android.util.Log.d("DataManager", "loadDanceGroup: trying to load ${file.absolutePath}")

            if (file.exists()) {
                android.util.Log.d("DataManager", "loadDanceGroup: file exists, size=${file.length()} bytes")
                // 读取JSON字符串
                val json = file.readText(Charsets.UTF_8)
                android.util.Log.d("DataManager", "loadDanceGroup: JSON length=${json.length} characters")

                // 打印JSON预览（调试用）
                val preview = if (json.length > 200) json.substring(0, 200) + "..." else json
                android.util.Log.d("DataManager", "loadDanceGroup: JSON preview: $preview")

                val group = gson.fromJson(json, DanceGroup::class.java)
                android.util.Log.d("DataManager", "Loaded group ${group.name}, dancers=${group.dancerCount}, formations=${group.formations.size}")

                if (group.formations.isNotEmpty()) {
                    android.util.Log.d("DataManager", "loadDanceGroup: first formation has ${group.formations[0].dancers.size} dancers")
                } else {
                    android.util.Log.w("DataManager", "loadDanceGroup: WARNING - loaded group has NO formations!")
                }

                group
            } else {
                android.util.Log.w("DataManager", "File not found: ${file.absolutePath}")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("DataManager", "Error loading group $groupId", e)
            e.printStackTrace()
            null
        }
    }

    /**
     * 获取所有舞团列表
     */
    fun getAllDanceGroups(): List<DanceGroup> {
        val groups = mutableListOf<DanceGroup>()
        android.util.Log.d("DataManager", "Loading all groups from: ${dataDir.absolutePath}")
        dataDir.listFiles()?.forEach { file ->
            if (file.extension == "json") {
                try {
                    // 解析JSON为舞团对象
                    val json = file.readText(Charsets.UTF_8)
                    val group = gson.fromJson(json, DanceGroup::class.java)
                    groups.add(group)
                    android.util.Log.d("DataManager", "Found group: ${group.name}")
                } catch (e: Exception) {
                    android.util.Log.e("DataManager", "Error parsing ${file.name}", e)
                    e.printStackTrace()
                }
            }
        }
        android.util.Log.d("DataManager", "Total groups loaded: ${groups.size}")
        return groups.sortedByDescending { it.modifiedTime }
    }

    /**
     * 删除舞团数据
     */
    fun deleteDanceGroup(groupId: String): Boolean {
        return try {
            val file = File(dataDir, "$groupId.json")
            file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 导出舞团为JSON文件
     */
    fun exportDanceGroup(group: DanceGroup, outputFile: File): Boolean {
        return try {
            val json = gson.toJson(group)
            // 使用UTF-8编码写入
            FileOutputStream(outputFile).use { output ->
                output.write(json.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 从文件导入舞团
     */
    fun importDanceGroup(inputFile: File): DanceGroup? {
        return try {
            FileInputStream(inputFile).use { input ->
                val json = input.readBytes().toString(Charsets.UTF_8)
                val group = gson.fromJson(json, DanceGroup::class.java)

                // 保存导入的数据
                saveDanceGroup(group)
                group
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 导出为脚本格式（人类可读）
     */
    fun exportToScript(group: DanceGroup, outputFile: File): Boolean {
        return try {
            val script = buildString {
                appendLine("# ${group.name}")
                appendLine("# 舞台尺寸: ${group.stageWidth}m x ${group.stageDepth}m")
                appendLine("# 舞者数量: ${group.dancerCount}")
                appendLine()

                group.formations.forEachIndexed { index, formation ->
                    appendLine("## 队形 ${index + 1}: ${formation.name}")
                    appendLine("时间: ${formation.timestamp}ms")
                    appendLine("过渡时长: ${formation.duration}ms")

                    if (formation.notes.isNotEmpty()) {
                        appendLine("注释: ${formation.notes}")
                    }

                    appendLine("队形详情：")
                    formation.dancers.forEach { dancer ->
                        appendLine("  ${dancer.name}: (${dancer.x}, ${dancer.y}, ${dancer.z})")
                    }

                    if (formation.props.isNotEmpty()) {
                        appendLine("道具:")
                        formation.props.forEach { prop ->
                            appendLine("  ${prop.name}: (${prop.x}, ${prop.y})")
                        }
                    }
                    appendLine()
                }
            }

            // 写入文件
            outputFile.writeText(script, Charsets.UTF_8)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 导出为CSV文件
     */
    fun exportToCSV(group: DanceGroup, outputFile: File): Boolean {
        return try {
            val csv = buildString {
                appendLine("队形索引, 时间(ms), 舞者ID, 舞者名称, X坐标, Y坐标, Z坐标, 颜色")

                group.formations.forEach { formation ->
                    formation.dancers.forEachIndexed { index, dancer ->
                        appendLine("${formation.name},${formation.timestamp},${index + 1},${dancer.name},${dancer.x},${dancer.y},${dancer.z},${dancer.color}")
                    }
                }
            }

            // 写入带BOM的UTF-8编码
            outputFile.outputStream().use { output ->
                // 写入UTF-8 BOM
                output.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                output.write(csv.toByteArray(Charsets.UTF_8))
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 导出工程为ZIP包
     * @param group 工程数据
     * @param audioUri 音频URI（可选）
     * @param videoUri 视频URI（可选）
     * @param outputFile 输出ZIP文件
     * @return 是否成功
     */
    fun exportProjectAsZip(
        group: DanceGroup,
        audioUri: Uri?,
        videoUri: Uri?,
        outputFile: File
    ): Boolean {
        return try {
            android.util.Log.d("DataManager", "Starting export to ${outputFile.absolutePath}")
            android.util.Log.d("DataManager", "Audio URI: $audioUri")
            android.util.Log.d("DataManager", "Video URI: $videoUri")

            ZipOutputStream(FileOutputStream(outputFile)).use { zipOut ->
                // 1. 写入JSON数据
                val json = gson.toJson(group)
                val jsonEntry = ZipEntry("project.json")
                zipOut.putNextEntry(jsonEntry)
                zipOut.write(json.toByteArray(Charsets.UTF_8))
                zipOut.closeEntry()
                android.util.Log.d("DataManager", "Added project.json to zip")

                // 2. 写入音频文件（如果有）
                if (audioUri != null) {
                    try {
                        android.util.Log.d("DataManager", "Attempting to add audio from URI: $audioUri")
                        context.contentResolver.openInputStream(audioUri)?.use { inputStream ->
                            // 获取音频文件名和扩展名
                            val fileName = getFileNameFromUri(audioUri) ?: "audio"
                            val extension = getFileExtension(fileName)
                            val audioFileName = if (extension.isNotEmpty()) {
                                "audio.$extension"
                            } else {
                                "audio.mp3"
                            }

                            val audioEntry = ZipEntry(audioFileName)
                            zipOut.putNextEntry(audioEntry)
                            val bytesCopied = inputStream.copyTo(zipOut)
                            zipOut.closeEntry()
                            android.util.Log.d("DataManager", "Added $audioFileName to zip, size: $bytesCopied bytes")
                        } ?: run {
                            android.util.Log.w("DataManager", "Failed to open audio input stream")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("DataManager", "Error adding audio to zip", e)
                        e.printStackTrace()
                        // 忽略音频导入错误，继续导出
                    }
                }

                // 3. 写入视频文件（如果有）
                if (videoUri != null) {
                    try {
                        android.util.Log.d("DataManager", "Attempting to add video from URI: $videoUri")
                        context.contentResolver.openInputStream(videoUri)?.use { inputStream ->
                            // 获取视频文件名和扩展名
                            val fileName = getFileNameFromUri(videoUri) ?: "video"
                            val extension = getFileExtension(fileName)
                            val videoFileName = if (extension.isNotEmpty()) {
                                "video.$extension"
                            } else {
                                "video.mp4"
                            }

                            val videoEntry = ZipEntry(videoFileName)
                            zipOut.putNextEntry(videoEntry)
                            val bytesCopied = inputStream.copyTo(zipOut)
                            zipOut.closeEntry()
                            android.util.Log.d("DataManager", "Added $videoFileName to zip, size: $bytesCopied bytes")
                        } ?: run {
                            android.util.Log.w("DataManager", "Failed to open video input stream")
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("DataManager", "Error adding video to zip", e)
                        e.printStackTrace()
                        // 忽略视频导入错误，继续导出
                    }
                }

                // 4. 导出舞者样式图片
                val stylePathByDancerId = mutableMapOf<String, String>()
                group.formations.forEach { formation ->
                    formation.dancers.forEach { dancer ->
                        val path = dancer.customStylePath
                        if (!path.isNullOrEmpty() && !stylePathByDancerId.containsKey(dancer.id)) {
                            stylePathByDancerId[dancer.id] = path
                        }
                    }
                }

                stylePathByDancerId.forEach { (dancerId, stylePath) ->
                    try {
                        val styleFile = File(stylePath)
                        if (!styleFile.exists()) {
                            android.util.Log.w("DataManager", "Style file missing, skip export: $stylePath")
                            return@forEach
                        }
                        val entryName = "dancer_styles/$dancerId.png"
                        val styleEntry = ZipEntry(entryName)
                        zipOut.putNextEntry(styleEntry)
                        FileInputStream(styleFile).use { input ->
                            input.copyTo(zipOut)
                        }
                        zipOut.closeEntry()
                    } catch (e: Exception) {
                        android.util.Log.e("DataManager", "Error adding dancer style to zip: dancerId=$dancerId", e)
                    }
                }
            }
            android.util.Log.d("DataManager", "Export completed successfully, file size: ${outputFile.length()} bytes")
            true
        } catch (e: Exception) {
            android.util.Log.e("DataManager", "Error exporting project to zip", e)
            e.printStackTrace()
            false
        }
    }

    /**
     * 从ZIP包导入工程
     * @param inputFile ZIP文件
     *
     */
    fun importProjectFromZip(inputFile: File): Triple<DanceGroup?, File?, File?> {
        var group: DanceGroup? = null
        var audioFile: File? = null
        var videoFile: File? = null
        val importedStyles = mutableMapOf<String, File>()

        val createdFiles = mutableListOf<File>()

        try {
            ZipInputStream(FileInputStream(inputFile)).use { zipIn ->
                var entry = zipIn.nextEntry
                var entryCount = 0
                var totalBytes = 0L
                var projectSeen = false
                var audioSeen = false
                var videoSeen = false

                while (entry != null) {
                    entryCount++
                    if (entryCount > MAX_ARCHIVE_ENTRIES) {
                        throw IOException("工程包条目过多")
                    }
                    if (entry.isDirectory) {
                        zipIn.closeEntry()
                        entry = zipIn.nextEntry
                        continue
                    }

                    when {
                        entry.name == "project.json" -> {
                            if (projectSeen) throw IOException("工程包包含重复的 project.json")
                            projectSeen = true
                            val jsonOut = ByteArrayOutputStream()
                            val bytes = copyZipEntryLimited(zipIn, jsonOut, MAX_PROJECT_JSON_BYTES, totalBytes)
                            totalBytes += bytes
                            val json = jsonOut.toString(Charsets.UTF_8.name())
                            group = gson.fromJson(json, DanceGroup::class.java)
                            validateImportedGroup(group)
                        }
                        isAllowedMediaEntry(entry.name, "audio", AUDIO_EXTENSIONS) -> {
                            if (audioSeen) throw IOException("工程包包含多个音频文件")
                            audioSeen = true
                            val audioDir = File(context.filesDir, "imported_audio")
                            if (!audioDir.exists()) {
                                audioDir.mkdirs()
                            }
                            val timestamp = System.currentTimeMillis()
                            val extension = getFileExtension(entry.name)
                            val fileName = "audio_${timestamp}.$extension"
                            audioFile = File(audioDir, fileName)
                            createdFiles += audioFile!!
                            FileOutputStream(audioFile).use { output ->
                                val bytes = copyZipEntryLimited(zipIn, output, MAX_MEDIA_BYTES, totalBytes)
                                totalBytes += bytes
                            }
                        }
                        isAllowedMediaEntry(entry.name, "video", VIDEO_EXTENSIONS) -> {
                            if (videoSeen) throw IOException("工程包包含多个视频文件")
                            videoSeen = true
                            val videoDir = File(context.filesDir, "imported_video")
                            if (!videoDir.exists()) {
                                videoDir.mkdirs()
                            }
                            val timestamp = System.currentTimeMillis()
                            val extension = getFileExtension(entry.name)
                            val fileName = "video_${timestamp}.$extension"
                            videoFile = File(videoDir, fileName)
                            createdFiles += videoFile!!
                            FileOutputStream(videoFile).use { output ->
                                val bytes = copyZipEntryLimited(zipIn, output, MAX_MEDIA_BYTES, totalBytes)
                                totalBytes += bytes
                            }
                        }
                        isAllowedStyleEntry(entry.name) -> {
                            val styleFileName = entry.name.substringAfterLast('/')
                            val dancerId = styleFileName.substringBeforeLast('.')
                            val importedStyleDir = File(context.filesDir, "imported_dancer_styles")
                            if (!importedStyleDir.exists()) {
                                importedStyleDir.mkdirs()
                            }
                            val tempStyleFile = File(importedStyleDir, "${dancerId}_${System.currentTimeMillis()}.png")
                            createdFiles += tempStyleFile
                            FileOutputStream(tempStyleFile).use { output ->
                                val bytes = copyZipEntryLimited(zipIn, output, MAX_STYLE_BYTES, totalBytes)
                                totalBytes += bytes
                            }
                            importedStyles[dancerId] = tempStyleFile
                        }
                        else -> {
                            throw IOException("工程包包含不支持的条目: ${entry.name}")
                        }
                    }
                    if (totalBytes > MAX_ARCHIVE_BYTES) throw IOException("工程包解压后过大")
                    zipIn.closeEntry()
                    entry = zipIn.nextEntry
                }

                if (!projectSeen || group == null) throw IOException("工程包缺少有效的 project.json")
            }

            group?.let {
                if (importedStyles.isNotEmpty()) {
                    val targetDir = getDancerStyleDir(it.id)
                    importedStyles.forEach { (dancerId, tempFile) ->
                        if (tempFile.exists()) {
                            val targetFile = File(targetDir, "$dancerId.png")
                            tempFile.copyTo(targetFile, overwrite = true)
                        }
                    }
                    it.formations.forEach { formation ->
                        formation.dancers.forEach { dancer ->
                            val targetFile = File(targetDir, "${dancer.id}.png")
                            if (targetFile.exists()) {
                                dancer.customStylePath = targetFile.absolutePath
                            }
                        }
                    }
                }
                saveDanceGroup(it)
            }

        } catch (e: Exception) {
            android.util.Log.e("DataManager", "Error importing project from zip", e)
            createdFiles.forEach { runCatching { if (it.exists()) it.delete() } }
            group = null
            audioFile = null
            videoFile = null
        }

        return Triple(group, audioFile, videoFile)
    }

    private fun isAllowedMediaEntry(name: String, prefix: String, extensions: Set<String>): Boolean {
        if (name.contains('/') || name.contains('\\')) return false
        val expectedPrefix = "$prefix."
        if (!name.startsWith(expectedPrefix)) return false
        return getFileExtension(name) in extensions
    }

    private fun isAllowedStyleEntry(name: String): Boolean {
        if (!name.startsWith("dancer_styles/") || !name.endsWith(".png")) return false
        val relative = name.removePrefix("dancer_styles/")
        if (relative.contains('/') || relative.contains('\\')) return false
        return SAFE_ID.matches(relative.removeSuffix(".png"))
    }

    private fun copyZipEntryLimited(
        zipIn: ZipInputStream,
        output: OutputStream,
        entryLimit: Long,
        currentTotal: Long
    ): Long {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copied = 0L
        while (true) {
            val count = zipIn.read(buffer)
            if (count < 0) break
            copied += count
            if (copied > entryLimit || currentTotal + copied > MAX_ARCHIVE_BYTES) {
                throw IOException("工程包解压大小超过限制")
            }
            output.write(buffer, 0, count)
        }
        return copied
    }

    private fun validateImportedGroup(group: DanceGroup?) {
        if (group == null || !SAFE_ID.matches(group.id)) throw IOException("工程 ID 无效")
        if (group.name.length > 200) throw IOException("工程名称过长")
        if (!group.stageWidth.isFinite() || !group.stageDepth.isFinite() ||
            group.stageWidth <= 0f || group.stageDepth <= 0f) {
            throw IOException("舞台尺寸无效")
        }
        if (group.formations.size > MAX_FORMATIONS) throw IOException("队形数量超过限制")
        group.formations.forEach { formation ->
            if (!SAFE_ID.matches(formation.id) || formation.dancers.size > MAX_DANCERS_PER_FORMATION) {
                throw IOException("队形数据无效")
            }
            formation.dancers.forEach { dancer ->
                if (!SAFE_ID.matches(dancer.id)) throw IOException("舞者 ID 无效")
            }
        }
    }

    /**
     * 从URI获取文件名
     */
    private fun getFileNameFromUri(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 获取文件扩展名
     */
    private fun getFileExtension(fileName: String): String {
        val lastDot = fileName.lastIndexOf('.')
        return if (lastDot > 0 && lastDot < fileName.length - 1) {
            fileName.substring(lastDot + 1).lowercase()
        } else {
            ""
        }
    }
}
