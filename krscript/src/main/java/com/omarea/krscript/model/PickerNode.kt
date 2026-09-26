package com.omarea.krscript.model

import com.omarea.common.model.SelectItem

class PickerNode(currentConfigXml: String) : RunnableNode(currentConfigXml) {
    var options: ArrayList<SelectItem>? = null
    var optionsSh = ""
    var value: String? = null

    var getState: String? = null

    // Parameter name
    var name: String = ""
    // Whether multiple selection is allowed
    var multiple: Boolean = false
    // Separator for multiple values
    var separator: String = "\n"
}
