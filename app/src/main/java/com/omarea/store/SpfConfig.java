package com.omarea.store;

/**
 * 公共参数
 * Created by helloklf on 2017/11/02.
 */

public class SpfConfig {
    // Per-app overrides for the profile options layer.
    public static String APP_PROFILE_OPTIONS_SPF = "app_profile_options";

    public static String CHARGE_SPF = "charge"; //spf
    public static String CHARGE_SPF_QC_BOOSTER = "qc_booster"; //bool
    public static String CHARGE_SPF_QC_LIMIT = "charge_limit_ma"; //int
    public static int CHARGE_SPF_QC_LIMIT_DEFAULT = 3000; //int
    public static String CHARGE_SPF_BP = "bp"; //bool
    public static String CHARGE_SPF_BP_LEVEL = "bp_level"; //int
    public static int CHARGE_SPF_BP_LEVEL_DEFAULT = 90; //int
    // 是否开启睡眠时间充电速度调整
    public static String CHARGE_SPF_NIGHT_MODE = "sleep_time"; //bool
    // 起床时间
    public static String CHARGE_SPF_TIME_GET_UP = "time_get_up"; //int（hours*60 + minutes）
    // 起床时间（默认为7:00）
    public static int CHARGE_SPF_TIME_GET_UP_DEFAULT = 7 * 60; //
    // 睡觉时间
    public static String CHARGE_SPF_TIME_SLEEP = "time_slepp"; //int（hours*60 + minutes）
    // 睡觉时间（默认为22:30点）
    public static int CHARGE_SPF_TIME_SLEEP_DEFAULT = 22 * 60 + 30;
    // 执行模式
    public static String CHARGE_SPF_EXEC_MODE = "";
    public static int CHARGE_SPF_EXEC_MODE_SPEED_UP = 0; // 目标 加快充电
    public static int CHARGE_SPF_EXEC_MODE_SPEED_DOWN = 1; // 目标 降低速度保护电池
    public static int CHARGE_SPF_EXEC_MODE_SPEED_FORCE = 2; // 目标 强制加速
    public static int CHARGE_SPF_EXEC_MODE_DEFAULT = CHARGE_SPF_EXEC_MODE_SPEED_UP; // 目标（默认设置）

    public static String DATA = "data";
    public static String WIFI = "wifi";
    public static String NFC = "nfc";
    public static String GPS = "gps";
    public static String POWERSAVE = "powersave";

    public static String ON = "_on";
    public static String OFF = "_off";

    public static String GLOBAL_SPF = "global"; //spf
    public static String GLOBAL_SPF_HELP_ICON = "show_help_icon";
    public static String GLOBAL_SPF_DISABLE_ENFORCE = "enforce_0";
    public static String GLOBAL_SPF_START_DELAY = "start_delay";
    public static String GLOBAL_SPF_SCENE_LOG = "scene_logview";
    public static String GLOBAL_SPF_AUTO_EXIT = "auto_exit";
    public static String GLOBAL_SPF_NIGHT_MODE = "app_night_mode";
    public static String GLOBAL_SPF_THEME = "app_theme5";
    public static String GLOBAL_SPF_POWERCFG_FIRST_MODE = "powercfg_first_mode";
    public static String GLOBAL_SPF_POWERCFG_SLEEP_MODE = "powercfg_sleep_mode";
    public static String GLOBAL_SPF_PROFILE_SOURCE = "scene_profile_source";
    public static String GLOBAL_SPF_POWERCFG = "global_powercfg";
    public static String GLOBAL_SPF_CONTRACT = "global_contract_scene5";
    public static String GLOBAL_SPF_LAST_UPDATE = "global_last_update";
    public static String GLOBAL_SPF_CURRENT_NOW_UNIT = "global_current_now_unit";
    public static int GLOBAL_SPF_CURRENT_NOW_UNIT_DEFAULT = -1000;
    public static String GLOBAL_SPF_FREEZE_ICON_NOTIFY = "freeze_icon_notify";
    public static String GLOBAL_SPF_FREEZE_SUSPEND = "freeze_suspend";
    public static String GLOBAL_SPF_FREEZE_TIME_LIMIT = "freeze_suspend_time_limit";
    public static String GLOBAL_NIGHT_BLACK_NOTIFICATION = "night_black_notification";

    public static String SWAP_SPF = "swap"; //spf
    public static String SWAP_SPF_SWAP = "swap";
    public static String SWAP_SPF_SWAP_SWAPSIZE = "swap_size";
    public static String SWAP_SPF_SWAP_PRIORITY = "swap_priority";
    public static String SWAP_SPF_SWAP_USE_LOOP = "swap_use_loop";
    public static String SWAP_SPF_ZRAM = "zram";
    public static String SWAP_SPF_ZRAM_SIZE = "zram_size";
    public static String SWAP_SPF_SWAPPINESS = "swappiness";
    public static String SWAP_SPF_EXTRA_FREE_KBYTES = "extra_free_kbytes";
    public static String SWAP_SPF_WATERMARK_SCALE = "watermark_scale";
    public static String SWAP_SPF_AUTO_LMK = "auto_lmk";
    public static String SWAP_SPF_ALGORITHM = "comp_algorithm"; // zram 压缩算法

    public static String SCENE_BLACK_LIST = "scene_black_list_spf";

