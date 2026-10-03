package com.omarea.data;

public class SceneConfigInfo {
    public String packageName;

    // 应用偏见（自动冻结）
    public boolean freeze = false;

    // cgroup - memory
    public String fgCGroupMem = "";
    public String bgCGroupMem = "";
    public boolean dynamicBoostMem = false;

    // 显示性能监视器
    public boolean showMonitor = false;
}
