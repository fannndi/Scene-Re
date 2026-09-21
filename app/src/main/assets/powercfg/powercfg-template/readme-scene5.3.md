## Applicable versions
- This document applies to Scene 5.3.0 (framework version `130`)

### New features in framework 130
- features: new root configuration
- features.high_rate: extension feature, configuration for the screen refresh rate control function @high_rate
- features.processes: extension feature for assigning important Android app processes to a cpuset
- features.charge_control: extension feature, configuration for the charging control function @charge
- affiniy.cpuset_mode: makes cpuset work together with affiniy for stronger thread placement constraints
- affiniy.heavy_thread: specifies the name of the heavy-load thread
- affiniy.heavy_mask: specifies the affiniy mask for the heavy-load thread
- affiniy.unity_main: specifies the affiniy mask for the UnityMain thread
- cpuset: assigns cores to app threads via cpuset
- import: splits per-app configuration into separate files
- booster.events: added the presets event
- presets: new root configuration for creating a set of presets, usable via [@preset] [name]
- sensor.props: specifies a set of properties to modify
- sensor.rules.values: specifies the values for a set of properties
- sensor.rules.enter_once: enter configuration that runs only once
- @governor: new function to switch the governor of all clusters at once
- @high_rate: switches between high and low refresh rate states
- @charge: charging control function

### Removed features in framework 130
- @refresh_rate: screen refresh rate function, now deprecated
- stop_on: this configuration is no longer recommended; its documentation has been removed


## Creating a profile
- Before you start, you should at least have a basic understanding of JSON syntax
- Below is an empty profile
> The `platform` property marks that this profile targets the `msm8998` platform and is required<br>
> **platform_name** is a descriptive property that helps users understand what **msm8998** is<br>
> The `framework` property indicates the scheduling framework version this profile targets<br>
> `schemes` contains the configuration for the 5 modes in Scene 5

```json
{
  "platform": "msm8998",
  "platform_name": "Snapdragon 835",
  "framework": 130,
  "schemes": {
    "powersave": {
      "call": []
    },
    "balance": {
      "call": []
    },
    "performance": {
      "call": []
    },
    "fast": {
      "call": []
    },
    "pedestal": {
      "call": []
    }
  }
}
```

| KEY | NAME |
| :- | :- |
| powersave | Power save |
| balance | Balanced |
| performance | Performance |
| fast | Fast |
| pedestal | Pedestal |



## Built-in functions
- Scene provides some commonly used scheduling adjustment functions
- and adapts them for (Qualcomm|MediaTek) devices

### CPU frequency range **`@cpu_freq`**
- Parameter format: **@cpu_freq [clusterExpr] [freqExpr] [freqExpr]**
- For example, to cap the little CPU cluster at 900MHz in power save mode
```json
{
  "platform": "msm8998",
  "schemes": {
    "powersave": {
      "call": [
        ["@cpu_freq", "cpu0", "min", "900MHz"]
      ]
    }
  }
}
```


#### CPU minimum frequency **`@cpu_freq_min`**
- Parameter format: **@cpu_freq_min [clusterExpr] [freqExpr]**
- For example, to set the little CPU cluster minimum to 300MHz in power save mode
```json
{
  "platform": "msm8998",
  "schemes": {
    "powersave": {
      "call": [
        ["@cpu_freq_min", "cpu0", "300MHz"]
      ]
    }
  }
}
```

#### CPU maximum frequency **`@cpu_freq_max`**
- Parameter format: **@cpu_freq_max [clusterExpr] [freqExpr]**
- For example, to cap the little CPU cluster at 900MHz in power save mode
```json
{
  "platform": "msm8998",
  "schemes": {
    "powersave": {
      "call": [
        ["@cpu_freq_max", "cpu0", "900MHz"]
      ]
    }
  }
}
```

#### Additional notes
- Frequency range conflicts
  > Suppose CPU0 is currently limited to [600MHz ~ 1.2GHz]<br>
  > Calling **`@cpu_freq_min` cpu0 1.5GHz** will definitely fail<br>
  > because 1.5GHz does not fall within [600MHz ~ 1.2GHz]<br>
  > Use **`@cpu_freq` cpu0 1.5GHz 2.0GHz** to specify the CPU frequency range directly

