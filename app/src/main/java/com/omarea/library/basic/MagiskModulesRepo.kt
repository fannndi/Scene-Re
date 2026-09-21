package com.omarea.library.basic

import android.util.Log
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.Exception
import java.net.URL
import java.net.URLEncoder

class MagiskModulesRepo {
    // https://magisk-modules-repo.github.io/submission/modules.json

    fun query(keywords: String?): ArrayList<String> {
        val url = URL("https://github.com/orgs/Magisk-Modules-Repo/repositories?q=" + URLEncoder.encode(keywords, "UTF-8"))
        val connection = url.openConnection()
        connection.setRequestProperty("x-requested-with", "XMLHttpRequest")
        // set connection timeout in ms
        connection.connectTimeout = 8000
        // set read timeout in ms
        connection.readTimeout = 15000
        connection.connect()

        /*
        // read stream
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
        */
        val content = String(connection.getInputStream().readBytes(), Charsets.UTF_8).split("\n")
        val reg = Regex(".*href=\"/Magisk-Modules-Repo/.*")
        val modules = ArrayList<String>()
        for (row in content) {
            val result = reg.matches(row)
            if (result) {
                var value = row.substring(row.indexOf("href=") + 7)
                value = value.substring(0, value.indexOf("\""))
                if (value.split("/").size == 2 && !value.endsWith("/") && !modules.contains(value)) {
                    modules.add(value)
                }
            }
        }
        return modules
    }

    // get Repository
    private fun getCodeRepository (parser: XmlPullParser): String? {
        // a tags
        if (parser.name == "a") {
            var href: String? = null
            var isCodeRepository = false
            for (i in 0 until parser.attributeCount) {
                val attrName = parser.getAttributeName(i)
                if (attrName == "href") {
                    href = parser.getAttributeValue(i)
                } else if (attrName == "itemprop" && parser.getAttributeValue(i).contains("codeRepository")) {
                    isCodeRepository = true
                }
            }
            if (isCodeRepository && !href.isNullOrEmpty()) {
                return href
            }
            // TODO: parse tag URLs
        }
        return null
    }
}