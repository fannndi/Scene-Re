package com.omarea.krscript.model

open class RunnableNode(currentConfigXml: String) : ClickableNode(currentConfigXml) {

    // Whether to show a confirmation prompt before starting
    var confirm: Boolean = false
    // Warning message
    var warning: String = ""
    // Whether to close the log view automatically when execution completes
    var autoOff: Boolean = false
    // Whether execution can be interrupted
    var interruptable: Boolean = true
    // Whether to reload the whole page after execution
    var reloadPage: Boolean = false
    // Feature areas (ids) to refresh after execution
    var updateBlocks: Array<String>? = null
    // Whether to close the page automatically when execution completes
    var autoFinish = false

    // UI mode (default, bg-task, hidden)
    var shell = shellModeDefault

    companion object {
        val shellModeDefault = "default"
        val shellModeBgTask = "bg-task"
        val shellModeHidden = "hidden"
    }

    //
    var setState: String? = null
}
