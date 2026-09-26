package com.omarea.krscript.model

public class PageMenuOption(currentConfigXml: String) : RunnableNode(currentConfigXml) {
    // Whether this is a plain menu item or one with special behavior
    // For example: type finish closes the current page on click, type refresh reloads it, and type file prompts for a file first
    public var type: String = ""
    // Whether it is shown as a floating action button
    public var isFab = false;

    // File MIME type (only valid when type=file)
    var mime: String = ""
    // File suffix (only valid when type=file)
    var suffix: String = ""
}