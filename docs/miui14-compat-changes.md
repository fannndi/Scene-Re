# Perubahan Kompatibilitas MIUI 14 — Log Penerapan

Pendamping `docs/miui14-compat-audit.md`. Seluruh 8 item rekomendasi sudah
diterapkan. Nomor temuan mengacu ke dokumen audit.

Status verifikasi: `:app:compileDebugKotlin` hijau, `:app:assembleDebug` hijau,
semua skrip aset lolos `bash -n`. **Belum diuji di perangkat** — lihat §7 audit
untuk daftar perintah verifikasi.

---

## 1. Perbaikan P0 (kerusakan senyap)

### 1.1 `kr-script/common/mount.sh` — B1

`mount_all()` ditulis ulang:

| Sebelum | Sesudah |
| --- | --- |
| `mount -o remount,rw /dev/block/bootdevice/by-name/system /system` tanpa syarat | Hanya dicoba bila entri `by-name` benar-benar ada |
| Tidak mengenal dynamic partition | Remount via `/dev/block/mapper/{system,system_ext,product,vendor}` |
| Tidak ada verifikasi | Verifikasi tulis nyata; pesan "dm-verity/AVB aktif" ke stderr bila remount mengaku sukses tapi tulis tetap gagal |
| Tanpa nilai balik | `0` = minimal satu partisi writable, `1` = tidak |

### 1.2 `library/shell/ThermalDisguise.kt` — B3, B4, B5, B16

| Masalah | Perbaikan |
| --- | --- |
| B3: `resumeMessage()` tidak memulihkan `board_sensor_temp` | Snapshot ke `vtools.thermal.disguise.bak` sebelum menimpa; dipulihkan saat resume. Snapshot MiGt juga |
| B4: `chmod 000` pada sysfs tidak andal | Node dibaca kembali dan dibandingkan dengan sentinel; `vtools.thermal.disguise.blocked=1` bila tidak cocok |
| B4: `isDisabled()` memeriksa mode file | Sekarang memeriksa **nilai** node |
| B5: gating hanya `MANUFACTURER == XIAOMI` | Ditambah cek `init.svc.mi_thermald` / `pidof mi_thermald` |
| B16: penolakan SELinux tidak terdeteksi | `isBlocked()` baru |

---

## 2. Delapan item rekomendasi — sudah diterapkan

### 2.1 `sys.sptm.gover` (A2)

`system/init.miui.rc:332-340` hanya menulis `policy0`, `policy4`, `policy7`;
surya punya `policy0` + `policy6` saja, jadi cluster big tidak pernah ikut.

`addin/scene_profile_options.sh`:

- `apply_sptm_gover <gov>` — menambal `policy6` dan menyetel properti
  `sys.sptm.gover` (hanya bila nilainya berubah, karena init hanya bereaksi pada
  perubahan nilai). Dilewati bila pengguna memilih governor sendiri
  (`SCENE_GOVERNOR`), supaya tidak saling menimpa.
- `restore_sptm_gover` — mengembalikan `policy6` dan menyetel properti ke `false`.

Env: `SCENE_SPTM_GOVER` (default **on** — hanya menyehatkan mekanisme MIUI
sendiri, bukan mengubah perilaku).

### 2.2 MiuiBooster (A4)

**ABI diverifikasi dengan `dexdump`**, bukan diasumsikan:

```
public MiuiBooster()                                   // konstruktor tanpa argumen
public boolean checkPermission(String pkg, int uid)
public int requestCpuHighFreq(int uid, int level, int timeoutMs)
public int requestGpuHighFreq / requestVpuHighFreq / requestIOHighFreq
public int requestMemory / requestNetwork              // (uid, level, timeoutMs)
public int requestThreadPriority(int uid, int tid, int level)
public int cancelCpuHighFreq(int uid)                  // + gpu/vpu/io/mem/net
public int cancelThreadPriority(int uid, int tid)
public static final int REQUEST_SUCCEEDED=0, REQUEST_FAILED=-1, PERMISSION_NOT_GRANTED=-2
```

`level` = tier device 1..3 (jar memvalidasi string device-level dengan
`c:[1-3],g:[1-3]`).

Dua bagian:

**(a) `addin/miui_booster.sh`** — otorisasi UID yang reversibel:

```
sh miui_booster.sh supported | authorized <uid> | authorize <uid> | revoke <uid>
```

