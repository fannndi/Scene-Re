# Audit Kompatibilitas & Performa — Scene vs MIUI 14 (surya / SM7150-AC)

Target: POCO X3 NFC ("surya", SM7150-AC / SD732G), MIUI 14, Android 12 (SDK 31).
Sumber ROM: `C:\Users\FANNNDI\Documents\crb_340\Projects\MIUI14\ROM`
(7.958 file; 196 `.rc`, 233 `.prop`, 379 `.xml`, 166 `.jar`, 32 `.sh`, 148 `.json`)

---

## 1. Ringkasan eksekutif

Audit menyeluruh menemukan **48 temuan** dalam tiga kelompok:

| Grup | Isi | Jumlah | Dampak utama |
| --- | --- | --- | --- |
| **A** | Mekanisme MIUI/QTI yang tersedia di ROM tetapi **belum dipakai** Scene | 20 | Peluang performa besar yang belum diambil |
| **B** | **Bug / mismatch** jalur, node, dan asumsi Scene terhadap ROM ini | 16 | Fitur diam-diam gagal (silent no-op) |
| **C** | Konfigurasi ROM yang bisa disetel ulang | 12 | Penyetelan halus + reversibilitas |

Tiga temuan **kritis** (harus diperbaiki sebelum tuning apa pun bisa dipercaya):

1. **B1 — `mount_all()` salah total di MIUI 14.** Semua partisi sistem adalah
   *logical* (dynamic partition) + AVB (`fstab.default`). `/dev/block/bootdevice/by-name/system`
   **tidak ada** di device dynamic-partition. Semua remount-rw gagal senyap.
2. **B2 — Backend `DIRECT` praktis tidak pernah aktif di MIUI 14.** Karena B1,
   `probeWritablePartition()` mengembalikan `false` → `Backend.NONE`. Janji di
   `AGENTS.md` ("bekerja dengan `su` biasa tanpa modul") **tidak terpenuhi** di
   MIUI 14 kecuali overlay ada.
3. **B3 — `ThermalDisguise.resumeMessage()` tidak memulihkan suhu.** Setelah
   "Extreme Performance" dimatikan, `board_sensor_temp` dibiarkan pada nilai palsu
   `36500` → kontrol thermal mi_thermald rusak permanen sampai reboot.

Temuan peluang terbesar (Grup A):

- **A1** `/dev/cpuset/game` + `/dev/cpuset/gamelite` — MIUI sudah membuatnya khusus
  untuk beban game; Scene tidak pernah menyentuhnya.
- **A2** `sys.sptm.gover` — saklar governor bawaan MIUI, **rusak di surya**
  (menulis `policy4`/`policy7` yang tidak ada).
- **A3** `/sys/class/thermal/thermal_message/*` — interface kontrol thermal resmi
  MIUI (`sconfig`, `cpu_limits`, `boost`, `screen_state`).
- **A4** `miuiboosterservice` + `persist.sys.mibridge_auth_uids` — API booster
  resmi MIUI (CPU/GPU/IO/Mem/Net/Thread), sepenuhnya belum dipakai.

---

## 2. Fakta ROM yang terverifikasi

Semua klaim di bawah diverifikasi langsung dari dump, bukan asumsi.

| Properti / fakta | Nilai | Sumber |
| --- | --- | --- |
| `ro.board.platform` | `sm6150` | `vendor/build.prop` |
| `ro.soc.model` | `SM7150` | `vendor/build.prop` |
| `ro.vendor.perf-hal.ver` | `2.2` → QTI perf HAL, **bukan** libperfmgr | `vendor/build.prop` |
| Target perf | `sdmmagpie` (SocIds 365,366) | `vendor/etc/perf/targetconfig.xml:60-75` |
| Topologi CPU | **2 cluster**: `policy0` = 6× little, `policy6` = 2× big | `targetconfig.xml:55-56` |
| Partisi sistem | `ext4 ro, logical, first_stage_mount`, `avb=vbmeta_system` | `vendor/etc/fstab.default` |
| Zram (fstab) | `zramsize=1073741824` (1 GiB) | `fstab.default` |
| Zram (MIUI runtime) | 4096 MB untuk device 6 GB | `system/system/etc/perfinit.conf:7` |
| `dex2oat_threads` surya | boot 6 / app 4 / bg 4 | `perfinit.conf:42-55` |
| `persist.logd.size.*` | radio 4M, system 4M, crash 1M | `system/init.miui.rc:29-31` |

> **Konsekuensi penting:** hanya ada **dua** cpufreq policy (`policy0`, `policy6`).
> `policy4` dan `policy7` **tidak ada** di surya.

### Daemon & modul MIUI yang hidup di ROM ini

| Komponen | Lokasi | Fungsi |
| --- | --- | --- |
| `mi_thermald` | `vendor/bin/mi_thermald`, `init.target.rc:91-95` | Thermal MIUI, konfigurasi terenkripsi AES |
| `miuibooster` | `system/system/xbin/miuibooster`, `miuibooster.rc` | API booster resmi (socket `miui_booster`, binder `miuiboosterservice`) |
| `Joyose` | `system/system/app/Joyose/Joyose.apk` | Game booster MIUI, penulis `thermal_message/sconfig` |
| `millet_monitor` | `init.milletmonitor.rc` | `millet_sig`/`millet_binder`/`millet_pkg` + cgroup `frozen` |
| `mqsasd` | `init.miui.rc:262-265` | Log/analitik MIUI |
| `mcd` | `init.miui.rc:155-172` | Memory controller daemon |
| PerfShielder | `miui-services.jar` (`com.miui.server.PerfShielderService`) | AIDL `IPerfShielder` |
| SPTM | `miui-services.jar` (`com.miui.server.sptm.*`) | Preload, greeze, home-animation, speed-test mode |
| powerkeeper | `miui-services.jar` (`com.miui.whetstone.PowerKeeperPolicy`) | Kebijakan daya |
| `migt`, `turbo_sched` | `system_ext_file_contexts:32,48` → `sysfs_migt` | Modul kernel Xiaomi (built-in, bukan `.ko`) |
| `gpu_plaid` | `/sys/class/gpu_plaid/plaid/game_data` | Data game GPU Xiaomi |
| `mi_reclaim`, `rtmm`, `ktrace`, `kperfevents` | `system/init.miui.rc:102-139, 295-324` | Reclaim & tracing Xiaomi |

**Catatan:** tidak ada `perfmgr.ko`, `migt.ko`, atau `turbo_sched.ko` di
`vendor/lib/modules` — semuanya built-in di kernel.

---

## 3. Grup A — Mekanisme MIUI yang bisa dieksploitasi (belum dipakai Scene)

### A1. `/dev/cpuset/game` dan `/dev/cpuset/gamelite` — **prioritas tertinggi**

- **Lokasi:** `system/init.miui.rc:67-83`
- **Fakta:** MIUI membuat dua cpuset khusus game saat `late-init`:
  ```
  mkdir /dev/cpuset/game      ; chown system system ; chmod 0660 tasks/cgroup.procs
  mkdir /dev/cpuset/gamelite  ; chown system system ; chmod 0660 tasks/cgroup.procs
  ```
  Komentar aslinya: *"put some heavy-load thread into this cpuset for performance"*
  dan *"put some light-load thread into this cpuset for battery life"*.