- About clusterExpr
  > CPU frequency is usually modified per core cluster<br>
  > For example, on a 4+3+1 Snapdragon processor, the little, middle, and big cores can be expressed in the following formats<br>
  > `cpu0`, `cpu4`, `cpu7`<br>
  > `policy0`, `policy4`, `policy7`<br>
  > `cluster0`, `cluster1`, `cluster2`<br>
  > However, never use ambiguous numbers such as `0`, `4`, `7`; they are very hard to understand

- About freqExpr
  > To make frequency notation easier, Scene supports several formats and special values<br>
  > e.g. `min` and `max` mean the minimum and maximum frequency supported by the cluster<br>
  > e.g. `1800MHz` and `1.8GHz` both equal `1800000KHz`, or simply `1800000`<br>
  > If you specify a frequency that does not exist, Scene picks the highest frequency below it<br>
  > If the specified frequency is higher than the cluster maximum, Scene picks the highest supported frequency<br>
  > If the specified frequency is lower than the cluster minimum, Scene picks the lowest supported frequency<br>

  > But be careful! Using `GHz|MHz` notation is convenient but can be a trap<br>
  > CPU frequencies are often not round numbers like 2.8GHz (2800000KHz); they are more often like 2841600KHz<br>
  > If you write 2.8GHz, it will not match 2841600KHz!<br>

  > Negative frequencies<br>
  > This is a Scene-defined notation meaning the `max` frequency minus the given value<br>
  > e.g. `-300MHz`, `-0.3GHz`, `-300000`<br>
  > If the little cluster maximum is `1800MHz`<br>
  > then **`@cpu_freq` cpu0 -1200MHz -300MHz** equals **`@cpu_freq` cpu0 600MHz 1500MHz**<br>
  > If the little cluster maximum is `2000MHz`<br>
  > then **`@cpu_freq` cpu0 -1200MHz -300MHz** equals **`@cpu_freq` cpu0 800MHz 1700MHz**

### GPU frequency range `@gpu_freq`
- Parameter format: **@gpu_freq [freqExpr] [freqExpr]**
- For example, to cap the GPU at 500MHz in power save mode
```json
{
  "platform": "msm8998",
  "schemes": {
    "powersave": {
      "call": [
        ["@gpu_freq", "min", "500MHz"]
      ]
    }
  }
}
```

#### GPU minimum frequency **`@gpu_freq_min`**
- Parameter format: **@gpu_freq_min [freqExpr]**
- For example, to set the GPU minimum frequency to 400MHz in fast mode
```json
{
  "platform": "msm8998",
  "schemes": {
    "fast": {
      "call": [
        ["@gpu_freq_min", "400MHz"]
      ]
    }
  }
}
```

#### GPU maximum frequency **`@gpu_freq_max`**
- Parameter format: **@gpu_freq_max [freqExpr]**
- For example, to cap the GPU at 300MHz in power save mode
```json
{
  "platform": "msm8998",
  "schemes": {
    "powersave": {
      "call": [
        ["@gpu_freq_max", "400MHz"]
      ]
    }
  }
}
```

#### Additional notes
- Frequency range conflicts
  Similar to CPU frequency settings, not repeated here
- About clusterExpr
  Similar to CPU frequency settings, not repeated here

#### Additional notes
- Approximate refresh rate tiers
> When possible, an exactly matching refresh rate tier is preferred<br>
> When there is no exact match, the closest tier is chosen as follows<br>

> Round up (when T >= 61, pick the lowest tier above T)<br>
> e.g. T=90 and the device only supports [60, 120, 144]<br>
>	   will hit 120

> Round down (when T < 61, pick the highest tier below T)<br>
> e.g. T=48 and the device only supports [30, 45, 60, 90, 120]<br>
>	  will hit 45<br>

> Note: T is the target refresh rate

> Debounce handling<br>
> In some cases the refresh rate is not an integer; a device's 60Hz may actually be 60.5Hz or 59.8Hz<br>
> Scene tolerates this with a maximum refresh rate error of ±2Hz

