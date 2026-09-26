package com.omarea.krscript.downloader

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Context.DOWNLOAD_SERVICE
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.URLUtil
import android.widget.Toast
import com.omarea.common.shared.FileWrite
import com.omarea.common.ui.DialogHelper
import com.omarea.krscript.R
import org.json.JSONObject
import java.io.File
import java.nio.charset.Charset

class Downloader(private var context: Context, private var activity: Activity? = null) {
    companion object {
        private val HISTORY_CONFIG = "kr_downloader"
    }

    fun downloadByBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW);
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        intent.setData(Uri.parse(url));
        activity?.startActivity(intent);
    }

    fun downloadBySystem(
            url: String,
            contentDisposition: String?,
            mimeType: String?,
            taskAliasId: String,
            fileName: String? = null): Long? {
        try {
            // Specify the download URL
            val request = DownloadManager.Request(Uri.parse(url))
            // Allow media scanning so the file is added to the gallery, music and other media libraries by type
            request.allowScanningByMediaScanner()
            // Show the notification while the download runs and when it completes
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // Notification title; defaults to the file name when not set
            //        request.setTitle("This is title");
            // Notification description
            //        request.setDescription("This is description");
            // Allow downloading over metered networks
            request.setAllowedOverMetered(true)
            // Show this entry in the downloads UI
            request.setVisibleInDownloadsUi(true)
            // Allow downloading while roaming
            request.setAllowedOverRoaming(true)
            // Allowed network types for downloads
            // request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI);
            // request.setAllowedNetworkTypes(DownloadManager.Request.NETWORK_MOBILE);
            // Set the destination path and file name
            val outName = if(fileName.isNullOrEmpty()) URLUtil.guessFileName(url, contentDisposition, mimeType) else fileName
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, outName)
            //        Alternatively, use one of these methods to customize the download path
            //        request.setDestinationUri()
            //        request.setDestinationInExternalFilesDir()
            val downloadManager = context.getSystemService(DOWNLOAD_SERVICE) as DownloadManager
            // Enqueue a download task
            val downloadId = downloadManager.enqueue(request)
            if (taskAliasId.isNotEmpty()) {
                addTaskHisotry(downloadId, taskAliasId, url)
            }
            Toast.makeText(context, context.getString(R.string.kr_download_create_success), Toast.LENGTH_SHORT).show()
            // Register the download-complete listener
            DownloaderReceiver.autoRegister(context.applicationContext)
            return downloadId
        } catch (ex: Exception) {
            DialogHelper.helpInfo(context, context.getString(R.string.kr_download_create_fail), "" + ex.message)
            return null
        }
    }

    // Save the download record
    private fun addTaskHisotry(downloadId: Long, taskAliasId: String, url: String) {
        val historyList = context.getSharedPreferences(HISTORY_CONFIG, Context.MODE_PRIVATE);

        val history = JSONObject();
        history.put("url", url);
        history.put("taskAliasId", taskAliasId);

        historyList.edit().putString(downloadId.toString(), history.toString(2)).apply();
        // FileWrite.writePrivateFile("".toByteArray(Charset.defaultCharset()), "downloader/", context)
    }

    // Save the task status and progress
    fun saveTaskStatus(taskAliasId: String, ratio: Int) {
        FileWrite.writePrivateFile(ratio.toString().toByteArray(Charset.defaultCharset()), "downloader/status/" + taskAliasId, context)
    }

    // Save the path after a successful download
    fun saveTaskCompleted(downloadId: Long, absPath: String) {
        val historyList = context.getSharedPreferences(HISTORY_CONFIG, Context.MODE_PRIVATE);
        val historyStr = historyList.getString(downloadId.toString(), null)
        var taskAliasId: String? = ""
        if (historyStr != null) {
            val hisotry = JSONObject(historyStr)
            hisotry.put("absPath", absPath)
            historyList.edit().putString(downloadId.toString(), hisotry.toString(2)).apply();
            taskAliasId = hisotry.getString("taskAliasId")
        }
        try {
            val file = File(absPath)
            if (file.exists() && file.canRead()) {
                val md5 = FileMD5().getFileMD5(file).lowercase()
                FileWrite.writePrivateFile(absPath.toByteArray(Charset.defaultCharset()), "downloader/path/" + md5, context)
                taskAliasId?.run {
                    FileWrite.writePrivateFile(absPath.toByteArray(Charset.defaultCharset()), "downloader/result/" + taskAliasId, context)
                }
            }
        } catch (ex: java.lang.Exception) {

        }
    }
}
