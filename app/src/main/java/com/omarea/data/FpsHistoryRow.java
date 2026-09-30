package com.omarea.data;

/**
 * One `fps_history` row (schema v2) with measurement provenance.
 */
public class FpsHistoryRow {
    public long time;
    public long session;
    public float fps;
    public double cpuLoad;
    public double gpuLoad;
    public int capacity;
    public double temperature;
    public String powerMode;
    /** Real spacing to the previous sample (ms); 1000 for legacy rows. */
    public long dtMs = 1000;
    /** measured_fps | gfxinfo | fpsgo | sf_counter | legacy */
    public String source;
    public int refreshHz;
    /** -1 when the source does not report jank. */
    public int jankFrames = -1;
    /** -1 when unknown. */
    public int frames = -1;
    public boolean valid = true;
}