Menambahkan UID Scene ke `persist.sys.mibridge_auth_uids` (snapshot asli ke
`vtools.scene.mibridge.bak`; `revoke` memulihkannya persis).

**(b) `utils/MiuiBoosterHints.kt`** — klien reflektif:

- `Class.forName` dulu; bila gagal, `DexClassLoader` dari
  `/system/framework/MiuiBooster.jar` ke `context.codeCacheDir`.
- `checkPermission` dipanggil sekali per proses (wajib — jar memeriksa flag
  internal sebelum setiap request).
- `requestCpu/Gpu/Io/Memory` dengan timeout 15 s (jendela yang sama dengan
  game boost MIUI), `cancelAll` saat game keluar.
- Semua jalur gagal bersifat senyap; layer sysfs tetap mekanisme utama.

Wiring di `ProfileOptions.kt`: `applyMiuiBooster()` (otorisasi → checkPermission
→ request) dan `releaseGameBoosts()` (dipanggil di setiap apply non-game, jalur
`disabled`, dan `reset()`).

Env: opsi UI **MIUI booster service** (default **off**). Switch dinonaktifkan
otomatis bila jar/service tidak ada.

### 2.3 Drag boost `0x1087` (A6)

`utils/QtiPerfHints.kt` dirapikan: satu helper `sendHint(opcode, pkg, type, label)`.

- `preFlingBoost()` — `0x1080` Type 4, 80 ms, kedaluwarsa sendiri (aman).
- `dragBoost()` / `releaseDragBoost()` — `0x1087` Type 1, **`Timeout=0` = tidak
  pernah kedaluwarsa**. Pelepasan dikirim dengan type `-1`.
- `PROP_DRAG_HELD` mencatat bahwa pelepasan masih terutang; `releaseGameBoosts()`
  melepas hanya bila prop itu `1`.

Env: opsi UI **Unlimited drag boost** (default **off**), dinonaktifkan otomatis
bila kanal perf HAL tidak tersedia.

### 2.4 Colocation v3 (A15)

`sched_little_cluster_coloc_fmin_khz` — Qualcomm post_boot memakukannya di
740 kHz (`init.qcom.post_boot.sh:3727`).

- `apply_coloc_fmin <kHz>` / `restore_coloc_fmin` di `scene_profile_options.sh`.
- Dinaikkan ke 1248000 pada `performance|fast` saja.

Env: `SCENE_COLOC_FMIN` dari opsi UI **Colocation floor boost** (default off).

### 2.5 zram (B6)

`addin/zram_control.sh` ditulis ulang (file ini adalah library; jalur live ada di
`swap_control.sh` + `SwapModuleUtils`):

- `best_zram_algorithm()` — pilih `zstd` > `lz4` > `lzo` dari `comp_algorithm`.
- `enable_zram [sizeMB]` — **raise-only**: menolak mengecilkan zram yang sudah
  dikonfigurasi MIUI (4096 MB untuk device 6 GB, `perfinit.conf:7`).
- Sintaks `function name()` diganti bentuk POSIX (mksh tidak menerima
  `function name()`, hanya `name()`).
- Urutan `swapoff` sebelum `reset`/`disksize 0` diperbaiki.

`SwapModuleUtils.kt`: default algoritma `"lzo"` → `bestZramAlgorithm()`
(dipakai juga di `ensureKeys()`).

### 2.6 LMK lewat prop MIUI (B7)

`powercfg-utils.sh` mendapat `miui_lmk_managed()`: mendeteksi
`persist.sys.minfree_def` / `minfree_6g` / `persist.sys.lmk.camera_minfree_levels`.

`powercfg-base.sh` hanya menulis
`/sys/module/lowmemorykiller/parameters/enable_adaptive_lmk` bila interface prop
MIUI **tidak** ada. Di MIUI, lmkd menurunkan node itu dari prop, jadi menulisnya
dari userspace tidak berefek sekaligus berisiko melawan tuning platform.

### 2.7 `msm_performance/parameters/cpu_max_freq` (B10)

Dua penulisan sentinel dihapus dari `set_cpu_freq()`. Node itu dipetakan ke
opcode `CPUBOOST_MAX_FREQ` perf HAL v2.2
(`commonsysnodesconfigs.xml` Idx `0x2`) sehingga menulisnya dari userspace adalah
race. `scaling_min/max_freq` tetap jadi jalur otoritatif.

