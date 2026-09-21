package com.omarea.krscript.model

open class ClickableNode(currentPageConfigPath: String) : NodeInfoBase(currentPageConfigPath) {
    // Feature icon path (in lists)
    var iconPath = ""

    // Feature icon path (desktop shortcut)
    var logoPath = ""

    // Whether shortcuts are allowed (defaults to allowed when not false and a key exists)
    var allowShortcut:Boolean? = null

    // Whether it is locked
    var locked: Boolean = false
    // Lock state query (script)
    var lockShell: String = ""

    // Android SDK version requirements for this feature
    var targetSdkVersion = 0
    var minSdkVersion = 0
    var maxSdkVersion = 100
}
