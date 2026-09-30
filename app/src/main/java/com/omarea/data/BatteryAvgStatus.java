package com.omarea.data;

public class BatteryAvgStatus {
    public long time;
    public float avgTemperature = -1;
    public float minTemperature = -1;
    public float maxTemperature = -1;
    public int status = 0;
    public int io = -1;
    public String packageName;
    public String mode;
    public int count;
    /** Real measured duration (sum of dt_ms) for this package+mode. */
    public long totalMs;
    /** Sum(capacity * dt_ms) - for average capacity if ever needed. */
    public long capacityMillis;
}
