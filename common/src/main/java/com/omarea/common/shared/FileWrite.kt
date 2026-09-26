package com.omarea.common.shared

import android.content.Context
import android.content.res.AssetManager
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException

/**
 * Provides common helpers for reading and writing files on external storage
 * Created by helloklf on 2016/8/27.
 */
object FileWrite {
    val SDCardDir: String = Environment.getExternalStorageDirectory().absolutePath

    fun getPrivateFileDir(context: Context): String {
        return context.filesDir.absolutePath + "/"
    }

    fun getPrivateFilePath(context: Context, outName: String): String {
        return getPrivateFileDir(context) + (if (outName.startsWith("/")) outName.substring(1, outName.length) else outName)
    }
    fun writePrivateFile(file: String, outName: String, context: Context): String? {
        return writePrivateFile(context.assets, file, outName, context);
    }

    fun writePrivateFile(assetManager: AssetManager, file: String, outName: String, context: Context): String? {
        try {
            val inputStream = if (file.startsWith("file:///android_asset/")) {
                assetManager.open(file.substring("file:///android_asset/".length))
            } else {
                assetManager.open(file)
            }

            val dir = File(getPrivateFileDir(context))
            if (!dir.exists())
                dir.mkdirs()
            val filePath = getPrivateFilePath(context, outName)
            val fileDir = File(filePath).parentFile
            if (fileDir != null && !fileDir.exists()) {
                fileDir.mkdirs()
            }

            val fileOutputStream = FileOutputStream(filePath)

            val datas = ByteArray(20480)
            while (true) {
                val len = inputStream.read(datas)
                if (len > 0) {
                    fileOutputStream.write(datas, 0, len)
                } else {
                    break
                }
            }

            fileOutputStream.close()
            inputStream.close()
            val writedFile = File(filePath)
            writedFile.setWritable(true)
            writedFile.setExecutable(true)
            writedFile.setReadable(true)
            return filePath
            //getApplicationContext().getClassLoader().getResourceAsStream("");
        } catch (e: IOException) {
            Log.e("writePrivateFile", "" + e.message)
            e.printStackTrace()
        }
        return null
    }

    fun writePrivateFile(bytes: ByteArray, outName: String, context: Context): Boolean {
        try {
            val dir = File(getPrivateFileDir(context))
            if (!dir.exists())
                dir.mkdirs()
            val filePath = getPrivateFilePath(context, outName)
            val fileDir = File(filePath).parentFile
            if (fileDir != null && !fileDir.exists()) {
                fileDir.mkdirs()
            }

            val fileOutputStream = FileOutputStream(filePath)
            fileOutputStream.write(bytes, 0, bytes.size)
            fileOutputStream.close()
            File(filePath).setExecutable(true, false)
            //getApplicationContext().getClassLoader().getResourceAsStream("");
            val writedFile = File(filePath)
            writedFile.setWritable(true)
            writedFile.setExecutable(true)
            writedFile.setReadable(true)
            return true
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        }
    }

    fun writePrivateShellFile(file: String, outName: String, context: Context): String? {
        val data = parseText(context, file)
        if (data.size > 0 && writePrivateFile(data, outName, context)) {
            return getPrivateFilePath(context, outName)
        }
        return null
    }

    // Convert DOS to Unix line endings so a stray \r\n cannot break script parsing
    private fun parseText(context: Context, fileName: String): ByteArray {
        try {
            val assetManager = context.assets
            // Read the whole asset in a loop.
            //
            // The previous version sized the buffer with inputStream.available()
            // and made a single read() call. Neither is safe: available() is a
            // hint, not a length, and one read() is explicitly allowed to return
            // short - which is the normal case for a compressed asset. The result
            // was a silently truncated script, and the failure only shows up as
            // "command not found" on the device. readBytes() loops until EOF.
            val bytes = assetManager.open(fileName).use { it.readBytes() }
            // Normalise DOS line endings: a stray \r becomes part of the last
            // token on the line, which mksh then treats as a command name.
            val codes = String(bytes, Charsets.UTF_8)
                .replace("\r\n", "\n")
                .replace("\r\t", "\t")
            return codes.toByteArray(Charsets.UTF_8)
        } catch (ex: Exception) {
            Log.e("script-parse", "" + ex.message)
            return "".toByteArray()
        }
    }
}