- **Kondisi Scene:** `powercfg-utils.sh:458-464` (`cpuset()`) hanya menulis
  `background`, `system-background`, `foreground`, `top-app`. Grep seluruh repo:
  `cpuset/game` = **0 hit**.
- **Dampak:** thread game tetap di `top-app`/`foreground`, bersaing dengan
  SystemUI/launcher di CPU yang sama. MIUI sudah menyiapkan partisi CPU
  terpisah tetapi tidak dipakai → potensi kenaikan frame-time dan jitter.
- **Rekomendasi:**
  ```sh
  # powercfg-utils.sh — tambahan
  cpuset_game() {   # $1 = cpus untuk game, $2 = cpus untuk gamelite
      [[ -d /dev/cpuset/game ]] || return 0
      write_node "$1" /dev/cpuset/game/cpus
      [[ -n "$2" ]] && write_node "$2" /dev/cpuset/gamelite/cpus
  }
  ```
  Lalu pindahkan PID game ke `game` (bukan hanya `renice`):
  ```sh
  for pid in $(pidof "$pkg"); do echo "$pid" > /dev/cpuset/game/cgroup.procs; done
  ```
  Pada `performance|fast`: `game=6-7`, `gamelite=0-5`. Pada `balance`: `game=0-7`.

### A2. `sys.sptm.gover` — saklar governor MIUI, **rusak di surya**

- **Lokasi:** `system/init.miui.rc:332-340`
  ```
  on property:sys.sptm.gover=true
      write /sys/devices/system/cpu/cpufreq/policy0/scaling_governor "performance"
      write /sys/devices/system/cpu/cpufreq/policy4/scaling_governor "performance"
      write /sys/devices/system/cpu/cpufreq/policy7/scaling_governor "performance"
  ```
- **Masalah:** di surya hanya `policy0` dan `policy6` ada (`targetconfig.xml:55-56`).
  `policy4` dan `policy7` **tidak ada** → dua dari tiga baris gagal.
  **Cluster big (`policy6`) tidak pernah ikut di-boost.**
- **Dampak:** mekanisme boost MIUI sendiri hanya separuh bekerja. Kalau Scene
  memakai saklar ini apa adanya, cluster big tetap `schedutil`.
- **Rekomendasi:** jangan bergantung pada `sys.sptm.gover`. Implementasikan
  padanannya sendiri dan **sertakan `policy6`**:
  ```sh
  sptm_gover() {   # $1 = performance|schedutil
      for p in /sys/devices/system/cpu/cpufreq/policy*; do
          [[ -d "$p" ]] || continue
          set_value "$1" "$p/scaling_governor"
      done
  }
  ```
  Lebih baik lagi: tetap set `setprop sys.sptm.gover true` **untuk kompatibilitas
  dengan framework MIUI**, lalu tambal `policy6` sendiri. `snapshot_boot_stock()`
  sudah menyimpan `scaling_governor` semua policy, jadi restore tetap aman.

### A3. `/sys/class/thermal/thermal_message/*` — interface thermal resmi MIUI

- **Lokasi:** node di-`chmod`/`chown` di `vendor/etc/init/hw/init.target.rc:65-68`;
  daftar lengkap dari string `mi_thermald`:
  | Node | Mode | Fungsi |
  | --- | --- | --- |
  | `thermal_message/sconfig` | 0664 system | Pemilih profil thermal (skenario) |
  | `thermal_message/temp_state` | 0666 system | Status level suhu |
  | `thermal_message/board_sensor_temp` | — | Suhu "board sensor" (dipakai ThermalDisguise) |
  | `thermal_message/board_sensor` | — | Nama board sensor |
  | `thermal_message/cpu_limits` | — | Batas CPU thermal |
  | `thermal_message/boost` | — | Gerbang boost thermal |
  | `thermal_message/screen_state` | — | Status layar |
  | `thermal_message/wifi_limit` | — | Batas thermal WiFi |
- **Kondisi Scene:** hanya `board_sensor_temp` (di `ThermalDisguise.kt`) dan
  `sconfig` (read-only, `kernel_probe.sh:94`). `cpu_limits`, `boost`,
  `screen_state`, `wifi_limit` = **0 hit**.
- **Dampak:** Scene menyetel thermal dengan trik kasar (chmod 000) padahal MIUI
  menyediakan interface resmi. Trik kasar merusak state (lihat B3).
- **Rekomendasi:**
  - Simpan/muat nilai asli `board_sensor_temp`, `sconfig`, `boost`, `cpu_limits`
    dalam prop `vtools.*` sebelum diubah, dan pulihkan saat toggle off.
  - Gunakan `thermal_message/boost` untuk mode hemat daya (matikan boost thermal)
    alih-alih membutakan sensor.
  - Tampilkan keempat node di `kernel_probe.sh` + Diagnostics.

### A4. `miuiboosterservice` + `persist.sys.mibridge_auth_uids` — API booster resmi

- **Lokasi:** `system/system/framework/MiuiBooster.jar`
  (`com.miui.performance.MiuiBooster`, `IMiuiBoosterManager`);
  implementasi native `system/system/xbin/miuibooster`.
- **API yang tersedia** (dari DEX + string binary):
  ```
  requestCpuHighFreq / cancelCpuHighFreq
  requestGpuHighFreq / cancelGpuHighFreq
  requestVpuHighFreq / cancelVpuHighFreq
  requestIOHighFreq  / cancelIOHighFreq
  requestIOPrefetch
  requestMemory      / cancelMemory
  requestNetwork     / cancelNetwork
  requestThreadPriority / cancelThreadPriority
  checkPermission(key) / checkPermission_key(pkg, uid, key)
  ```
- **Nama binder:** `miuiboosterservice` (konstanta `MIUIBOOSTER_SERVICE_BINDER_NAME`)
- **Soket:** `miui_booster` (dari `miuibooster.rc`)
- **Gerbang aktivasi** (dari string binary `miuibooster`):
  | Prop | Arti |
  | --- | --- |
  | `persist.sys.enable_miui_booster` | Master switch service |
  | `persist.sys.mibridge_auth_uids` | **Daftar UID yang diotorisasi** |
  | `persist.sys.mispeed_auth_uids` | Daftar UID untuk jalur MiSpeed |
  | `sys.hardcoder.registered` | Registrasi HardCoder |
- **Kondisi Scene:** 0 hit untuk `miuibooster`, `mibridge`, `mispeed`, `hardcoder`.
- **Dampak:** Scene memakai jalur `renice -20` + `ionice` RT untuk memprioritaskan
  game. Jalur itu bekerja, tetapi tidak menyentuh DVFS (frekuensi CPU/GPU/DDR),
  tidak menaikkan prioritas thread render, dan tidak melakukan I/O prefetch.
  `MiuiBooster` melakukan semuanya lewat jalur resmi yang sudah disetel MIUI.
