package com.omarea.krscript.model

public class PageNode(currentConfigXml: String) : ClickableNode(currentConfigXml) {
    public var pageConfigPath: String = ""
    public var pageConfigSh: String = ""
    public var onlineHtmlPage: String = ""
    // Web page link to open on click
    public var link: String = ""
    // Activity to open on click
    public var activity: String = ""

    // Before the page config is read
    public var beforeRead = ""
    // After the page config is read
    public var afterRead = ""

    // Menu option settings
    public var pageMenuOptions: ArrayList<PageMenuOption>? = null
    public var pageMenuOptionsSh: String = ""
    // Script that handles menu and floating button clicks
    public var pageHandlerSh:  String = ""

    // Page load failure
    public var loadSuccess = ""
    // Page load success
    public var loadFail = ""
}
