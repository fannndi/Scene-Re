@file:OptIn(DelicateCoroutinesApi::class)

package com.omarea.utils

import android.content.Context
import com.omarea.Scene
import com.omarea.store.AutoSkipConfigStore
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL

class AutoSkipCloudData {
    fun updateConfig(context: Context, showMsg: Boolean) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://vtools.oss-cn-beijing.aliyuncs.com/addin/auto-skip-config-v1.json")
                val connection = url.openConnection()
                // set request method: GET
                // connection.setRequestMethod("GET");
                // set connection timeout: 15000 ms
                connection.connectTimeout = 15000
                // set read timeout: 60000 ms
                connection.readTimeout = 20000
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
                val data = JSONArray(stringBuilder.toString().trim { it <= ' ' })
                if (showMsg) {
                    Scene.toast("Fetched " + data.length() + " auto-skip entries from the cloud")
                }
                val db = AutoSkipConfigStore(context)
                db.clearAll()
                for (index in 0 until data.length()) {
                    val row = data.getJSONObject(index)
                    row?.run {
                        db.addConfig(getString("activity"), getString("viewId"))
                    }
                }
            } catch (ex: Exception) {
                if (showMsg) {
                    Scene.toast("Failed to fetch cloud config data")
                }
            }
        }
    }
}
