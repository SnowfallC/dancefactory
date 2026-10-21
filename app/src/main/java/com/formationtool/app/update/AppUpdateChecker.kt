package com.formationtool.app.update

import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

object AppUpdateChecker {

    private const val MANIFEST_URL =
        "https://pub-02ac16656b17473f97b1b838e1bc546d.r2.dev/dancefactory/updates/latest.json"
    private const val OFFICIAL_DOWNLOAD_URL = "https://dfworkshop.top/"

    data class UpdateInfo(
        val latestVersion: String,
        val downloadUrl: String,
        val fileName: String,
        val sha256: String,
        val size: Long,
        val notes: String,
        val mandatory: Boolean
    )

    suspend fun checkForUpdate(context: Activity, showFailure: Boolean = false): UpdateInfo? {
        return try {
            withContext(Dispatchers.IO) {
                val currentVersion = try {
                    context.packageManager
                        .getPackageInfo(context.packageName, 0).versionName
                        ?: return@withContext null
                } catch (_: Exception) { return@withContext null }

                val conn = URL(MANIFEST_URL).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Cache-Control", "no-cache")

                if (conn.responseCode != 200) return@withContext null

                val json = conn.inputStream.bufferedReader().use(BufferedReader::readText)
                conn.disconnect()

                val root = UpdateManifestVerifier.verifyAndParse(json)
                val remoteVersion = root.get("version").asString
                val mandatory = root.has("mandatory") && root.get("mandatory").asBoolean

                val androidObj = root.getAsJsonObject("artifacts")
                    .getAsJsonObject("android")

                val notesBuilder = StringBuilder()
                if (root.has("notes")) {
                    for (e in root.getAsJsonArray("notes")) {
                        notesBuilder.append("• ").append(e.asString).append("\n")
                    }
                }

                if (compareVersions(remoteVersion, currentVersion) > 0) {
                    UpdateInfo(
                        latestVersion = remoteVersion,
                        downloadUrl = androidObj.get("downloadUrl").asString,
                        fileName = androidObj.get("fileName").asString,
                        sha256 = androidObj.get("sha256").asString,
                        size = androidObj.get("size").asLong,
                        notes = notesBuilder.toString().trim(),
                        mandatory = mandatory
                    )
                } else null
            }
        } catch (_: Exception) {
            if (showFailure) {
                withContext(Dispatchers.Main) {
                    showOfficialDownloadDialog(context, "无法完成更新检查。")
                }
            }
            null
        }
    }

    fun showUpdateDialog(activity: Activity, info: UpdateInfo) {
        val message = buildString {
            append("发现新版本: ${info.latestVersion}\n\n")
            if (info.notes.isNotBlank()) {
                append("更新内容:\n${info.notes}\n\n")
            }
            val sizeMb = String.format("%.1f", info.size / 1024.0 / 1024.0)
            append("安装包大小: ${sizeMb} MB")
        }

        val builder = AlertDialog.Builder(activity)
            .setTitle("应用更新")
            .setMessage(message)
            .setCancelable(!info.mandatory)

        if (info.mandatory) {
            builder.setPositiveButton("立即更新") { _, _ ->
                downloadAndInstallApk(activity, info)
            }
        } else {
            builder.setPositiveButton("立即更新") { _, _ ->
                downloadAndInstallApk(activity, info)
            }
            builder.setNegativeButton("稍后提醒") { dialog, _ ->
                dialog.dismiss()
            }
        }

        builder.show()
    }

    private fun downloadAndInstallApk(activity: Activity, info: UpdateInfo) {
        val downloadUri = Uri.parse(info.downloadUrl)
        if (downloadUri.scheme != "https" || info.size <= 0 || info.size > 2L * 1024 * 1024 * 1024) {
            showOfficialDownloadDialog(activity, "更新包信息无效，无法继续自动更新。")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!activity.packageManager.canRequestPackageInstalls()) {
                AlertDialog.Builder(activity)
                    .setTitle("允许安装更新")
                    .setMessage("请在系统设置中允许舞形工坊安装应用，返回后再次点击“立即更新”。")
                    .setPositiveButton("前往授权") { _, _ ->
                        try {
                            activity.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${activity.packageName}")))
                        } catch (_: Exception) {
                            showOfficialDownloadDialog(activity, "无法打开安装授权设置，请在系统设置中允许本应用安装未知应用。")
                        }
                    }
                    .setNegativeButton("取消", null)
                    .show()
                return
            }
        }

        val downloadDir = File(activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates")
        downloadDir.mkdirs()
        val apkFile = File(downloadDir, info.fileName)
        if (apkFile.exists()) apkFile.delete()

        val request = DownloadManager.Request(downloadUri).apply {
            setTitle("舞形工坊更新 ${info.latestVersion}")
            setDescription("正在下载安装包...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationUri(Uri.fromFile(apkFile))
        }

        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = try {
            dm.enqueue(request)
        } catch (_: Exception) {
            showOfficialDownloadDialog(activity, "更新包下载无法启动。")
            return
        }

        Toast.makeText(activity, "开始下载更新...", Toast.LENGTH_SHORT).show()

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1) != downloadId) return
                activity.unregisterReceiver(this)

                val downloadSucceeded = dm.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
                    if (!cursor.moveToFirst()) false else {
                        val statusIndex = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                        statusIndex >= 0 && cursor.getInt(statusIndex) == DownloadManager.STATUS_SUCCESSFUL
                    }
                } ?: false

                val valid = try {
                    downloadSucceeded && apkFile.isFile && apkFile.length() == info.size &&
                        sha256(apkFile).equals(info.sha256, ignoreCase = true)
                } catch (_: Exception) {
                    false
                }
                if (!valid) {
                    if (apkFile.exists()) apkFile.delete()
                    showOfficialDownloadDialog(activity, "安装包下载失败或校验不通过。")
                    return
                }

                try {
                    installApk(activity, apkFile)
                } catch (_: Exception) {
                    showOfficialDownloadDialog(activity, "无法启动安装程序。")
                }
            }
        }

        val filter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        ContextCompat.registerReceiver(
            activity,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun installApk(activity: Activity, apkFile: File) {
        val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                apkFile
            )
        } else {
            Uri.fromFile(apkFile)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
    }

    private fun showOfficialDownloadDialog(activity: Activity, reason: String) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle("更新失败")
            .setMessage("$reason\n\n请前往官网重新下载安装最新版本。")
            .setPositiveButton("前往官网重新下载") { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OFFICIAL_DOWNLOAD_URL)))
                } catch (_: Exception) {
                    Toast.makeText(activity, "请在浏览器中打开：$OFFICIAL_DOWNLOAD_URL", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buf = ByteArray(8192)
            var n: Int
            while (fis.read(buf).also { n = it } != -1) {
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02X".format(it) }
    }

    private fun compareVersions(a: String, b: String): Int {
        val pa = a.split(".")
        val pb = b.split(".")
        val len = maxOf(pa.size, pb.size)
        for (i in 0 until len) {
            val va = if (i < pa.size) pa[i].toIntOrNull() ?: 0 else 0
            val vb = if (i < pb.size) pb[i].toIntOrNull() ?: 0 else 0
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }
}
