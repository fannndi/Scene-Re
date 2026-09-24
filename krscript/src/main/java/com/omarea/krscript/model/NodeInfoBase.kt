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

    // 唯一标识（如果需要将功能添加到桌面作为快捷方式，则需要此标识来区分）
    var key: String = ""
    // 索引（自动生成）
    val index: String = UUID.randomUUID().toString()
    // 标题
    var title: String = ""
    // 描述
    var desc: String = ""
    // 描述（脚本）
    var descSh: String = ""
    // 摘要信息
    var summary: String = ""
    // 摘要信息(脚本)
    var summarySh: String = ""
}
