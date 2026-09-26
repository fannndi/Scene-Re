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
        // Whether to show an underline
        internal var underline: Boolean = false
        // Whether to display after a line break
        internal var breakRow: Boolean = false
        // Alignment
        internal var align: Layout.Alignment = Layout.Alignment.ALIGN_NORMAL
        // Web page link to open on click
        internal var link: String = ""
        // Activity to open on click
        internal var activity: String = ""
        // Text content
        internal var text: String = ""
        // Script that dynamically provides the text content
        internal var dynamicTextSh: String = ""
        // Script to run on click
        internal var onClickScript: String = ""
    }
}