### 2.8 UFS power saving (B11)

`ufshc_perf()` dipisah:

- `ufshc_perf on|off` — hanya `clkscale_enable` + devfreq `min_freq`. Saat `off`
  juga memulihkan `clkgate_enable` dan `hibern8_on_idle_enable` ke `1`.
- `ufshc_idle_saver off|on` — baru, khusus `clkgate_enable` +
  `hibern8_on_idle_enable`.

`scene_profile_options.sh` memanggilnya hanya saat `SCENE_GAME_PKG` tidak kosong
(`apply_ufs_idle_saver` / `restore_ufs_idle_saver`), jadi biaya daya idle
terbatas pada sesi game.

Env: `SCENE_UFS_IDLE_BOOST` (default on, game-scoped).

---

## 3. Peluang cpuset MIUI (A1)

`powercfg-utils.sh`:

- `stock_paths()` +5 node: `/dev/cpuset/{game,gamelite,top-app/boost,background/untrustedapp,vr}/cpus`.

`scene_profile_options.sh`:

- `apply_game_cpuset <heavy> <light>` / `restore_game_cpuset()` — memakai
  `apply_tunable`/`restore_tunable` sehingga snapshot & restore otomatis.
- Pemetaan: `performance|fast` → game `6-7`, gamelite `0-5`;
  `balance|light` → game `0-7`, gamelite `0-5`.
- **PID game benar-benar dipindahkan** ke `/dev/cpuset/game/cgroup.procs`.
  Menyetel mask saja tidak berpengaruh apa pun tanpa ini.
- `restore_game_cpuset` ikut di jalur `SCENE_RESET`.

---

## 4. Probe & Diagnostics

`addin/kernel_probe.sh` — +30 probe (semua read-only):

```
xiaomi.migt_viptask, xiaomi.migt_walt_limit, xiaomi.turbo_sched, xiaomi.joyose,
xiaomi.mi_thermald, xiaomi.gpu_plaid, xiaomi.core_ctl_isolated
miui.cpuset_game(+cpus), miui.cpuset_gamelite(+cpus), miui.cpuset_vr,
miui.cpuset_top_app_boost, miui.cpuset_untrusted
miui.thermal_sconfig, miui.thermal_temp_state, miui.thermal_boost,
miui.thermal_cpu_limits, miui.thermal_screen_state
miui.booster_enabled, miui.booster_jar, miui.booster_auth_uids
miui.sptm_gover, miui.fluency_enabled, miui.fluency_thermal_break
cpu.walt_proc, cpu.walt_group_upmigrate, cpu.coloc_fmin, cpu.irq_affinity
system.dynamic_partitions, system.by_name_system, system.selinux
```

`utils/KernelCapabilities.kt` — bagian `miui` ditambahkan ke daftar `sections`;
tanpa itu semua probe `miui.*` tidak akan muncul di laporan.

`utils/Diagnostics.kt` — `miu-integration.txt` diperluas dengan: status MiuiBooster
+ allow-list, seluruh node `thermal_message/*`, state cpuset MIUI + PID di
bucket game, saklar platform (`sys.sptm.gover`, colocation, `mi_fluency`), dan
kondisi zram (ukuran efektif + algoritma aktif).

---

## 5. Config & UI

`SpfConfig.java` — 5 kunci baru + konstanta colocation 1248000 kHz.

`ProfileOptions.kt` — 5 field `Config` baru, env baru
(`SCENE_SPTM_GOVER`, `SCENE_COLOC_FMIN`, `SCENE_UFS_IDLE_BOOST`), plus
`applyMiuiBooster()` dan `releaseGameBoosts()`.

`DialogProfileOptions.kt` + `dialog_profile_options.xml` + `strings.xml` — 5 baris
switch baru:

| Opsi | Default | Catatan |
| --- | --- | --- |
| MIUI performance switch (SPTM) | on | |
| Colocation floor boost | off | |
| UFS idle boost in games | on | |
| MIUI booster service | off | Switch mati otomatis bila jar/service tidak ada |
| Unlimited drag boost | off | Switch mati otomatis bila kanal perf HAL tidak ada |

---

## 6. Berkas yang berubah

