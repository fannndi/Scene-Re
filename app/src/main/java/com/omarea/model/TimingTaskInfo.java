package com.omarea.model;

import java.io.Serializable;
import java.util.ArrayList;

public class TimingTaskInfo implements Serializable {
    // task id
    public String taskId;
    // task name
    public String taskName;
    // whether enabled
    public boolean enabled;
    // trigger time as hours * 60 + minutes, e.g. 6:30 is 6 * 60 + 30 = 390
    public int triggerTimeMinutes = 420;
    // task expiry time
    public long expireDate;
    // execute after the screen turns off
    public boolean afterScreenOff;
    // ask for confirmation before executing
    public boolean beforeExecuteConfirm;
    // battery level requirement (skip when below this value and not charging)
    public int batteryCapacityRequire;
    // execute only while charging
    public boolean chargeOnly;
    // task action list
    public ArrayList<TaskAction> taskActions;
    // task action list (custom)
    public ArrayList<CustomTaskAction> customTaskActions;

    public TimingTaskInfo() {
    }

    public TimingTaskInfo(String taskId) {
        this.taskId = taskId;
    }
}
