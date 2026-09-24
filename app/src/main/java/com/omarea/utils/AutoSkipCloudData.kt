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
    companion object {
        /** Bundled copy of the same list, used when the network is unavailable. */
        private const val SEED_ASSET = "addin/auto-skip-config-v1.json"

        /** Writes the bundled skip rules into the store. Returns how many were added. */
        private fun seedFromAsset(context: Context, db: AutoSkipConfigStore): Int {
            val text = context.assets.open(SEED_ASSET).use { input ->
                input.bufferedReader().readText()
            }
            val data = JSONArray(text.trim())
            var added = 0
            for (index in 0 until data.length()) {
                val row = data.getJSONObject(index)
                if (db.addConfig(row.getString("activity"), row.getString("viewId"))) {
                    added++
                }
            }
            return added
        }
    }

    fun updateConfig(context: Context, showMsg: Boolean) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val url = URL("https://vtools.oss-cn-beijing.aliyuncs.com/addin/auto-skip-config-v1.json")
                val connection = url.openConnection()
                // 设置连接方式：get
                // connection.setRequestMethod("GET");
                // 设置连接主机服务器的超时时间：15000毫秒
                connection.connectTimeout = 15000
                // 设置读取远程返回的数据时间：60000毫秒
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
                SceneLog.i("AutoSkip", "cloud config applied: ${data.length()} entries")
            } catch (ex: Exception) {
                // The cloud fetch failed. Previously this left the store empty, so
                // auto-skip silently did nothing until the network came back. Fall
                // back to the bundled seed so the feature still works offline.
                val seeded = runCatching {
                    val db = AutoSkipConfigStore(context)
                    db.clearAll()
                    seedFromAsset(context, db)
                }.getOrDefault(-1)

                if (seeded >= 0) {
                    SceneLog.w(
                        "AutoSkip",
                        "cloud fetch failed; seeded $seeded entries from bundled config", ex
                    )
                    if (showMsg) {
                        Scene.toast("Cloud unavailable, using $seeded bundled auto-skip entries")
                    }
                } else {
                    SceneLog.e("AutoSkip", "cloud fetch failed and bundled seed also failed", ex)
                    if (showMsg) {
                        Scene.toast("Failed to fetch cloud config data")
                    }
                }
            }
        }
    }
}