### Priority `@set_priority`
- Strictly speaking, this changes the processor's frequency scaling and big/little core migration policy, not process CPU resource preemption priority
- Parameter format: **@set_priority [group] [level]**
- `group` can be `background`, `foreground`, `top-app`, or the short forms `bg`, `fg`, `top`
- `level` has 6 tiers by aggressiveness: `min`, `low`, `normal`, `high`, `max`, `turbo`
  > Scene automatically adjusts the following parameters based on the specified `level` <br>
    **cpu.uclamp.min**,<br>
    **schedtune.boost**,<br>
    **cpuset**,<br>
    **sched_boost**,<br>
    **sched_upmigrate**,<br>
    **up_rate_limit_us**,<br>
    **schedtune.util.max** <br>
    and other parameters (depending on kernel support)

- For example, in performance mode we want the processor to be as aggressive as possible while limiting background CPU usage
- This can be configured as follows
```json
{
  "platform": "lahaina",
  "platform_name": "Snapdragon 888",
  "schemes": {
    "performance": {
      "call": [
        ["@set_priority", "top-app", "high"],
        ["@set_priority", "foreground", "normal"],
        ["@set_priority", "background", "low"]
      ]
    }
  }
}
```

- Or, in fast mode, we want frequency scaling to be very aggressive while background processes can still run normally
- This can be configured as follows
```json
{
  "platform": "lahaina",
  "platform_name": "Snapdragon 888",
  "schemes": {
    "performance": {
      "call": [
        ["@set_priority", "top-app", "max"],
        ["@set_priority", "foreground", "high"],
        ["@set_priority", "background", "normal"]
      ]
    }
  }
}
```

#### Additional notes
- Global parameters affecting frequency scaling aggressiveness are modified only when **`@set_priority` top-app [level]** is called
- `high`, `max`, and `turbo` all increase frequency scaling aggressiveness and migration of heavy tasks to big cores
  > Note: the `turbo` level unconditionally prefers big cores<br>
  > Preferring big cores can significantly improve smoothness and responsiveness under light load<br>
  > `But` in high-frame-rate and large games, single-core performance requirements are often very high;<br>
  > migrating too many tasks to the big cores may overwhelm cores that are already heavily loaded



### Realme GT mode `@realme_gt`
- Parameter format: **@realme_gt [on|off]**
- For example, to enable GT mode automatically in fast mode
```json
{
  "platform": "msm8998",
  "schemes": {
    "fast": {
      "call": [
        ["@realme_gt", "on"]
      ]
    }
  }
}
```


### Set value `@set_value`
- Parameter format: **@set_value [path] [value]**
- For example, to write a value to a given path in power save mode (the example intends to turn off CPU7)
```json
{
  "platform": "msm8998",
  "schemes": {
    "powersave": {
      "call": [
        ["@set_value", "/sys/devices/system/cpu/cpu7/online", "0"]
      ]
    }
  }
}
```

#### Additional notes
> The extended usage of `@set_value` is quite complex; if you have not encountered a scenario that needs it, you can skip this section and continue reading the other notes<br>
> Note that all special usages add special markers to [value]

- Special usage: multiple writes with the `|` symbol
> The following example modifies MTK processor frequencies through PPM<br>
> It writes `0 1991000` and `1 2025000` in two separate writes
```
"call": [
  ["@set_value", "/proc/ppm/policy/hard_userlimit_max_cpu_freq", "0 1991000|1 2025000"]
]
```

- All special usages

```
Multiple values, e.g. 123|223|323
Increase only, e.g. ^223 or >223. Note: if the current property value is 123 and value is ^122, no write is performed; if value is ^124, the write is performed
Decrease only, e.g. <123. Note: if the current property value is 123 and value is ^124, no write is performed; if value is ^122, the write is performed
Lock value, e.g. #123. Note: writes 123 to the property, then makes it read-only
Verify value, e.g. true(enabled:true). Note: if the current property value is enabled:true, no write is performed
Fuzzy verify, e.g. true(~enabled:true). Note: if the current property value contains enabled:true, no write is performed
No verification, e.g. =123. Skips comparing the current property value; the write is performed even if the current value equals value
			* The framework compares by default; writing value 111 directly equals 111(111),
			* But note: verification cannot be performed when value contains the | symbol, e.g. 1500|1700|1899 equals =1500|=1700|=1899

values special format, marker special usage
Correct example
#^223 Increase the value only and lock it
#^1600000(boost_cluster_0:1600000) Increase the value only and lock it
0 1600000|1 1400000|#1 1400000 Writes 0 1600000, 1 1400000, 1 1400000 to the property respectively, then locks it

Incorrect example
^#223 When the lock marker (#) is used with other markers, # must always come first
```

