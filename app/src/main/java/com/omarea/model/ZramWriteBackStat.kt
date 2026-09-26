package com.omarea.model

public class ZramWriteBackStat {
    public var backingDev: String? = null
    // bytes written to the backing device, KB
    public var backed: Int = 0
    // historical reads (from the backing device), KB
    public var backReads: Int = 0
    // historical write-backs (to the backing device), KB
    public var backWrites: Int = 0
}