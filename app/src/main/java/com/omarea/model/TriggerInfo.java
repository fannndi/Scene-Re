package com.omarea.model;

import com.omarea.data.EventType;

import java.io.Serializable;
import java.util.ArrayList;

public class TriggerInfo implements Serializable {
    // whether enabled
    public boolean enabled;
    // id
    public String id;
    // events
    public ArrayList<EventType> events;

    // whether to restrict the execution time window
    public boolean timeLimited = false;
    // time window - start time
    public int timeStart = 0;
    // time window - end time
    public int timeEnd = 24 * 60 - 1;

    // task action list
    public ArrayList<TaskAction> taskActions;
    // task action list (custom)
    public ArrayList<CustomTaskAction> customTaskActions;

    public TriggerInfo(String id) {
        this.id = id;
    }
}
