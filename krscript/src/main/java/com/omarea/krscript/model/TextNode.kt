package com.omarea.krscript.model

import android.text.Layout

class TextNode(currentPageConfigPath: String) : NodeInfoBase(currentPageConfigPath) {
    val rows = ArrayList<TextRow>()

    class TextRow {
        // Text size
        internal var size: Int = -1
        // Text color
        internal var color: Int = -1
        // Text background color
        internal var bgColor: Int = -1
        // Whether bold
        internal var bold: Boolean = false
        // Whether italic
        internal var italic: Boolean = false
        // Whether underlined
        internal var underline: Boolean = false
        // Whether to start on a new line
        internal var breakRow: Boolean = false
        // Alignment
        internal var align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL
        // Web link to open when clicked
        internal var link: String = ""
        // Activity to open when clicked
        internal var activity: String = ""
        // Text content
        internal var text: String = ""
        // Script that dynamically provides the text content
        internal var dynamicTextSh: String = ""
        // Script to run when clicked
        internal var onClickScript: String = ""
    }
}
