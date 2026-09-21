package com.omarea.utils


import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.omarea.common.ui.DialogHelper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URL


class Update {
    companion object {
        // 使用本仓库的 GitHub Releases 作为更新源
        private const val UPDATE_CHECK_URL = "https://api.github.com/repos/fannndi/Scene-Re/releases/latest"
        private const val FALLBACK_DOWNLOAD_PREFIX = "https://vtools.oss-cn-beijing.aliyuncs.com/app-release"
    }

    private fun currentVersionCode(context: Context): Int {
        val manager = context.packageManager
        var code = 0
        try {
            val info = manager.getPackageInfo(context.packageName, 0)
            code = PackageInfoCompat.getLongVersionCode(info).toInt()
        } catch (e: PackageManager.NameNotFoundException) {
            e.printStackTrace()
        }

        return code
    }

    // 从 tag_name（如 "r1799"）中解析版本号
    private fun parseVersionCode(tagName: String?): Int {
        if (tagName == null) {
            return 0
        }
        return Regex("(\\d+)").find(tagName)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    fun checkUpdate(context: Context) {
        val handler = Handler(Looper.getMainLooper());
        Thread(Runnable {
            var bufferedReader: BufferedReader? = null
            try {
                val url = URL(UPDATE_CHECK_URL)
                val connection = url.openConnection()
                connection.connectTimeout = 15000
                connection.readTimeout = 60000
                if (connection is java.net.HttpURLConnection) {
                    connection.setRequestProperty("Accept", "application/vnd.github+json")
                }
                bufferedReader = BufferedReader(InputStreamReader(connection.getInputStream()))
                val stringBuilder = StringBuilder()
                while (true) {
                    val line = bufferedReader.readLine()
                    if (line != null) {
                        stringBuilder.append(line)
                        stringBuilder.append("\n")
                    } else {
                        break
                    }
                }
                val jsonObject = JSONObject(stringBuilder.toString().trim { it <= ' ' })

                val latestVersionCode = if (jsonObject.has("versionCode")) {
                    jsonObject.getInt("versionCode")
                } else {
                    parseVersionCode(jsonObject.optString("tag_name"))
                }

                if (latestVersionCode > 0 && currentVersionCode(context) < latestVersionCode) {
                    handler.post {
                        try {
                            update(context, jsonObject, latestVersionCode)
                        } catch (ex: java.lang.Exception) {
                            Log.e("Update", "Failed to show update dialog: " + ex.message)
                        }
                    }
                }
            } catch (ex: Exception) {
                Log.e("Update", "Update check failed: " + ex.message)
            } finally {
                try {
                    bufferedReader?.close()
                } catch (ex: Exception) {
                }
            }
        }).start()
    }

    private fun update(context: Context, jsonObject: JSONObject, latestVersionCode: Int) {
        val versionName = if (jsonObject.has("versionName")) {
            jsonObject.getString("versionName")
        } else {
            jsonObject.optString("tag_name", "r$latestVersionCode")
        }
        val message = if (jsonObject.has("message")) {
            jsonObject.getString("message")
        } else {
            jsonObject.optString("body", "")
        }
        val releaseUrl = jsonObject.optString("html_url", "")

        DialogHelper.confirm(context,
                "Download new version " + versionName + "?",
                "What's new:" + "\n\n" + message,
                {
                    val downloadUrl = when {
                        jsonObject.has("downloadUrl") -> jsonObject.getString("downloadUrl")
                        releaseUrl.isNotEmpty() -> releaseUrl
                        else -> FALLBACK_DOWNLOAD_PREFIX + latestVersionCode + ".apk"
                    }
                    try {
                        val intent = Intent()
                        intent.setAction(Intent.ACTION_VIEW)
                        intent.data = Uri.parse(downloadUrl)
                        context.startActivity(intent)
                    } catch (ex: java.lang.Exception) {
                        Toast.makeText(context, "Failed to start download!", Toast.LENGTH_SHORT).show()
                    }
                })
                .setCancelable(false)
    }

    fun getRealFilePath(context: Context, uri: Uri?): String? {
        if (null == uri) return null
        val scheme = uri.scheme
        var data: String? = null
        if (scheme == null)
            data = uri.path
        else if (ContentResolver.SCHEME_FILE == scheme) {
            data = uri.path
        } else if (ContentResolver.SCHEME_CONTENT == scheme) {
            val cursor = context.contentResolver.query(uri, arrayOf(MediaStore.Images.ImageColumns.DATA), null, null, null)
            if (null != cursor) {
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(MediaStore.Images.ImageColumns.DATA)
                    if (index > -1) {
                        data = cursor.getString(index)
                    }
                }
                cursor.close()
            }
        }
        return data
    }


    // 安装Apk
    private fun installApk(context: Context, filePath: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW)
            // i.setDataAndType(Uri.fromFile(File(filePath)), "application/vnd.android.package-archive")

            val fileUri = FileProvider.getUriForFile(context, context.applicationContext.packageName + ".provider", File(filePath))
            i.setDataAndType(fileUri, "application/vnd.android.package-archive")

            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        } catch (e: Exception) {
            Log.e("installApk", "" + e.message)
            // Log.e(TAG, "安装失败")
            e.printStackTrace()
        }
    }
}