- **Rekomendasi:** tambahkan jalur opsional `MiuiBooster` sebagai pelengkap
  (bukan pengganti) `renice`:
  ```sh
  # addin/miui_booster.sh — probe + otorisasi
  miui_booster_supported() {
      [[ "$(getprop persist.sys.enable_miui_booster)" = "true" ]] || return 1
      [[ -e /system/system/framework/MiuiBooster.jar ]] || [[ -e /system/framework/MiuiBooster.jar ]] || return 1
      return 0
  }
  # otorisasi UID Scene sekali (butuh root, reversibel)
  miui_booster_authorize() {
      local uid="$1" cur
      cur="$(getprop persist.sys.mibridge_auth_uids)"
      case ",$cur," in *",$uid,"*) return 0 ;; esac
      setprop persist.sys.mibridge_auth_uids "${cur:+$cur,}$uid"
  }
  ```
  Di sisi Java, panggil `IMiuiBoosterManager` lewat `ServiceManager.getService("miuiboosterservice")`
  secara reflektif (pola yang sama dengan `QtiPerfHints` yang sudah ada), dan
  selalu `cancel*` saat game keluar agar tidak ada boost bocor.
- **Risiko:** mengubah `persist.sys.mibridge_auth_uids` memperluas otorisasi API
  sistem. Batasi ke UID Scene saja, catat nilai asli, dan sediakan tombol undo.
  Bila tidak nyaman, cukup pakai A6 (perf hints) yang sudah tersedia.

### A5. Joyose / `MiPlatformAppBoosterManager`

- **Lokasi:** `system/system/app/Joyose/Joyose.apk`; di framework:
  `com.xiaomi.joyose.MiPlatformAppBoosterManager`, `IMiGameBoosterCallback`,
  `com.miui.gamebooster.action.ACCESS_MAINACTIVITY`.
- **Node & prop yang dipakai Joyose** (string DEX):
  ```
  /sys/class/thermal/thermal_message/sconfig
  /sys/class/thermal/thermal_message/board_sensor_temp
  /sys/module/migt/parameters/glk_freq_limit_walt
  /sys/class/gpu_plaid/plaid/game_data
  /sys/devices/system/cpu/core_ctl_isolated
  /proc/sys/walt/sched_group_upmigrate
  /proc/sys/walt/sched_group_downmigrate
  sys.thermal.pl.enable
  ```
- **Dampak:** Joyose adalah "competitor" Scene — ia menulis ulang `sconfig`,
  `glk_freq_limit_walt`, dan `sched_group_*` (via `/proc/sys/walt`, **path berbeda**
  dari yang dipakai Scene!). Setiap tuning Scene bisa ditimpa Joyose.
- **Rekomendasi:** (a) tambahkan `/proc/sys/walt/*` ke daftar node yang ditulis
  Scene **selain** `/proc/sys/kernel/*`, dengan urutan tulis Scene **setelah**
  Joyose; (b) expose `sys.thermal.pl.enable` sebagai opsi; (c) dokumentasikan di
  Diagnostics bahwa Joyose aktif dan bisa menimpa.

### A6. Katalog lengkap perf hint QTI (`perfboostsconfig.xml`)

- **Lokasi:** `vendor/etc/perf/perfboostsconfig.xml`
- **Hint yang tersedia untuk `Target="sdmmagpie"`:**

  | Id | Type | Timeout | Nama |
  | --- | --- | --- | --- |
  | `0x00001080` | 1 | — | app launch boost |
  | `0x00001080` | 2 | — | — |
  | `0x00001080` | 4 | 80 ms | Pre-Fling boost |
  | `0x00001081` | 1 | 2000 ms | app launch |
  | `0x00001081` | 2 | 1500 ms | — |
  | `0x00001081` | 3 | 15000 ms | Launch boost v3 |
  | `0x00001081` | 4 | 15000 ms | **gameBoost** (dipakai Scene) |
  | `0x00001081` | 5 | 2000 ms | Adaptive Launch boost |
  | `0x00001081` | 6 | 2000 ms | — |
  | `0x00001082` | 1 | 400 ms | — |
  | `0x00001087` | 1 | **0 (tanpa batas)** | **Drag boost** |
  | `0x00001090` | — | 1000 ms | — |

- **Kondisi Scene:** `QtiPerfHints.kt` hanya memakai `0x1081` / Type 4.
- **Dampak:** `0x1087` Type 1 punya `Timeout="0"` = boost **tanpa batas waktu**.
  Untuk mode `fast`/`pedestal` ini jauh lebih kuat daripada gameBoost 15 detik
  yang harus di-refresh terus.
- **Rekomendasi:** tambahkan konstanta dan metode baru di `QtiPerfHints`:
  ```kotlin
  private const val VENDOR_HINT_DRAG = 0x00001087   // Type 1, Timeout=0
  private const val VENDOR_HINT_PRE_FLING = 0x00001080 // Type 4, 80 ms
  ```
  Panggil `perfHint(VENDOR_HINT_DRAG, pkg, 1, 0)` saat masuk mode `fast`, dan
  **wajib** panggil `perfHint(..., -1, 0)` atau `release()` saat keluar — boost
  tanpa timeout akan bocor dan menghabiskan baterai kalau tidak dilepas.

### A7. `GameOptimizationFeature.xml` — DDR floor & FPS meter

- **Lokasi:** `vendor/etc/lm/GameOptimizationFeature.xml`
- **Nilai:** `MIN_DDR_FREQ = 1144`, `MAX_MIN_DDR_FREQ = 2086`,
  `FPS_NODE = /sys/class/drm/sde-crtc-0/measured_fps`,
  `FPS_PERIODICITY_NODE = /sys/class/drm/sde-crtc-0/fps_periodicity_ms`.
- **Kondisi Scene:** `scene_qualcomm_boost.sh:224-249` (`apply_ddr_floor`) sudah
  memakai "middle OPP" dan mengomentari 1144 MHz — **benar** — tetapi:
  - mengabaikan plafon `MAX_MIN_DDR_FREQ = 2086`;
  - `mid_freq()` mengambil OPP **tengah** tabel, bukan 1144 secara eksplisit.
    Untuk tabel DDR surya `1144 1720 2086 2929 3879 5931 6881` (dari
    `init.qcom.post_boot.sh:3674`), OPP tengah = 2086 → hasilnya **lebih tinggi**
    dari maksud MIUI.
- **Dampak:** DDR di-floor terlalu tinggi → konsumsi daya naik tanpa manfaat FPS
  yang sepadan pada game ringan.
- **Rekomendasi:** jadikan 1144 sebagai floor dan 2086 sebagai plafon:
  ```sh
  MIUI_DDR_FLOOR=1144
  MIUI_DDR_FLOOR_MAX=2086
  ```
  dan clamp `mid` ke `[MIUI_DDR_FLOOR, MIUI_DDR_FLOOR_MAX]` sebelum menulis.
  Tambahan: pakai `measured_fps` untuk memutuskan apakah floor dilepas.

### A8. `/sys/module/turbo_sched/parameters`

- **Lokasi:** dilabeli `u:object_r:sysfs_migt:s0` di `system_ext_file_contexts:48`;
  dirujuk `miui-framework.jar` dan `miui-services.jar`.
