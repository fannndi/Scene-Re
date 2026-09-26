package com.omarea.krscript.model

import java.io.File
import java.io.Serializable
import java.util.*

open class NodeInfoBase(public val currentPageConfigPath: String) : Serializable {
    public val pageConfigDir = (
        if (currentPageConfigPath.isNotEmpty()) {
            // File.parent is null when the path has no parent component at all (for
            // example a bare "config.xml"). Calling startsWith() on it would NPE, so
            // fall back to an empty string, which is what the old code produced for
            // the no-config case anyway.
            val dir = File(currentPageConfigPath).parent ?: ""
            if (dir.startsWith("file:/android_asset/")) {
                "file:///android_asset/" + dir.substring("file:/android_asset/".length)
            } else {
                dir
            }
        } else {
            ""
        }
    )

    // Unique identifier (needed to tell features apart when adding one to the desktop as a shortcut)
    var key: String = ""
    // Index (generated automatically)
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
