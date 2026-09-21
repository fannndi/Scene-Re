package com.omarea.data;

public enum EventType {
    POWER_CONNECTED,            // charger connected
    POWER_DISCONNECTED,         // charger disconnected
    BATTERY_LOW,                // battery low
    BATTERY_CAPACITY_CHANGED,   // battery level changed
    BATTERY_CHANGED,            // battery state changed
    BATTERY_FULL,               // battery full
    CHARGE_CONFIG_CHANGED,      // charge control config changed
    SCREEN_ON,                  // screen on
    SCREEN_OFF,                 // screen off
    APP_SWITCH,                 // app switch
    BOOT_COMPLETED,             // boot completed
    TIMER,                      // timer

    SERVICE_DEBUG,             // service debug config updated
    SERVICE_UPDATE,             // service config updated
    STATE_RESUME,               // state resume (usually applies scene mode config after screen on)

    SCENE_MODE_ACTION,          // scene mode persistent notification action triggered
    SCENE_CONFIG,               // scene mode shared config
    SCENE_APP_CONFIG,           // scene mode per-app config
}