- **Kondisi Scene:** 0 hit.
- **Dampak:** modul scheduler Xiaomi ini tidak dimanfaatkan sama sekali.
- **Rekomendasi:** enumerasi isi direktori saat runtime di `kernel_probe.sh`
  (read-only, aman), lalu ekspos hanya parameter yang terbukti menguntungkan.
  **Jangan** menulis buta — parameter tidak terdokumentasi.

### A9. `/sys/module/migt/parameters/mi_viptask` dan `glk_freq_limit_walt`

- **Lokasi:** `mi_viptask` dari `miui-services.jar`; `glk_freq_limit_walt` dari
  string Joyose.
- **Kondisi Scene:** `ThermalDisguise.kt:10` hanya menulis `glk_maxfreq`
  (`echo 0 0 0`); `mi_viptask` dan `glk_freq_limit_walt` = 0 hit.
- **Dampak:** `glk_freq_limit_walt` adalah **batas frekuensi WALT** yang dipakai
  Joyose. Selama node ini masih berisi batas, tuning frekuensi Scene di atas
  batas itu akan dipangkas.
- **Rekomendasi:** baca nilai `glk_freq_limit_walt` di Diagnostics; kalau bukan 0,
  tampilkan peringatan bahwa Joyose memasang batas WALT.

### A10–A20 — Ringkas

| # | Mekanisme | Lokasi | Dampak | Rekomendasi |
| --- | --- | --- | --- | --- |
| A10 | `/sys/class/gpu_plaid/plaid/game_data` | string Joyose; label `sys_game_plaid_file` | Data profil GPU per-game Xiaomi tidak dibaca | Probe read-only; jadikan indikator "MIUI game mode aktif" |
| A11 | `/sys/devices/system/cpu/core_ctl_isolated` | string Joyose | Inti terisolasi tidak terdeteksi → cap frekuensi Scene salah hitung | Tambah ke `kernel_probe.sh`; kalau >0, kurangi jumlah inti yang di-`online`-kan |
| A12 | `persist.sys.miui_mi_fluency.thermal_break` (+ `enabled`, `boost_gpu`, `compact_top`, `limit_top`) | `miui-services.jar` | Jalur "thermal break" resmi MIUI untuk kelancaran, tidak dipakai | Ekspos `thermal_break` sebagai opsi eksplisit; snapshot & restore |
| A13 | `persist.sys.miui_sptm.*`, `persist.sys.smartpower.*` | `miui-services.jar` | Strategi preload/SPTM MIUI tidak bisa disetel dari Scene | Snapshot + ekspos subset aman (`sptm.enable`, `sptm.min_free`) |
| A14 | `/proc/sys/walt/*` (`sched_conservative_pl`, `sched_coloc_busy_hyst_cpu_ns`, dll.) | `vendor/etc/perf/commonresourceconfigs.xml` | Scene hanya menulis `/proc/sys/kernel/*`; separuh tunable WALT terlewat | Tulis keduanya (guarded) — lihat juga A5 |
| A15 | Colocation v3: `sched_little_cluster_coloc_fmin_khz=740000` | `init.qcom.post_boot.sh:3727`; `init.target.rc:42-46` | Scene tidak menyentuh colocation sama sekali | Naikkan ke 1248000 saat `performance/fast`; pulihkan ke 740000 |
| A16 | `/proc/irq/default_smp_affinity=3f` | `init.qcom.post_boot.sh:3581` | IRQ diarahkan ke 6 little core; saat game IRQ ikut berebut | Set `ff` (semua core) saat game, `3f` saat idle — snapshot dulu |
| A17 | `/sys/kernel/mi_reclaim/event`, `/sys/kernel/mm/rtmm/*`, `/sys/kernel/ktrace/*` | `init.miui.rc:102-139, 321-324` | Reclaim/tracing Xiaomi tidak bisa dikendalikan | Probe; matikan ktrace saat game (IO trace = overhead) |
| A18 | `millet_monitor` + `/sys/fs/cgroup/frozen` | `init.milletmonitor.rc` | Pembekuan proses MIUI tidak dimanfaatkan/dihindari | Jangan pernah memasukkan PID game ke `frozen`; verifikasi `cgroup.procs` game bersih |
| A19 | PerfShielder (`IPerfShielder`) | `miui-services.jar` | API perf AIDL MIUI tidak dipakai | Alternatif A4 bila otorisasi UID ditolak |
| A20 | `thermal_message/temp_state` (0666) | `init.target.rc:67-68`; `thermald-devices.conf:28-34` | Node bisa ditulis siapa saja; level thermal tidak dibaca | Baca untuk guard thermal adaptif (gantikan `ThermalPid` berbasis suhu baterai saja) |

---

## 4. Grup B — Bug & mismatch kompatibilitas di Scene

### B1. `mount_all()` memakai jalur partisi yang tidak ada — **KRITIS**

- **Lokasi:** `app/src/main/assets/kr-script/common/mount.sh:5-30`
  ```sh
  $BUSYBOX mount -o remount,rw /dev/block/bootdevice/by-name/system /system 2> /dev/null
  ...
  mount -o rw,remount /dev/block/bootdevice/by-name/vendor /vendor 2> /dev/null
  ```
- **Fakta ROM:** `vendor/etc/fstab.default` menandai `system`, `system_ext`,
  `product`, `vendor` dengan flag **`logical,first_stage_mount`** dan
  `avb=vbmeta_system`. Artinya keempatnya adalah **dynamic partition** di dalam
  `/dev/block/bootdevice/by-name/super`. `by-name/system` **tidak dibuat**.
- **Dampak:** setiap remount lewat `by-name/*` gagal. Karena semua error di-`2> /dev/null`,
  kegagalan tidak terlihat. `write_target_for()` mengembalikan jalur langsung
  padahal tidak writable → **semua penulisan file sistem gagal senyap**.
- **Rekomendasi:** tulis ulang `mount_all()` agar sadar dynamic partition:
  ```sh
  mount_all() {
      # 1) coba jalur sederhana (device lama / non-dynamic)
      for m in / /system /system_ext /product /vendor; do
          mount -o rw,remount "$m" 2> /dev/null && continue
          $BUSYBOX mount -o rw,remount "$m" 2> /dev/null
      done

      # 2) dynamic partition: remount lewat /dev/block/mapper
      if [[ -e /dev/block/mapper/system ]]; then
          for part in system system_ext product vendor; do
              [[ -e "/dev/block/mapper/$part" ]] || continue
              mount -o rw,remount "/dev/block/mapper/$part" "/$part" 2> /dev/null
          done
      fi

      # 3) AVB/verity masih memblokir? laporkan, jangan diam
      if ! touch /system/.scene_rw_probe 2> /dev/null; then
          echo "scene: /system tetap read-only (dm-verity/AVB aktif)" 1>&2
      else
          rm -f /system/.scene_rw_probe
      fi
  }
  ```
  Untuk device AVB, remount-rw hanya bisa setelah verifikasi dinonaktifkan
  (`avbctl disable-verification` / vbmeta patched). Scene harus **melaporkan ini
  secara eksplisit** di Diagnostics, bukan gagal diam-diam.