| Berkas | Perubahan |
| --- | --- |
| `kr-script/common/mount.sh` | `mount_all()` sadar dynamic partition |
| `addin/kernel_probe.sh` | +30 probe MIUI |
| `addin/scene_profile_options.sh` | cpuset game, UFS idle, SPTM, colocation |
| `addin/scene_qualcomm_boost.sh` | clamp DDR floor `[1144, 2086]` |
| `addin/zram_control.sh` | raise-only, pilih algoritma terbaik |
| `addin/miui_booster.sh` | **baru** — otorisasi UID reversibel |
| `powercfg/sm6150/powercfg-utils.sh` | `miui_lmk_managed`, UFS split, hapus `msm_performance`, `stock_paths` +5 |
| `powercfg/sm6150/powercfg-base.sh` | LMK sadar MIUI |
| `utils/MiuiBoosterHints.kt` | **baru** — klien `IMiuiBoosterManager` |
| `utils/QtiPerfHints.kt` | pre-fling + drag boost + rilis |
| `utils/Diagnostics.kt` | bagian MIUI diperluas |
| `utils/KernelCapabilities.kt` | bagian `miui` di laporan |
| `library/shell/ThermalDisguise.kt` | snapshot/restore + deteksi blokir |
| `library/shell/SwapModuleUtils.kt` | algoritma zram terbaik |
| `common/shared/FileWrite.kt` | `parseText()` baca sampai EOF (B17) |
| `.gitattributes` | **baru** — paksa `eol=lf` untuk skrip (B18) |
| `scene_mode/options/ProfileOptions.kt` | 5 opsi + booster/drag lifecycle |
| `vtools/dialogs/DialogProfileOptions.kt` | 5 switch |
| `res/layout/dialog_profile_options.xml` | 5 baris |
| `res/values/strings.xml` | 12 string |
| `store/SpfConfig.java` | 5 kunci |

---

## 8. Dua temuan yang muncul saat implementasi (sudah diperbaiki)

**B17 — `FileWrite.parseText()` memotong skrip secara senyap.** Buffer
dialokasikan dari `inputStream.available()` dan hanya satu `read()` dijalankan;
`available()` bukan panjang dan satu `read()` boleh kembali pendek (normal untuk
aset terkompresi). Diganti `use { it.readBytes() }` yang membaca sampai EOF.
Lihat audit §8.1.

**B18 — CRLF pada skrip aset.** `core.autocrlf=true` tanpa `.gitattributes`
membuat 7 skrip aset berakhiran CRLF. Aplikasi selamat karena `parseText()`
menormalkan CRLF saat ekstraksi, tetapi skrip CRLF rusak bagi siapa pun yang
menjalankannya langsung dari tree. Ketujuhnya dinormalkan ke LF dan
`.gitattributes` baru memaksa `eol=lf`.

---

## 9. Sisa pekerjaan / batasan

Yang **tidak** dikerjakan, sengaja:

- **Menulis prop LMK MIUI** (`persist.sys.minfree_*`). Format nilainya tidak
  terdokumentasi di dump; menebak bisa merusak tuning memori platform. Yang
  dilakukan hanya berhenti menulis node kernel yang tidak efektif.
- **Memakai `sys.sptm.gover` sebagai satu-satunya jalur governor.** Governor
  tetap milik `active.sh`; SPTM hanya menambal `policy6` dan menjaga properti
  sinkron.
- **Menambah blok baru ke `powerhint.xml`.** File itu diparse HAL saat boot dan
  salah format berarti kehilangan seluruh hint kamera/qvr. Perubahan di sana
  sebaiknya lewat overlay dan diuji terpisah.
- **Menyalakan `persist.sys.miui_mi_fluency.thermal_break`.** Itu mematikan
  proteksi thermal MIUI secara global; hanya boleh sebagai aksi eksplisit
  pengguna, bukan default opsi.

Yang masih perlu keputusan/uji di perangkat:

1. Apakah `MiuiBooster.checkPermission` benar-benar mengembalikan `true` setelah
   UID ditambahkan (butuh reboot atau tidak).
2. Apakah `releaseDragBoost()` (`type = -1`) diakui HAL. Bila tidak, boost
   `0x1087` bocor sampai reboot — karena itu defaultnya off.
3. Apakah `chmod` pada `board_sensor_temp` berhasil di kernel surya.
4. Apakah `sched_group_*migrate` di `/proc/sys/walt/` dan `/proc/sys/kernel/`
   adalah alias di kernel ini.
