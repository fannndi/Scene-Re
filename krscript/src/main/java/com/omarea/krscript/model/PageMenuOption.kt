package com.omarea.krscript.model

public class PageMenuOption(currentConfigXml: String) : RunnableNode(currentConfigXml) {
    // Whether this is a plain menu item or one with special behavior
    // e.g. type finish closes the current page, type refresh reloads it, type file requires choosing a file first
    public var type: String = ""
    // Whether to show as a floating action button
    public var isFab = false;

    // File MIME type (only valid for type=file)
    var mime: String = ""
    // File suffix (only valid for type=file)
    var suffix: String = ""
}