### B2. Backend `DIRECT` tidak pernah aktif di MIUI 14 — **KRITIS**

- **Lokasi:** `common/src/main/java/com/omarea/common/shared/RootBackend.java:118-133`
  (`probeWritablePartition`) dan `:158-169` (`resolve`).
- **Rantai sebab:** B1 → `mount -o rw,remount /system` gagal → `touch /system/.scene_rw_probe`
  gagal → `probeWritablePartition()` = `false`. Karena `findOverlayRoot()` juga
  `null` saat tidak ada modul, hasilnya `Backend.NONE`.
- **Dampak:** klaim `AGENTS.md` — *"DIRECT … membuat aplikasi bekerja dengan `su`
  biasa dan tanpa framework modul"* — **tidak berlaku di MIUI 14**. Semua fitur
  override file mati; hanya fitur runtime (sysfs/prop) yang jalan.
- **Rekomendasi:**
  1. Perbaiki B1 lebih dulu.
  2. Tambahkan deteksi penyebab ke `backendDescription()`: bedakan
     "tidak ada overlay" vs "partisi tidak writable karena verity".
  3. Tambahkan fallback **tanpa tulis partisi**: karena mayoritas fitur Scene
     adalah sysfs/prop (bukan file), pastikan `supported()` tidak dijadikan
     gerbang untuk fitur runtime. Audit semua pemanggil `RootBackend.supported()`.

### B3. `ThermalDisguise.resumeMessage()` meninggalkan suhu palsu — **KRITIS**

- **Lokasi:** `app/src/main/java/com/omarea/library/shell/ThermalDisguise.kt:24-43`
  ```kotlin
  fun disableMessage() { ... "echo 36500 > $boardSensorTemp\n" ... }
  fun resumeMessage()  { ... "chmod 644 $boardSensorTemp\n" ... }   // nilai TIDAK dipulihkan
  ```
- **Dampak:** setelah toggle dimatikan, `board_sensor_temp` tetap `36500`. mi_thermald
  membaca nilai itu sebagai suhu board → keputusan thermal salah sampai reboot.
  Baterai bisa panas tanpa penanganan.
- **Rekomendasi:** snapshot nilai asli sebelum menimpa, pulihkan saat resume:
  ```kotlin
  private val backupProp = "vtools.thermal.disguise.bak"
  private val disabledProp = "vtools.thermal.disguise"

  fun disableMessage() {
      KeepShellPublic.doCmdSync(
          "if [ \"\$(getprop $backupProp)\" = \"\" ]; then " +
          "  setprop $backupProp \"\$(cat $boardSensorTemp 2>/dev/null)\"; fi\n" +
          "chmod 644 $boardSensorTemp\n" +
          "echo 36500 > $boardSensorTemp\n" +
          "chmod 000 $boardSensorTemp\n" +
          "chmod 644 $migtMaxFreq\n" +
          "echo 0 0 0 > $migtMaxFreq\n" +
          "pm disable $gameService\n" +
          "pm clear $gameServiceApp\n" +
          "setprop $disabledProp 1")
  }

  fun resumeMessage() {
      KeepShellPublic.doCmdSync(
          "chmod 644 $boardSensorTemp\n" +
          "v=\"\$(getprop $backupProp)\"\n" +
          "[ -n \"\$v\" ] && echo \"\$v\" > $boardSensorTemp\n" +
          "setprop $backupProp \"\"\n" +
          "pm enable $gameService\n" +
          "setprop $disabledProp 0")
  }
  ```

### B4. `chmod 000` pada node sysfs tidak andal

- **Lokasi:** `ThermalDisguise.kt:28`
- **Analisis:** `board_sensor_temp` adalah atribut kernfs/sysfs. Permission-nya
  ditentukan kernel saat pembuatan; `kernfs` **tidak mengimplementasikan
  `setattr`**, sehingga `chmod` pada sysfs umumnya gagal `EPERM`. MIUI sendiri
  hanya melakukan `chmod` di `init.target.rc` (saat init, di mana init punya
  konteks berbeda) — bukan di runtime.
- **Dampak:** kalau `chmod 000` gagal, mi_thermald tetap bisa membaca `36500`.
  Nilai 36500 adalah sentinel "sangat panas" bagi sebagian implementasi → bisa
  memicu **throttling maksimum**, kebalikan dari yang diinginkan.
  `isDisabled()` juga bergantung pada `ls -l` yang menampilkan `----------`,
  sehingga deteksi status ikut salah.
- **Rekomendasi:** verifikasi hasil `chmod` (`echo $?`), jangan mengandalkan
  keberhasilan. Kalau gagal, gunakan A3 (`thermal_message/boost`) sebagai jalur
  resmi, dan perbaiki `isDisabled()` agar memeriksa nilai node, bukan mode string.

### B5. Gating `ThermalDisguise.supported()` terlalu sempit

- **Lokasi:** `ThermalDisguise.kt:14-22` — hanya `Build.MANUFACTURER == "XIAOMI"`.
- **Dampak:** device Xiaomi tanpa `mi_thermald` (atau sebaliknya, ROM AOSP di
  device Xiaomi) akan menampilkan tombol yang tidak berfungsi.
- **Rekomendasi:** tambahkan cek `getprop init.svc.mi_thermald` = `running`
  (atau `pidof mi_thermald`), karena tanpa daemon-nya node itu tidak berefek.

### B6. `zram_control.sh` mengecilkan zram MIUI

- **Lokasi:** `addin/zram_control.sh:27` → `disksz_mb=768`, `:44` → `algorithm=lzo`
- **Fakta ROM:** MIUI menetapkan zram 4096 MB untuk device 6 GB
  (`perfinit.conf:7`) dan fstab meminta 1 GiB sebagai baseline.
- **Dampak:** Scene memotong zram dari 4096 MB → 768 MB (≈ −80%) dan memaksa
  `lzo` (rasio lebih buruk dari `lz4`/`zstd`). Dengan MIUI yang agresif membekukan
  proses (millet) dan LMK kustom, ini menaikkan risiko OOM-kill.
- **Rekomendasi:** jangan pernah **mengecilkan**; hanya boleh memperbesar. Baca
  dulu nilai MIUI, dan pilih algoritma terbaik dari
  `/sys/block/zram0/comp_algorithm`:
  ```sh
  want="$(getprop ro.config.zram.size_mb)"; [[ -z "$want" ]] && want=4096
  cur=$(( $(cat /sys/block/zram0/disksize 2>/dev/null || echo 0) / 1048576 ))
  [[ "$cur" -ge "$want" ]] && return 0     # sudah cukup besar, jangan sentuh
  ```
  Algoritma: pilih `zstd` → `lz4` → `lzo` sesuai `comp_algorithm`.

### B7. LMK: Scene menulis node, MIUI menulis prop

- **Lokasi Scene:** `powercfg-base.sh:85` (`enable_adaptive_lmk`),
  `powercfg-utils.sh` (`minfree` tidak disetel, tapi `enable_adaptive_lmk` ya).
