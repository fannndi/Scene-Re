package com.omarea.krscript.model

open class ClickableNode(currentPageConfigPath: String) : NodeInfoBase(currentPageConfigPath) {
    // Feature icon path (in the list)
    var iconPath = ""

    // Feature icon path (desktop shortcut)
    var logoPath = ""

    // Whether a shortcut may be added (not false; allowed by default when a key exists)
    var allowShortcut:Boolean? = null

    // Whether it is locked
    var locked: Boolean = false
    // Lock state getter (script)
    var lockShell: String = ""

    // Android SDK version requirements of this feature
    var targetSdkVersion = 0
    var minSdkVersion = 0
    var maxSdkVersion = 100
}