### Lock value `@lock_value`
- Parameter format: **@lock_value [path] [value]**
- For example, to write a value to a given path in power save mode (the example intends to turn off CPU7) and make the property read-only afterwards
```json
{
  "platform": "msm8998",
  "schemes": {
    "powersave": {
      "call": [
        ["@lock_value", "/sys/devices/system/cpu/cpu7/online", "0"]
      ]
    }
  }
}
```

#### Additional notes
- The locking effect of `@lock_value` is the same as `#[value]` in `@set_value`'s special usage
- `@lock_value` also supports special usage markers on [value]


## Advanced (scenes)
- Although the five basic modes cover most scenarios
- it would be even better to apply specific optimizations to specific apps, wouldn't it?

- For example:
```json
{
  "platform": "mt6893",
  "platform_name": "D1200",
  "apps": [
    {
      "friendly": "Genshin Impact",
      "scene": "Scene-For-YS",
      "packages": [
        "com.miHoYo.Yuanshen",
        "com.miHoYo.ys.mi",
        "com.miHoYo.ys.bilibili",
        "com.miHoYo.GenshinImpact"
      ],
      "call": [],
      "sensors": [],
      "affinity": {},
      "booster": {}
    }
  ]
}
```

> This example adds a scene that performs no special actions<br>
> [packages] specifies which apps this scene matches (by package name)<br>
> The [scene] property `Scene-For-YS` is a custom scene ID<br>
> [friendly] is a descriptive property mainly for readability<br>
> [call] can invoke Scene built-in functions just like the five modes above

- If you need a scene configuration that applies to all apps
- use `"packages": ["*"]` as a wildcard, e.g.:
```json
{
  "platform": "mt6893",
  "platform_name": "D1200",
  "apps": [
    {
      "friendly": "Generic",
      "scene": "Scene-For-Any",
      "packages": ["*"],
      "call": []
    }
  ]
}
```
- However, scenes are matched top to bottom, so put the wildcard scene last

### Sensors `sensors`
- Scene implements simple numeric monitoring. Note: numeric, meaning the monitored value must be a number!
- It periodically polls (reads) a given path and decides which parameters to modify based on the value

- Full usage example:
```json
{
  "friendly": "Genshin Impact",
  "scene": "Scene-For-YS",
  "packages": [
    "com.miHoYo.Yuanshen",
    "com.miHoYo.ys.mi",
    "com.miHoYo.ys.bilibili",
    "com.miHoYo.GenshinImpact"
  ],
  "sensors": [
    {
      "sensor": "/sys/devices/platform/charger/power_supply/battery/capacity",
      "logger": false,
      "disable": false,
      "interval": 5000,
      "props": [],
      "rules": [
        {
          "threshold": [-1, 15],
          "note": "[capacity] < MAX && [capacity] >= 15, Removing GPU restrictions",
          "enter_once" : [],
          "enter": [
            ["/proc/mali/dvfs_enable", "1"],
            ["/proc/gpufreq/gpufreq_opp_freq", "0"]
          ],
          "values": []
        },
        {
          "threshold": [16, -1],
          "note": "[capacity] < 16 && [capacity] >= MIN, GPU limited to 370MHz",
          "enter_once" : [],
          "enter": [
            ["/proc/mali/dvfs_enable", "0"],
            ["/proc/gpufreq/gpufreq_opp_freq", "370000"]
          ],
          "values": []
        }
      ]
    }
  ]
}
```