- **Fakta ROM:** MIUI punya lmkd kustom dengan prop sendiri
  (`persist.sys.minfree_def`, `persist.sys.minfree_6g`, `persist.sys.minfree_8g`,
  `persist.sys.lmk.camera_minfree_levels`, `sys.lmkd.*`, `persist.sys.mms.*`).
  `init.qcom.post_boot.sh` juga memanggil `configure_memory_parameters`.
- **Dampak:** menulis `/sys/module/lowmemorykiller/parameters/*` langsung akan
  ditimpa lmkd MIUI saat prop berubah. Sebaliknya, mengubah prop MIUI efektif
  permanen.
- **Rekomendasi:** untuk LMK, tulis **prop** MIUI (dengan snapshot), bukan node:
  ```sh
  miui_lmk_profile() {   # $1 = def|6g|8g, $2 = nilai
      local p="persist.sys.minfree_${1}"
      [[ "$(getprop vtools.scene.lmk.bak.$1)" = "" ]] && \
          setprop "vtools.scene.lmk.bak.$1" "$(getprop $p)"
      setprop "$p" "$2"
  }
  ```
  Pertahankan penulisan node hanya untuk kernel non-MIUI.

### B8. `cpuset()` tidak mengenal cpuset MIUI

- **Lokasi:** `powercfg-utils.sh:458-464`; pemakaian di `active.sh:51,64,80,94,108,122`
  dan `conservative.sh`.
- **Dampak:** lihat A1. Selain itu, `active.sh:51` menulis
  `system-background = 0-3`, sedangkan MIUI memakai `0-5`
  (`init.qcom.post_boot.sh:3693`). Membatasi lebih ketat dari platform dapat
  memperlambat pekerjaan latar (dex2oat, sync) → jeda UI.
- **Rekomendasi:** tambahkan parameter cpuset game ke `cpuset()`, dan
  **jangan menyempitkan** `system-background` di bawah nilai platform.

### B9. `snapshot_boot_stock()` hanya berjalan pada `action=init`

- **Lokasi:** `powercfg-base.sh:27-29`; `active.sh:23-26` (init) — `powercfg-base.sh`
  hanya di-`source` saat `action=init`.
- **Dampak:** kalau pengguna menerapkan profil sebelum aksi `init` (mis. setelah
  wipe data, atau aplikasi dipasang lalu profil langsung dijalankan), snapshot
  tidak ada → `restore_boot_stock()` jadi no-op → mode "off" **tidak**
  mengembalikan nilai stok. Ini bug reversibilitas yang serius.
- **Rekomendasi:** panggil `snapshot_boot_stock` secara defensif di awal
  `active.sh`/`conservative.sh` (bukan hanya saat `init`), dan tambahkan
  `getprop vtools.stock.ready` ke Diagnostics.

### B10. `set_cpu_freq()` menulis sentinel ke node milik perf HAL

- **Lokasi:** `powercfg-utils.sh:345-346`
  ```sh
  write_node "0:4294967295 1:4294967295 ..." /sys/module/msm_performance/parameters/cpu_max_freq
  ```
- **Fakta ROM:** `commonsysnodesconfigs.xml` memetakan `cpu_max_freq` ke
  `Idx="0x2"` — node yang **sama** dipakai QTI perf HAL v2.2 untuk opcode
  `CPUBOOST_MAX_FREQ`.
- **Dampak:** menulis sentinel "tanpa batas" melepas batas yang dipasang HAL.
  Sebaliknya, HAL dapat menimpa nilai Scene kapan saja. Hasilnya tidak
  deterministik, dan sulit didiagnosis.
- **Rekomendasi:** jangan pakai node ini untuk membatalkan batas. Biarkan HAL
  yang mengelola `cpu_max_freq`, dan setel batas Scene lewat
  `scaling_max_freq` (yang sudah dilakukan). Tambahkan catatan di Diagnostics
  bahwa `msm_performance` dikelola HAL.

### B11. `ufshc_perf()` menonaktifkan power saving tanpa peringatan

- **Lokasi:** `powercfg-utils.sh:354-377`
- **Dampak:** `hibern8_on_idle_enable=0` + `clkgate_enable=0` menaikkan konsumsi
  idle secara permanen selama profil `performance/fast` aktif. Pada MIUI yang
  sudah agresif soal baterai, ini bisa memicu `powerkeeper` turun tangan.
- **Rekomendasi:** pisahkan `clkscale` (aman, murni performa) dari
  `clkgate`/`hibern8` (hemat daya). Aktifkan `clkgate`/`hibern8` hanya saat
  game benar-benar berjalan (`SCENE_GAME_PKG` tidak kosong), dan pulihkan saat
  game keluar.

### B12. Probe `perfmgr` mati

- **Lokasi:** `kernel_probe.sh:121`
- **Fakta:** tidak ada `perfmgr.ko` di `vendor/lib/modules`, dan `perfmgr` tidak
  dirujuk di seluruh ROM.
- **Dampak:** `xiaomi.perfmgr` selalu `0` — membingungkan di Diagnostics.
- **Rekomendasi:** ganti dengan probe yang relevan: `xiaomi.migt`,
  `xiaomi.turbo_sched`, `xiaomi.mi_thermald`, `miui.booster`, `miui.joyose`.

### B13. Path `sched_group_*` ganda (`/proc/sys/kernel` vs `/proc/sys/walt`)

- **Lokasi Scene:** `scene_profile_options.sh:500-508`, `powercfg-base.sh:50-51`
  → `/proc/sys/kernel/sched_group_{up,down}migrate`.
- **Lokasi MIUI:** Joyose menulis `/proc/sys/walt/sched_group_{up,down}migrate`;
  `commonresourceconfigs.xml` memuat **kedua** keluarga path.
- **Dampak:** kalau kernel mengekspos keduanya sebagai alias, tidak masalah.
  Kalau tidak, tuning Scene tidak berpengaruh pada jalur yang dibaca Joyose.
- **Rekomendasi:** tulis keduanya secara guarded (`[[ -e node ]] && ...`), dan
  tampilkan mana yang benar-benar ada di Diagnostics.

### B14. Tidak ada pemulihan cpuset/stune ke nilai MIUI saat mode "off"

- **Lokasi:** `powercfg-utils.sh:140-223` (`stock_paths`) — daftar snapshot
  **tidak memuat** `/dev/cpuset/foreground/boost/cpus`? (ada) tetapi **tidak
  memuat** `/dev/cpuset/game*`, `/dev/stune/*/schedtune.colocate`,
  `/proc/irq/default_smp_affinity`, `sched_little_cluster_coloc_fmin_khz`,
  `/proc/sys/walt/*`, dan seluruh node `thermal_message/*`.
- **Dampak:** semua nilai platform itu diubah permanen dan tidak kembali saat
  mode "off"/restore.
