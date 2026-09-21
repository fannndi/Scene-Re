package com.omarea.krscript.model

public class PageNode(currentConfigXml: String) : ClickableNode(currentConfigXml) {
    public var pageConfigPath: String = ""
    public var pageConfigSh: String = ""
    public var onlineHtmlPage: String = ""
    // Web link to open when clicked
    public var link: String = ""
    // Activity to open when clicked
    public var activity: String = ""

    // Before reading the page config
    public var beforeRead = ""
    // After reading the page config
    public var afterRead = ""

    // Menu options
    public var pageMenuOptions: ArrayList<PageMenuOption>? = null
    public var pageMenuOptionsSh: String = ""
    // Script handling menu and FAB click events
    public var pageHandlerSh:  String = ""

    // Page load failure
    public var loadSuccess = ""
    // Page load success
    public var loadFail = ""
}