> This example reads the battery percentage every 5 seconds<br>
> If the percentage >= 15, restore GPU frequency<br>
> If the percentage < 16, limit GPU frequency to 370MHz<br>

> threshold is a range made of two values, evaluated as < [value1] && >= [value2], where `-1` means unlimited<br>
> interval is the polling interval in milliseconds<br>
> enter: property changes and function calls performed when the rule matches; repeated matches run repeatedly<br>
> enter_once: property changes and function calls performed when the rule matches; repeated matches run only once<br>
> note is a comment property with no effect on runtime logic<br>
> props: properties the rule will modify. *This configuration only relates to values, not enter or enter_once<br>
> values: the values corresponding to props<br>

#### Improved sensor rule syntax
- In most cases a rule modifies the same property
- so repeating properties and values in enter is verbose
- It can be simplified by specifying props once and values per rule
- For example, to change processor performance based on battery temperature:

```json
{
  "sensor": "/sys/class/power_supply/battery/temp",
  "interval": 2000,
  "props": ["$gpu_freq", "$ddr_freq_min", "$cpu_max_0", "$cpu_max_4", "$cpu_max_7"],
  "rules": [
    { "threshold": [ -1, 491], "values": ["3200000000", "1800000", "1700000", "1800000"] },
    { "threshold": [490, 471], "values": ["3200000000", "1800000", "1800000", "2000000"] },
    { "threshold": [470, 441], "values": ["4266000000", "1900000", "2000000", "2100000"] },
    { "threshold": [440,  -1], "values": ["4266000000", "2000000", "2150000", "2300000"] }
  ]
}
```



### Thread CPU affinity `affinity`
- You may have heard that most Unity games have a thread called `UnityMain` with very high CPU usage
- In most cases the kernel decides whether to migrate tasks to `Big` cores based on load
- but sometimes the system deliberately lowers big-core usage to save power
- In such cases we can manually adjust thread placement to improve game smoothness
- Scene's CPU affinity configuration format is as follows:

```json
{
  "friendly": "Genshin Impact",
  "scene": "Scene-For-YS",
  "packages": ["com.miHoYo.Yuanshen"],
  "affinity": {
    "repeat": 0,
    "interval": 5000,
    "cpuset_mode": "off",
    "unity_main": "80",
    "heavy_thread": "UnityGfx",
    "heavy_mask": "70",
    "comm": {
      "70": ["UnityMultiRende", "mali-cmar-backe"],
      "F": ["Worker Thread", "AudioTrack", "Audio"]
    },
    "other": "7f"
  }
}
```

- `other` is optional; when empty or omitted, threads whose names are not in `comm` are skipped
- `unity_main` is also optional; it specifies the affinity mask for the UnityMain thread. If multiple UnityMain threads exist, only the busiest one is matched.
- `heavy_thread` is also optional and works together with heavy_mask; it specifies the name of the heavy-load thread. If multiple threads share the name, only the busiest one is matched.
- `heavy_mask` is used together with heavy_thread to specify the heavy-load thread's affinity mask.
- `repeat` is the maximum number of affinity checks; `0` means unlimited and is the default
- `interval` is the affinity check interval in milliseconds; minimum `50`, default `5000`
- `cpuset_mode` is new in framework 130; can be coexist | always | off
> coexist: use affinity together with cpuset for stronger constraints; the two work together to make thread placement more stable <br>
> always: use cpuset instead of affinity, equivalent to automatically translating into a cpuset configuration <br>
> off: default

- Not sure what `80`, `70`, `F`, `7f` mean?
  > They are hexadecimal numbers indicating which cores are used, e.g.<br>
  > `80` in binary is `10000000`, 8 digits; now it should be clear<br>
  > `70` in binary is `1110000`, 7 digits; pad one 0 to get 8 digits: `01110000`<br>
  > `f` in binary is `1111`, 4 digits; pad four 0s to get 8 digits: `00001111`<br>

- Now you can see that `0` and `1` indicate whether a core is used
  > `80` is `10000000`, meaning `CPU7`<br>
  > `70` is `01110000`, meaning `CPU6~4`<br>
  > `f` is `00001111`, meaning `CPU3~0`


