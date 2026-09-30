package com.omarea.data;

import android.graphics.drawable.Drawable;

public class FpsWatchSession {
    public Long sessionId;
    public String packageName;
    public Long beginTime;
    /** -1 while the session is still running. */
    public Long endTime = -1L;
    public String appName;
    public Drawable appIcon;
}