    // Profile options: the tuning layer applied after every powercfg switch.
    public static String GLOBAL_SPF_PROFILE_OPTIONS = "profile_options_enabled";
    public static String GLOBAL_SPF_PROFILE_LIMIT_PERCENT = "profile_limit_percent";
    public static String GLOBAL_SPF_PROFILE_LITE = "profile_lite_mode";
    public static String GLOBAL_SPF_PROFILE_GOVERNOR = "profile_governor";
    public static String GLOBAL_SPF_PROFILE_IOSCHED = "profile_io_scheduler";
    public static String GLOBAL_SPF_PROFILE_PID_PRIORITY = "profile_pid_priority";
    public static String GLOBAL_SPF_PROFILE_DND_GAME = "profile_dnd_game";
    public static String GLOBAL_SPF_PROFILE_PRELOAD = "profile_game_preload";
    public static String GLOBAL_SPF_PROFILE_PRELOAD_BUDGET = "profile_preload_budget_mb";
    public static String GLOBAL_SPF_PROFILE_BYPASS_GAME = "profile_bypass_charge";
    public static String GLOBAL_SPF_PROFILE_EXTRA_TWEAKS = "profile_extra_tweaks";
    public static String GLOBAL_SPF_PROFILE_GAME_DOWNSCALE = "profile_game_downscale";
    public static String GLOBAL_SPF_PROFILE_GAME_FPS = "profile_game_fps";
    public static String GLOBAL_SPF_PROFILE_GAME_RENDERER = "profile_game_renderer";
    // Clear the page cache right after a game starts (Encore drop_caches).
    public static String GLOBAL_SPF_PROFILE_DROP_CACHES = "profile_drop_caches_on_game";
    // Hold the DDR latency nodes at their middle OPP while a game runs
    // (MIUI GameOptimizationFeature MIN_DDR_FREQ).
    public static String GLOBAL_SPF_PROFILE_GAME_DDR_FLOOR = "profile_game_ddr_floor";
    // Experimental: send the vendor game-boost hint through the QTI perf HAL.
    public static String GLOBAL_SPF_PROFILE_QTI_HINTS = "profile_qti_hints";
    // AZenith-style addon toggles.
    public static String GLOBAL_SPF_PROFILE_GOV_TUNES = "profile_gov_tunes";
    public static String GLOBAL_SPF_PROFILE_STOP_TRACE = "profile_stop_trace";
    public static String GLOBAL_SPF_PROFILE_STOP_LOGGERS = "profile_stop_loggers";
    // Standing HWUI renderer override for every app (system default = "").
    public static String GLOBAL_SPF_PROFILE_GLOBAL_RENDERER = "profile_global_renderer";
    // Qualcomm bus/DRAM + GPU boost (Encore-style Snapdragon profile).
    public static String GLOBAL_SPF_PROFILE_QCOM_BUS = "profile_qcom_bus_boost";
    public static String GLOBAL_SPF_PROFILE_QCOM_GPU = "profile_qcom_gpu_boost";
    public static String GLOBAL_SPF_PROFILE_QCOM_GPU_PS = "profile_qcom_gpu_powersave";
    // GPU frequency limiter (% of the top Adreno OPP; 0 = off).
    public static String GLOBAL_SPF_PROFILE_GPU_LIMIT = "profile_gpu_limit_percent";
    // Light games: adaptive detection and the caps applied while one runs.
    public static String GLOBAL_SPF_PROFILE_LIGHT_DETECT = "profile_light_detect";
    public static String GLOBAL_SPF_PROFILE_LIGHT_CPU_LIMIT = "profile_light_cpu_limit";
    public static int GLOBAL_SPF_PROFILE_LIGHT_CPU_LIMIT_DEFAULT = 70;
    public static String GLOBAL_SPF_PROFILE_LIGHT_GPU_LIMIT = "profile_light_gpu_limit";
    public static int GLOBAL_SPF_PROFILE_LIGHT_GPU_LIMIT_DEFAULT = 60;
    // Thermal guard: cap CPU/GPU while the battery runs hot during a game.
    public static String GLOBAL_SPF_THERMAL_GUARD = "thermal_guard_enabled";
    public static String GLOBAL_SPF_THERMAL_GUARD_TEMP = "thermal_guard_temp_c";
    public static int GLOBAL_SPF_THERMAL_GUARD_TEMP_DEFAULT = 43;
    public static String GLOBAL_SPF_THERMAL_GUARD_PERCENT = "thermal_guard_percent";
    public static int GLOBAL_SPF_THERMAL_GUARD_PERCENT_DEFAULT = 70;
    // Game session report (battery / thermal / frequency history per game).
    public static String GLOBAL_SPF_GAME_SESSIONS = "game_sessions_enabled";
    // Follow the system battery saver with the powersave profile.
    public static String GLOBAL_SPF_PROFILE_FOLLOW_SAVER = "profile_follow_battery_saver";
    // Mode to restore once the system battery saver turns off.
    public static String GLOBAL_SPF_PROFILE_SAVER_BACKUP = "profile_battery_saver_backup";
    public static String GLOBAL_SPF_MONITOR_FALLBACK = "monitor_fallback_enabled";
    public static String GLOBAL_SPF_MONITOR_GAME_MODE = "monitor_game_mode";
    // Boot guard: incremented by BootWorker, cleared once the UI comes up.
    public static String GLOBAL_SPF_BOOT_COUNT = "boot_guard_count";
    // Saved Do Not Disturb mode while a game is in the foreground (-1 = untouched).
    public static String GLOBAL_SPF_DND_BACKUP = "dnd_backup";
    // Thermal PID loop.
    public static String GLOBAL_SPF_THERMAL_PID = "thermal_pid_enabled";
    // Automatic bypass charging while gaming.
    public static String GLOBAL_SPF_BYPASS_AUTO = "bypass_auto_enabled";
    public static String GLOBAL_SPF_BYPASS_THRESHOLD = "bypass_auto_threshold";
    public static int GLOBAL_SPF_BYPASS_THRESHOLD_DEFAULT = 50;
    public static String GLOBAL_SPF_BYPASS_NODE = "bypass_node_name";
}