### Thread CPU core configuration `cpuset`
- It is similar to affinity: both restrict or assign the CPU cores a thread may use
- The difference is that cpuset enforces stronger constraints against the system's own affinity modifications
- Its configuration is very similar to affinity,
- but it uses notation like 0-7 for cores instead of affinity's hexadecimal masks

```json
{
  "friendly": "Genshin Impact",
  "scene": "Scene-For-YS",
  "packages": ["com.miHoYo.Yuanshen"],
  "cpuset": {
    "repeat": 0,
    "interval": 5000,
    "comm": {
      "7": ["UnityMain"],
      "4-6": ["UnityGfxDevice", "UnityMultiRende", "mali-cmar-backe"],
      "0-3": ["Worker Thread", "AudioTrack", "Audio"]
    },
    "other": "0-6"
  }
}
```

- `other` is optional; when empty or omitted, threads whose names are not in `comm` are skipped
- `unity_main` is also optional; it specifies the CPU cores the UnityMain thread may use. If multiple UnityMain threads exist, only the busiest one is matched.
- `heavy_thread` is also optional and works together with heavy_mask; it specifies the name of the heavy-load thread. If multiple threads share the name, only the busiest one is matched.
- `heavy_mask` is used together with heavy_thread to specify the CPU cores the heavy-load thread may use.
- `repeat` is the maximum number of affinity checks; `0` means unlimited and is the default
- `interval` is the affinity check interval in milliseconds; minimum `50`, default `5000`


> Note that cpuset is not a perfect replacement for affinity <br>
> For example, we may allow a thread to run on 0-6, but the system can set its affinity mask to f<br>
> As a result, the thread may keep running on little cores instead of using cores 0-6 as intended


### Boost assistance `booster`
- Scene provides boost assistance; currently only `InputDevice` monitoring is implemented as a trigger (an enhanced touch boost)
- Usage example:

```json
{
  "booster": {
    "events": ["presets"],
    "duration": 3000,
    "idle_delay": 5000,
    "enter": [
      ["/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq", "951000"]
    ],
    "exit": [
      ["/sys/devices/system/cpu/cpufreq/policy0/scaling_min_freq", "255000"]
    ]
  }
}
```
- The format is straightforward; [events] specifies the device events that trigger boosting
> `touch`, `buttons`, and `presets` are Scene's preset input events, meaning touch, button press, and both respectively;<br>
> sometimes Scene may fail to locate the input devices for `touch` and `buttons`<br>
> so you can also specify concrete input device names in [events] for Scene to monitor
- [duration] is the boost duration in milliseconds
- [enter] configures the property changes performed when entering boost; built-in functions can be called like [Call] (not recommended)
- [exit] configures the property changes performed when exiting boost; built-in functions can be called like [Call] (not recommended)
- [idle_delay] is how many milliseconds after opening the app without touch/button input before exiting boost

##### Automatic backup/restore
- Before applying the [enter] changes for boost,
- Scene tries to read and back up the current values and restores them automatically when applying [exit]
-  Changes made by calling built-in functions are not backed up/restored
- Because systems often have their own boost logic, Scene may back up incorrect values
- So, if you are willing to be diligent, keep the properties in [exit] matching those in [enter]

##### Using functions
- Both [enter] and [exit] can call Scene built-in functions like [call]
- but the framework has no automatic backup/restore for built-in function calls
- so if you make changes by calling functions in [enter]
- be sure to call the same functions in [exit] to restore the parameters, e.g.:

```json
{
  "booster": {
    "events": ["presets"],
    "duration": 3000,
    "enter": [
      ["@cpu_freq_min", "1.4Ghz"]
    ],
    "exit": [
      ["@cpu_freq_min", "300MHz"]
    ]
  }
}
```


### Per-mode scene refinement `modes`
- We want to make targeted adjustments for `Genshin Impact` and apply them to `powersave` and `balance`
- For a specific mode within a scene (powersave, balance, etc.),
- you can configure `call`, `booster`, `affinity`, `sensors`
- Example:

