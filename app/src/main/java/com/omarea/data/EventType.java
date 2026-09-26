package com.omarea.data;

public enum EventType {
    POWER_CONNECTED,            // charger connected
    POWER_DISCONNECTED,         // charger disconnected
    BATTERY_LOW,                // battery low
    BATTERY_CAPACITY_CHANGED,   // battery capacity changed
    BATTERY_CHANGED,            // battery status changed
    BATTERY_FULL,               // battery full
    CHARGE_CONFIG_CHANGED,      // charge control config changed
    SCREEN_ON,                  // screen on
    SCREEN_OFF,                 // screen off
    APP_SWITCH,                 // app switch
    BOOT_COMPLETED,             // boot completed
    TIMER,                      // timer

    SERVICE_DEBUG,             // service debug config updated
    SERVICE_UPDATE,             // service config updated
    STATE_RESUME,               // state resumed (usually re-applies the app scene mode config after the screen turns on)

    SCENE_MODE_ACTION,          // scene mode: persistent notification action triggered
    SCENE_CONFIG,               // scene mode: global config
    SCENE_APP_CONFIG,           // scene mode: per-app config
}
