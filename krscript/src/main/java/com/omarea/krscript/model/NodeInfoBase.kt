package com.omarea.krscript.model

import java.io.File
import java.io.Serializable
import java.util.*

open class NodeInfoBase(public val currentPageConfigPath: String) : Serializable {
    public val pageConfigDir = (
        if (currentPageConfigPath.isNotEmpty()) {
            val dir = File(currentPageConfigPath).parent
            if (dir.startsWith("file:/android_asset/")) {
                "file:///android_asset/" + dir.substring("file:/android_asset/".length)
            } else {
                dir
            }
        } else {
            ""
        }
    )

    // Unique ID (needed to distinguish features added to the desktop as shortcuts)
    var key: String = ""
    // Index (auto-generated)
    val index: String = UUID.randomUUID().toString()
    // Title
    var title: String = ""
    // Description
    var desc: String = ""
    // Description (script)
    var descSh: String = ""
    // Summary
    var summary: String = ""
    // Summary (script)
    var summarySh: String = ""
}