```json
{
  "friendly": "Genshin Impact",
  "scene": "Scene-For-YS",
  "packages": [
    "com.miHoYo.Yuanshen",
    "com.miHoYo.ys.mi",
    "com.miHoYo.ys.bilibili",
    "com.miHoYo.GenshinImpact"
  ],
  "modes": [
    {
      "mode": ["powersave", "balance"],
      "logger": false,
      "disable": false,
      "call": [],
      "affinity": {
        "comm": {
          "80": ["UnityMain"],
          "70": ["UnityGfxDevice", "UnityMultiRende"],
          "F": ["Worker Thread", "AudioTrack", "Audio"]
        },
        "other": "7f"
      },
      "booster": {
        "events": ["touch", "buttons"],
        "duration": 2000,
        "enter": [],
        "exit": []
      }
    }
  ]
}
```

#### Per-[mode] affinity configuration
- If both scene-level (`app`) and [mode]-level `affinity` configurations exist, the [mode]-level one takes precedence
- You can also configure scene-level `affinity` and override it for a specific `mode`, e.g.
```json
{
  "friendly": "Genshin Impact",
  "scene": "Scene-For-YS",
  "packages": ["com.miHoYo.Yuanshen"],
  "affinity": {
    "comm": {
      "80": ["UnityMain"],
      "40": ["UnityGfxDevice"],
      "3F": ["UnityMultiRende"],
      "F": ["Worker Thread", "AudioTrack", "Audio"]
    },
    "other": "7f"
  },
  "modes": [
    {
      "mode": ["powersave", "balance"],
      "affinity": {
        "comm": {
          "80": ["UnityMain"],
          "70": ["UnityGfxDevice", "UnityMultiRende"],
          "F": ["Worker Thread", "AudioTrack", "Audio"]
        },
        "other": "7f"
      }
    }
  ]
}
```

#### Per-[mode] sensors configuration
- If both scene-level (`app`) and [mode]-level `sensors` configurations exist, the [mode]-level one takes precedence
- You can also configure scene-level `sensors` and override it for a specific `mode`, e.g.
```json
{
  "friendly": "Genshin Impact",
  "scene": "Scene-For-YS",
  "packages": ["com.miHoYo.Yuanshen"],
  "sensors": [],
  "modes": [
    {
      "mode": ["powersave", "balance"],
      "sensors": []
    }
  ]
}
```

#### Per-[mode] booster configuration
- If both scene-level (`app`) and [mode]-level `booster` configurations exist, the [mode]-level one takes precedence
- You can also configure scene-level `booster` and override it for a specific `mode`, e.g.
```json
{
  "friendly": "Genshin Impact",
  "scene": "Scene-For-YS",
  "packages": ["com.miHoYo.Yuanshen"],
  "booster": {},
  "modes": [
    {
      "mode": ["powersave", "balance"],
      "booster": {},
    }
  ]
}
```

#### Mode wildcard
- `mode` can list multiple modes; to match all modes you do not need to list all five, just use ["*"]
```json
{
  "modes": [
    {
      "mode": ["powersave", "balance"],
      "affinity": {
      }
    },
    {
      "mode": ["*"],
      "affinity": {
      }
    }
  ]
}
```


### Utilization Clamping @uclamp
- uclamp was introduced in the Linux kernel as a replacement for schedtune
- so not all devices support this feature
- @uclamp takes at most 3 parameters and is very simple to use
- For example:
```json
["@uclamp", "0.00~max", "0.00~max", "0.00~max"]
```
- The 3 parameters correspond to cpu.uclamp.min and cpu.uclamp.max for background, foreground, and top-app in cpuctl; the two values are separated by ~

### Classic governor @classical
- Switches the specified cores' governor to `conservative` or a similar governor and sets related parameters
- Usage:
```json
["@classical", "cpu0", "70", "60", "3", "1"]
```
- The 5 parameters are [CPU core or cluster] [up_threshold] [down_threshold] [freq_step] [sampling_down_factor]
- Here [CPU core or cluster] uses the same notation as @cpufreq, supporting formats like `cpu4`, `policy4`, `cluster1`
- This function sets the conservative governor's sampling_rate to 8ms and ignore_nice_load to 0


