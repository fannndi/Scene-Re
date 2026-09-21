package com.omarea.krscript.model

import com.omarea.common.model.SelectItem

class ActionParamInfo {
    // Parameter name: must be unique
    var name: String? = null

    var title: String? = null

    var label: String? = null

    // Description
    var desc: String? = null

    // Value
    var value: String? = null
    var valueShell: String? = null
    var valueFromShell: String? = null
    var maxLength = -1 // input only
    var type: String? = null
    var max: Int = Int.MAX_VALUE // seekbar only
    var min: Int = Int.MIN_VALUE // seekbar only
    var required: Boolean = false // Whether it is required
    var readonly: Boolean = false
    var options: ArrayList<SelectItem>? = null
    var optionsFromShell: ArrayList<SelectItem>? = null
    var optionsSh = ""
    // Whether multiple selection is allowed (options only)
    var multiple: Boolean = false
    // Whether it is supported
    var supported: Boolean = true
    // Text field watermark (placeholder hint)
    var placeholder: String = ""
    // File MIME type (only valid for type=file)
    var mime: String = ""
    // File suffix (only valid for type=file)
    var suffix: String = ""
    // Whether the user may enter a path manually
    var editable: Boolean = false
    // Separator for multiple values (multi-select dropdown only)
    var separator: String = "\n"
}
