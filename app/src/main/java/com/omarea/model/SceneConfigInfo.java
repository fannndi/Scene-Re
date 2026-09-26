package com.omarea.model;

import android.content.pm.ActivityInfo;

public class SceneConfigInfo {
    public String packageName;

    // use per-app brightness
    public boolean aloneLight = false;
    // per-app brightness value
    public int aloneLightValue = -1;
    // block notifications
    public boolean disNotice = false;
    // intercept keys
    public boolean disButton = false;
    // enable GPS on launch
    public boolean gpsOn = false;
    // app freeze (auto-freeze)
    public boolean freeze = false;
    // screen orientation
    public int screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    // cgroup - memory
    public String fgCGroupMem = "";
    public String bgCGroupMem = "";
    public boolean dynamicBoostMem = false;

    // show the performance monitor
    public boolean showMonitor = false;
}