### High refresh rate switching `@high_rate`
- Use @high_rate "on"|"off" to switch between high and low refresh rates
- This is useful on systems with poor refresh rate management, but is recommended only for devices with LTPS OLED screens
- Before using it, configure the DisplayModeID for high/low refresh rate per device under features

```json
{
  "platform": "mt6895",
  "platform_name": "D8100",
  "framework": 130,
  "features": {
    "high_rate": {
      "enable": true,
      "enable_control": "/data/local/tmp/scene_refresh_rate",
      "device_policy": [
        { "device": "rubens", "enable": true, "mode_high_rate": "1", "mode_low_rate": "0" },
        { "device": "OP5565", "enable": true, "mode_high_rate": "1", "mode_low_rate": "0" }
      ]
    }
  }
}
```

- device can be obtained via `getprop ro.product.device`
- mode_high_rate: DisplayModeID for the high refresh rate state
- mode_low_rate: DisplayModeID for the low refresh rate state
- enable_control: an extra control file that enables the @high_rate function
    > enable_control is usually fixed to /data/local/tmp/scene_refresh_rate<br>
    > If specified, you must write 1 to this file to enable the @high_rate function


### Charging control `@charge_control`
- Use @charge "suspend"|"normal" to switch between the two modes
- The function has no built-in implementation; configure the actual changes under the suspend and normal nodes

```json
{
  "platform": "mt6895",
  "platform_name": "D8100",
  "framework": 130,
  "features": {
    "charge_control": {
      "enable": true,
      "enable_control": "/data/local/tmp/scene_charge_control",
      "suspend": [
        ["$charge_limit", "15"],
        ["$night_charging", "1"]
      ],
      "normal": [
        ["$charge_limit", "0"],
        ["$night_charging", "0"]
      ]
    }
  },
  "schemes": {
    "powersave": {
      "call": [
        ["@charge", "normal"]
      ]
    }
  }
}
```

- enable_control: an extra control file that enables the @charge function
    > enable_control is usually fixed to /data/local/tmp/scene_charge_control<br>
    > If specified, you must write 1 to this file to enable the @charge function


### Presets `@preset`
- If you have common settings that must be reused, referencing presets is very convenient
- For example, here is an original configuration without presets:
```json
{
  "schemes": {
    "powersave": {
      "call": [
        ["$cpufreq", "0 450000 2000000"],
        ["$cpufreq", "4 200000 2850000"],
        ["$cpufreq", "7 500000 2850000"],
        ["@uclamp", "0.00~max", "0.00~max", "0.1~max"],
        ["@cpuset", "2-3", "0-4", "0-6", "0-7"]
      ]
    },
    "balance": {
      "call": [
        ["$cpufreq", "0 450000 2000000"],
        ["$cpufreq", "4 200000 2850000"],
        ["$cpufreq", "7 500000 2850000"],
        ["@uclamp", "0.00~max", "0.00~max", "0.1~max"],
        ["@cpuset", "0-3", "0-4", "0-6", "0-7"]
      ]
    },
  }
}
```
- As you can see, there is a lot of duplicated code. So create a preset for the repeated content, like this:
```json
{
  "presets": {
    "set_001": [
      ["$cpufreq", "0 450000 2000000"],
      ["$cpufreq", "4 200000 2850000"],
      ["$cpufreq", "7 500000 2850000"],
      ["@uclamp", "0.00~max", "0.00~max", "0.1~max"]
    ]
  },
  "schemes": {
    "powersave": {
      "call": [
        ["@preset", "set_001"],
        ["@cpuset", "2-3", "0-4", "0-6", "0-7"]
      ]
    },
    "balance": {
      "call": [
        ["@preset", "set_001"],
        ["@cpuset", "0-3", "0-4", "0-6", "0-7"]
      ]
    },
  }
}
```
- Looks much better, right? `@preset` also supports using multiple presets at once, e.g. `["@preset", "set_001", "set_002"]`


### Non-core functions
- Functions that appear in Scene's bundled profiles but are not documented usually have strict device and SoC requirements
- Such functions are not main framework features and their usage and behavior are not guaranteed to stay consistent

## Closing
- Note: all example code in this document only demonstrates framework features and is not performance optimization best practice