- **Rekomendasi:** tambahkan ke `stock_paths()`:
  ```sh
  for p in /dev/cpuset/game /dev/cpuset/gamelite /dev/cpuset/vr; do
      [[ -d "$p" ]] && { echo "$p/cpus"; }
  done
  echo /dev/stune/top-app/schedtune.colocate
  echo /dev/stune/foreground/schedtune.colocate
  echo /proc/irq/default_smp_affinity
  echo /proc/sys/kernel/sched_little_cluster_coloc_fmin_khz
  for p in /proc/sys/walt/*; do [[ -f "$p" ]] && echo "$p"; done
  for n in sconfig temp_state boost cpu_limits; do
      echo "/sys/class/thermal/thermal_message/$n"
  done
  ```
  **Peringatan:** `stock_paths()` harus deterministik (indeks → node). Iterasi
  glob (`/proc/sys/walt/*`) melanggar aturan itu — urutkan eksplisit atau
  sertakan hanya node yang sudah diketahui namanya.

### B15. `SCENE_GAME_DDR_FLOOR` mengabaikan plafon MIUI

Lihat A7.

### B16. Tidak ada penanganan SELinux untuk `sysfs_migt`

- **Lokasi:** `CommonCmds.kt:24-25` hanya mendefinisikan `setenforce 0/1`;
  **tidak ada** pemanggilan di skrip mana pun (0 hit di `app/src/main/assets`).
- **Fakta ROM:** `system_ext_sepolicy.cil:1286-1288, 1529-1537` — `sysfs_migt`
  hanya di-`allow` untuk `joyose_app`, `system_app`, `mcd`, `system_server`.
  `/sys/module/migt` dan `/sys/module/turbo_sched` keduanya `sysfs_migt`.
- **Dampak:** `ThermalDisguise.disableMessage()` menulis `glk_maxfreq`
  (`sysfs_migt`). Di bawah SELinux enforcing dengan domain root yang tidak
  di-`allow`, penulisan **ditolak**. Karena tidak ada pengecekan hasil,
  kegagalan tidak terlihat. Hal yang sama berlaku untuk
  `/sys/module/turbo_sched/*`.
- **Rekomendasi:** jangan matikan SELinux global. Sebaliknya:
  1. Deteksi hasil: baca kembali node setelah menulis; kalau tidak berubah,
     laporkan sebagai "diblokir SELinux".
  2. Sediakan jalur alternatif lewat `thermal_message/*` (A3) yang punya label
     berbeda dan biasanya boleh ditulis root.
  3. Dokumentasikan bahwa modul Magisk/KernelSU dengan sepolicy tambahan
     diperlukan untuk `sysfs_migt` di MIUI 14.

---

## 5. Grup C — Konfigurasi ROM yang bisa disetel

| # | Lokasi | Nilai sekarang | Dampak | Rekomendasi |
| --- | --- | --- | --- | --- |
| C1 | `vendor/etc/perf/powerhint.xml` | Hanya hint kamera + qvr untuk `msmsteppe`/`sdmmagpie` | Tidak ada hint game/scroll khusus surya | Bisa ditambah blok `<Config Target="sdmmagpie">` baru lewat overlay; risikonya sedang (file di-parse HAL saat boot) |
| C2 | `vendor/etc/perf/perfconfigstore.xml:30` | `vendor.perf.gestureflingboost.enable=true` | Boost setiap fling → konsumsi daya | Matikan saat mode `powersave` via `resetprop` (bukan edit file) |
| C3 | `perfconfigstore.xml:22-27` | `vendor.iop.enable_iop=0`, `enable_uxe=0`, `enable.prefetch=false` | I/O prefetch QTI mati | Nyalakan `vendor.iop.enable_prefetch_ofr` saat mode `fast` untuk mempercepat pembacaan aset game |
| C4 | `perfconfigstore.xml:32` | `ro.lmk.enable_userspace_lmk=false` | LMK userspace mati; MIUI pakai lmkd sendiri | Biarkan; jangan diubah tanpa uji OOM |
| C5 | `vendor/etc/thermal-*.conf` (8 file) | Terenkripsi AES, header `87775749922a2a43…` | Profil thermal tidak bisa dibaca/diubah dari luar | **Jangan** dekripsi ulang (aturan scope: editor thermal MIUI sudah dihapus). Cukup kendalikan lewat `thermal_message/sconfig` |
| C6 | `/data/vendor/thermal/thermal-global-mode`, `/data/vendor/thermal/config/` | Dipakai mi_thermald | Mode thermal global | Baca saja di Diagnostics untuk transparansi |
| C7 | `system/system/etc/perfinit.conf:7` | `zram_size` 6 GB → 4096 MB | Lihat B6 | Selaraskan `zram_control.sh` |
| C8 | `perfinit.conf:42-55` | surya: `dex2oat` 4 / `boot` 6 / `bg` 4 | Thread dex2oat | Naikkan `boot_dex2oat_threads` ke 8 saat pertama boot pasca-update untuk mempercepat instal |
| C9 | `system/init.miui.rc:29-31` | `persist.logd.size.*` 4M/4M/1M | Log buffer besar = I/O terus-menerus | Turunkan ke 1M/1M/512K saat mode hemat daya (reversibel, butuh restart logd) |
| C10 | `vendor/etc/init/hw/init.target.rc:130-139` | cpuset awalnya `0-3`, lalu `0-7` | Nilai platform | Pakai sebagai baseline restore, bukan untuk ditimpa |
| C11 | `init.qcom.post_boot.sh:3748-3751` | `input_boost "0:1324800"` ms 120; powerkey `4:1804800 7:2208000` | Boost input QTI | Scene memakai nilai berbeda; samakan agar tidak saling menimpa |
| C12 | `init.qcom.post_boot.sh:3674` | Zona bw_hwmon DDR: `1144 1720 2086 2929 3879 5931 6881` | Tabel OPP DDR sebenarnya | Jadikan sumber tunggal untuk `apply_ddr_floor` (A7) |

---

## 6. Matriks prioritas

| Prioritas | Temuan | Alasan |
| --- | --- | --- |
| **P0 — rusak** | B1, B2, B3, B9 | Fitur gagal senyap / state sistem ditinggalkan tidak konsisten |
| **P1 — peluang besar** | A1, A2, A6, A7, A15 | Kenaikan FPS/kelancaran langsung, risiko rendah |
| **P2 — integrasi MIUI** | A3, A4, A5, A12, A13 | Memakai jalur resmi, menghindari pertempuran dengan Joyose |
| **P3 — koreksi halus** | B4, B5, B6, B7, B10, B11, B13, B14, B16 | Perbaikan ketahanan & reversibilitas |
| **P4 — opsional** | A8–A11, A14, A16–A20, C1–C12 | Perlu pengukuran di perangkat sebelum diaktifkan |

---

## 7. Verifikasi yang masih diperlukan di perangkat

Beberapa klaim tidak dapat dibuktikan dari dump (sysfs/procfs hanya ada saat
runtime). Jalankan ini di device dan lampirkan hasilnya:

