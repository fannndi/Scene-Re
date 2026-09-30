# MIUI thermal configs — decrypted reference (surya)

Decrypted copies of the stock ROM's `vendor/etc/thermal-*.conf` for
**POCO X3 NFC / surya / SM7150 (soc_id 365/366, "moorea")**,
ROM `miui_SURYAIDGlobal_V14.0.2.0.SJGIDXM` (Android 12, MIUI 14).

## How they were decrypted

- Cipher: **AES-128-CBC**, key = IV = `"thermalopenssl.h"` (16 bytes).
- Reference implementation: `mi-thermal-crypt` (github, single C file;
  uses OpenSSL EVP).
- Decrypt: `mi-thermal-crypt -i thermal-normal.conf -o thermal-normal.conf.dec`
- The repo already carries a second tool (`../mi-thermal-config/miui-thermal`,
  Go) doing the same job for other devices.

Verification: `md5sum` of the decrypted `thermal-normal.conf` **matches the
live decrypted file the device's mi_thermald writes**
(`/data/vendor/thermal/decrypt.txt`, checked 2026-09-30) — so this is
exactly what the running daemon loads.

## sconfig → config mapping (`thermal-map.conf`)

| sconfig | config | label | shipped in ROM |
|---|---|---|---|
| 0 | thermal-normal.conf | Normal | ✅ |
| 1 | thermal-high.conf | High | ❌ missing |
| 2 | thermal-extreme.conf | Extreme | ❌ missing |
| 8 | thermal-phone.conf | Phone call | ✅ |
| 9 | thermal-tgame.conf | Game | ✅ |
| 10 | thermal-nolimits.conf | No limits | ✅ |
| 11 | thermal-class0.conf | Class 0 | ❌ missing |
| 12 | thermal-camera.conf | Camera | ✅ |
| 13 | thermal-tgame.conf | Game | ✅ |
| 14 | thermal-youtube.conf | YouTube | ❌ missing |
| 15 | thermal-arvr.conf | AR/VR | ✅ |
| 16 | thermal-tgame.conf | Game | ✅ |

Selecting a non-shipped value makes mi_thermald look for a missing config —
`ThermalProfiles.shipped` in the app marks the safe presets.

## Key behaviours (normal profile, verified live on device)

- `SS-CPU6`: sensor `nvm-therm-adc` ≥ **50 °C** → gold cluster capped to
  **1209600 kHz**; released at 48 °C (this is the `thermal-cpufreq-6`
  clamp the app's VerifyPolicy treats as expected).
- `MONITOR-BAT`: `quiet-therm-adc` 38–45 °C steps the battery cooling
  device (201 → 1515).
- `MONITOR-TEMP_STATE`: `quiet-therm-adc` ≥ 60 °C → writes
  `thermal_message/temp_state = 4`.
- `MONITOR-BCD`/`CCC_CTRL`: extreme cases hotplug cpu0/1 or cpu6/7 +
  backlight; BCL trips on low battery SOC.
- `nolimits` (sconfig 10) is the loosest shipped profile (cpu7 cap 1094400
  at 51 °C, boost_limit + GPU cooling active).

## Runtime notes

- mi_thermald decrypts the selected config into
  `/data/vendor/thermal/decrypt.txt` and logs to
  `/data/vendor/thermal/thermal.dump`.
- `thermal-global-mode` holds the current global mode (observed `0`).
- The kernel's own step zones (`cpu-0-x-step`, `cpu-1-x-step`, `gpu-step` in
  the DTB) remain the final hardware safety net.
- These files are reference material for the app's thermal guard
  (`ThermalController`/`ThermalService`) and diagnostics — nothing here is
  executed by the app.
