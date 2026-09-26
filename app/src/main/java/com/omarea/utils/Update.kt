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

    fun checkUpdate(context: Context) {
        val handler = Handler(Looper.getMainLooper());
        Thread(Runnable {
            //http://47.106.224.127/
            try {
                val url = URL("")
                val connection = url.openConnection()
                // Set the request method: GET
                // connection.setRequestMethod("GET");
                // Connection timeout to the host server: 15000 ms
                connection.connectTimeout = 15000
                // Read timeout for remote data: 60000 ms
                connection.readTimeout = 60000
                val bufferedReader = BufferedReader(InputStreamReader(connection.getInputStream()))
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

                if (jsonObject.has("versionCode")) {
                    val currentVersion = currentVersionCode(context)
                    if (currentVersion < jsonObject.getInt("versionCode")) {
                        handler.post {
                            try {
                                update(context, jsonObject)
                            } catch (ex: java.lang.Exception) {

                            }
                        }
                    }
                }
            } catch (ex: Exception) {
                /*
                handler.post {
                    Toast.makeText(context, "Update check failed!\n" + ex.message, Toast.LENGTH_SHORT).show()
                }
                */
            }
        }).start()
    }

    private fun update(context: Context, jsonObject: JSONObject) {
        DialogHelper.confirm(context,
                "Download new version " + jsonObject.getString("versionName") + "?",
                "What's new:" + "\n\n" + jsonObject.getString("message"),
                {
                    var downloadUrl = "http://vtools.oss-cn-beijing.aliyuncs.com/app-release${jsonObject.getInt("versionCode")}.apk"// "http://47.106.224.127/publish/app-release.apk"
                    if (jsonObject.has("downloadUrl")) {
                        downloadUrl = jsonObject.getString("downloadUrl")
                    }
                    try {
                        val intent = Intent()
                        intent.setAction(Intent.ACTION_VIEW)
                        intent.data = Uri.parse(downloadUrl)
                        context.startActivity(intent)
                    } catch (ex: java.lang.Exception) {
                        Toast.makeText(context, "Failed to start download!", Toast.LENGTH_SHORT).show()
                    }
                    /*
                    // Create the download task; downloadUrl is the download link
                    val request = DownloadManager.Request(Uri.parse(downloadUrl));
                    // Set the download path and file name
                    request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "Scene_" + jsonObject.getString("versionName") + ".apk");
                    // Show download progress in the notification bar
                    request.allowScanningByMediaScanner();
                    request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_MOBILE or DownloadManager.Request.NETWORK_WIFI)
                    request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    // Get the download manager
                    val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                    // Enqueue the download task, otherwise it will not start
                    val taskId = downloadManager.enqueue(request)

                    val intentFilter = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                    context.registerReceiver(object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            val id = intent!!.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
                            if (id == taskId) {
                                val path = getRealFilePath(context!!, downloadManager.getUriForDownloadedFile(taskId))
                                Toast.makeText(context, "Download complete. Please tap the notification to install the update.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }, intentFilter)
                    */
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


    // Install the APK
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
            // Log.e(TAG, "Install failed")
            e.printStackTrace()
        }
    }
}