```sh
# 1. Layout cluster & policy
ls -d /sys/devices/system/cpu/cpufreq/policy*
cat /sys/devices/system/cpu/cpufreq/policy0/related_cpus
cat /sys/devices/system/cpu/cpufreq/policy6/related_cpus

# 2. Apakah policy4/policy7 ada (klaim A2)?
for p in 4 7; do echo "policy$p: $([ -d /sys/devices/system/cpu/cpufreq/policy$p ] && echo ADA || echo TIDAK)"; done

# 3. cpuset MIUI (klaim A1)
ls /dev/cpuset/
cat /dev/cpuset/game/cpus /dev/cpuset/gamelite/cpus 2>/dev/null

# 4. thermal_message (klaim A3)
ls -l /sys/class/thermal/thermal_message/
for n in sconfig temp_state boost cpu_limits screen_state wifi_limit; do
  printf '%s = %s\n' "$n" "$(cat /sys/class/thermal/thermal_message/$n 2>&1)"
done

# 5. WALT path (klaim B13)
ls /proc/sys/walt/ 2>/dev/null
cat /proc/sys/walt/sched_group_upmigrate 2>/dev/null
cat /proc/sys/kernel/sched_group_upmigrate 2>/dev/null

# 6. chmod sysfs (klaim B4)
chmod 000 /sys/class/thermal/thermal_message/board_sensor_temp; echo "exit=$?"
chmod 644 /sys/class/thermal/thermal_message/board_sensor_temp

# 7. SELinux (klaim B16)
getenforce
ls -lZ /sys/module/migt/parameters/glk_maxfreq
echo 0 0 0 > /sys/module/migt/parameters/glk_maxfreq; echo "write exit=$?"

# 8. Dynamic partition (klaim B1)
ls -l /dev/block/bootdevice/by-name/ | grep -E "system|vendor|super"
ls -l /dev/block/mapper/
cat /proc/mounts | grep -E "system|vendor|product"

# 9. mi_thermald & booster
getprop init.svc.mi_thermald
getprop persist.sys.enable_miui_booster
getprop persist.sys.mibridge_auth_uids

# 10. Perf HAL & Joyose
getprop init.svc.perf-hal-2-2
pidof Joyose
cat /sys/module/migt/parameters/glk_freq_limit_walt 2>/dev/null
```

---

## 8. Perubahan yang diterapkan

Lihat `docs/miui14-compat-changes.md` untuk rincian lengkap. Ringkas:

**Gelombang 1 — perbaikan P0**

1. `kr-script/common/mount.sh` — `mount_all()` sadar dynamic partition +
   probe writability eksplisit (B1).
2. `addin/kernel_probe.sh` — +30 probe MIUI (A1, A2, A3, A4, A8, A9, A10, A11,
   B12, B13).
3. `library/shell/ThermalDisguise.kt` — snapshot & restore `board_sensor_temp`;
   deteksi kegagalan `chmod`; gating berbasis `mi_thermald` (B3, B4, B5, B16).
4. `addin/scene_qualcomm_boost.sh` — clamp DDR floor ke
   `[MIN_DDR_FREQ, MAX_MIN_DDR_FREQ]` = `[1144, 2086]` (A7, B15).

**Gelombang 2 — seluruh 8 item rekomendasi**

5. `addin/scene_profile_options.sh` — cpuset game MIUI + pemindahan PID (A1),
   UFS idle saver game-scoped (B11), `apply_sptm_gover` yang menambal `policy6`
   (A2), `apply_coloc_fmin` (A15).
6. `powercfg/sm6150/powercfg-utils.sh` — `miui_lmk_managed()` (B7), `ufshc_perf`
   dipisah dari `ufshc_idle_saver` (B11), penulisan `msm_performance/cpu_max_freq`
   dihapus (B10), `stock_paths()` +5 node cpuset (B14).
7. `powercfg/sm6150/powercfg-base.sh` — LMK hanya ditulis bila MIUI tidak
   mengelolanya (B7).
8. `addin/zram_control.sh` + `library/shell/SwapModuleUtils.kt` — zram
   *raise-only*, algoritma terbaik dipilih otomatis (B6).
9. `addin/miui_booster.sh` (baru) + `utils/MiuiBoosterHints.kt` (baru) —
   otorisasi UID reversibel + klien `IMiuiBoosterManager` dengan ABI yang
   diverifikasi `dexdump` (A4).
10. `utils/QtiPerfHints.kt` — `preFlingBoost` (0x1080, aman) dan
    `dragBoost`/`releaseDragBoost` (0x1087, tanpa timeout, opt-in) (A6).
11. `utils/Diagnostics.kt`, `utils/KernelCapabilities.kt` — bagian MIUI pada
    laporan dan Diagnostics.
12. `SpfConfig.java`, `ProfileOptions.kt`, `DialogProfileOptions.kt`, layout,
    strings — 5 opsi baru dengan siklus hidup booster/drag yang benar.

**Verifikasi:** `:app:assembleDebug` hijau; aset baru terkonfirmasi masuk ke
dalam APK; seluruh skrip aset lolos `bash -n`.

### 8.1 Temuan tambahan yang muncul saat implementasi

**B17 — `FileWrite.parseText()` bisa memotong skrip secara senyap (P0).**

- **Lokasi:** `common/src/main/java/com/omarea/common/shared/FileWrite.kt:110-126`
- **Fakta:** buffer dialokasikan dengan `inputStream.available()` lalu hanya
  **satu** `read()` yang dijalankan. `available()` adalah petunjuk, bukan
  panjang, dan satu `read()` boleh mengembalikan lebih sedikit dari yang diminta
  — itu justru kasus normal untuk aset yang dikompresi. Hasilnya: skrip
  terpotong tanpa error.
- **Dampak:** semua skrip yang diekstrak lewat `writePrivateShellFile`
  (`scene_profile_options.sh` 49,5 KB, `scene_qualcomm_boost.sh`,
  `powercfg-utils.sh`, `kernel_probe.sh`, `miui_booster.sh`) berisiko terpotong.
  Gejalanya di perangkat adalah "command not found" pada baris terakhir yang
  selamat — sangat sulit dilacak.
- **Perbaikan:** `assetManager.open(fileName).use { it.readBytes() }` yang
  membaca sampai EOF. Diterapkan.

**B18 — CRLF pada skrip aset (risiko berulang).**

- **Lokasi:** `core.autocrlf=true` di repo ini, tanpa `.gitattributes`.
- **Fakta:** 7 skrip aset ditemukan berakhiran CRLF di working tree
  (`fast_charge.sh`, `fast_charge_run_once.sh`, `force_compact.sh`,
  `swap_control.sh`, `zram_control.sh`, `brightness.sh`, `powercfg-utils.sh`).
- **Dampak:** bagi mksh, `\r` bukan whitespace — ia menjadi bagian dari token
  terakhir, sehingga `foo() {` menjadi syntax error dan setiap assignment
  membawa CR. Aplikasi saat ini **selamat** karena `FileWrite.parseText()`
  menormalkan `\r\n` → `\n` saat ekstraksi, tetapi itu jaring pengaman, bukan
  izin menyimpan yang salah: skrip CRLF juga rusak bagi siapa pun yang
  menjalankannya langsung dari tree.
- **Perbaikan:** ketujuh file dinormalkan ke LF, dan `.gitattributes` baru
  memaksa `eol=lf` untuk `*.sh`, `*.bash`, `*.prop`, `*.rc`.

**Belum diuji di perangkat.** Empat klaim yang hanya bisa dibuktikan di device
ada di §7, dan batasan yang sengaja tidak dikerjakan ada di
`docs/miui14-compat-changes.md` §7.


