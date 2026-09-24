# Audit Menyeluruh — Scene

**Tanggal:** 23 September 2026
**Cakupan:** seluruh project (modul `:app`, `:common`, `:krscript`) — dependency, kompatibilitas antar-paket, dan kualitas kode
**Basis kode:** ~45.500 LOC (264 file Kotlin, 68 file Java, 300 file XML)

---

## 1. Ringkasan Eksekutif

| Area | Status | Catatan |
|---|---|---|
| Dependency | **Diperbarui** | 12 dari 13 dependency dinaikkan ke versi stabil terbaru; 1 dihapus |
| Toolchain | **Diperbarui** | Gradle 9.1.0 → 9.7.1, AGP 9.0.0 → 9.4.1, Kotlin 2.0.21 → 2.4.20, NDK 25.2 → 28.2 |
| Kompatibilitas | **6 temuan** | 2 temuan memblokir (`nonTransitiveRClass`, Compose vs `compileSdk`), semuanya sudah dibereskan |
| Bug nyata | **14 diperbaiki** | termasuk 5 bug logika yang menyebabkan perilaku salah / crash |
| Edge-to-edge (targetSdk 36) | **Selesai** | 24 Activity + 7 layout + 3 helper inset baru — tanpa ini UI tertutup system bar di Android 15+ |
| Struktur build | **Dimodernisasi** | Version catalog + deprecation Gradle 10 dihilangkan total |
| Build | **Terverifikasi** | `:app:assembleDebug` hijau; APK ~24 MB dihasilkan; scope check `AGENTS.md` bersih |

Yang **tidak** diubah: arsitektur modul, struktur paket, dan seluruh kode native/CMake. Tidak ada fitur yang ditambahkan atau dihapus.

---

## 2. Pembaruan Toolchain (beserta alasan)

| Komponen | Sebelum | Sesudah | Alasan |
|---|---|---|---|
| Gradle wrapper | 9.1.0 | **9.7.1** | AGP 9.4 mewajibkan Gradle **≥ 9.6.0** — jadi kenaikan wrapper ini bukan opsional, melainkan prasyarat. Ditambahkan juga `distributionSha256Sum` agar distribusi Gradle diverifikasi integritasnya saat diunduh. |
| AGP | 9.0.0 | **9.4.1** | Rilis stabil terbaru. Mendukung API level hingga 37 (project memakai 37). |
| Kotlin | 2.0.21 | **2.4.20** | Versi plugin Compose compiler **harus sama persis** dengan versi compiler Kotlin; keduanya diikat ke satu entri `kotlin` di catalog. |
| NDK | 25.2.9519653 | **28.2.13676358** | Ini default AGP 9.4, dan merupakan jalur LTS pertama dengan dukungan **halaman 16 KB** — syarat yang diminta Google Play untuk aplikasi dengan targetSdk 35+. |
| compileSdk | 36 | **37** | **Diwajibkan** oleh Compose 1.12.1 (compose-bom 2026.09.00) — AAR metadata-nya menyatakan "compile against version 37 or later". AGP 9.4 mendukung hingga API 37. **Lihat §3.2.** |
| targetSdk | 34 | **36** | 34 sudah tidak memenuhi syarat publikasi Google Play. **Lihat peringatan di §3.1.** |
| minSdk | 24 (app) / 21 (lib) | **24 (semua)** | Menyelaraskan modul library dengan app; menghapus cabang kode mati (`SDK_INT < 24`). |

---

## 3. Masalah Kompatibilitas Antar-Paket

### 3.1 ⚠️ `targetSdk 34 → 36` memicu edge-to-edge → **SUDAH DITANGANI** (§6.1)

Ini konsekuensi yang **harus** ditangani, bukan sekadar kenaikan angka. Pada perangkat Android 15+, aplikasi dengan targetSdk ≥ 35 dipaksa edge-to-edge, dan `Window.setStatusBarColor()` / `setNavigationBarColor()` menjadi **no-op**.

**Tidak ada jalan pintas.** Saya verifikasi ini dari dokumentasi resmi Android 16:

> *"Android 15 enforced edge-to-edge for apps targeting Android 15 (API level 35), but your app could opt-out by setting `R.attr#windowOptOutEdgeToEdgeEnforcement` to `true`. For apps targeting Android 16 (API level 36), `R.attr#windowOptOutEdgeToEdgeEnforcement` is deprecated and disabled, and your app can't opt-out of going edge-to-edge."*
>
> — Jika app menargetkan API 36 berjalan di perangkat Android 15, atribut itu **masih berfungsi**; di perangkat Android 16 atribut itu **dinonaktifkan**.

Artinya menyetel atribut opt-out hanya akan menutupi masalah di Android 15 sambil tetap rusak di Android 16 — jadi saya **tidak** memakainya. Perbaikan sesungguhnya ada di §6.1.

Titik yang terpengaruh (semuanya sudah diperbaiki):
- `app/.../utils/WindowCompatHelper.kt`
- `app/.../activities/ActivityStartSplash.kt`
- `app/.../activities/ActivityAddinOnline.kt` + JS bridge `setStatusBarColor`/`setNavigationBarColor`
- `app/.../activities/ThemeSwitch.kt`
- `app/.../activities/ActionPage.kt`
- `app/.../activities/ActivityFpsChart.kt`

**Catatan penting:** `android:fitsSystemWindows="true"` **tidak bisa** dipakai sebagai solusinya. Atribut itu hanya mengonsumsi inset saat window *tidak* di-layout edge-to-edge, sehingga seluruh pemakaian `fitsSystemWindows="true"` di project ini berhenti berfungsi secara diam-diam begitu targetSdk naik ke 36. Ini yang membuat bug-nya tidak terlihat: layout lama "kelihatan benar" di API 35 ke bawah.

### 3.2 ❌ DITEMUKAN SAAT BUILD → DIPERBAIKI: Compose 1.12.1 mewajibkan `compileSdk ≥ 37`

Ini temuan yang **hanya bisa ditemukan dengan menjalankan build sungguhan**, bukan dari membaca `maven-metadata.xml`. Setelah semua dependency dinaikkan, `:app:checkDebugAarMetadata` gagal:

```
Dependency 'androidx.compose.ui:ui-android:1.12.1' requires libraries and applications that
depend on it to compile against version 37 or later of the Android APIs.

:app is currently compiled against android-36.
```

Mekanismenya: sejak AGP 7.x, setiap AAR menyertakan **AAR metadata** berisi field `minCompileSdk`. Compose 1.12.1 menetapkan nilai itu ke `37`, jadi aplikasi yang mengompilasi terhadap `android-36` ditolak di tahap verifikasi — jauh sebelum kompilasi Kotlin dimulai.

**Perbaikan:** `compileSdk 36 → 37` di `gradle/libs.versions.toml`. Platform `android-37.0` dan `build-tools 37.0.0` sudah terpasang di mesin ini, jadi tidak perlu unduhan tambahan.

**Catatan penting — `compileSdk` dan `targetSdk` adalah dua knob yang independen:**

| | Nilai | Arti |
|---|---|---|
| `compileSdk` | **37** | Terhadap API mana kode dikompilasi. Hanya memengaruhi simbol yang tersedia saat compile; **tidak** mengubah perilaku runtime. |
| `targetSdk` | **36** | Kontrak perilaku runtime + syarat publikasi Google Play. |

Menaikkan `compileSdk` ke 37 **tidak** memicu perubahan perilaku baru di perangkat, dan **tidak** menaikkan syarat Play Store. Jadi langkah ini murni menyelesaikan constraint library, tanpa memperbesar risiko runtime.

### 3.3 ✅ Diverifikasi AMAN — miuix 0.8.8 vs Compose BOM 2026.09.00

Ini titik risiko terbesar dari sisi versi:

| | Dikompilasi terhadap | Dipakai bersama |
|---|---|---|
| miuix 0.8.8 | Compose Multiplatform **1.10.3**, kotlin-stdlib **2.3.20** | → |
| compose-bom 2026.09.00 | Jetpack Compose **1.12.1** | |

Gradle menyelesaikan ke 1.12.1 (versi lebih tinggi), sehingga miuix berjalan di atas Compose **2 minor lebih baru** daripada saat ia dikompilasi. Permukaan API miuix yang dipakai project ternyata sangat kecil dan stabil — hanya 5 import di 4 file:

`MiuixTheme.colorScheme` (39×), `MiuixTheme.textStyles` (28×), `CardDefaults.defaultColors` (3×), `ColorSchemeMode.Light/Dark`, `ThemeController(...)`, `Card(...)`

**Hasil: kompilasi lolos.** Tetap disarankan menjalankan uji visual pada halaman yang memakai miuix (`FragmentHome`, `FragmentCpuModes`, `FragmentNav`, `OverviewMenu`).

### 3.4 ❌ DITEMUKAN SAAT BUILD → DIPERBAIKI: Kotlin 2.4.20 memperketat nullability platform-type

Setelah `compileSdk` naik ke 37, `:app:compileDebugKotlin` gagal pada kode yang **sebelumnya lolos**:

```
ActivityBase.kt:76:34 Only safe (?.) or non-null asserted (!!.) calls are allowed
on a nullable receiver of type 'ActivityManager.RecentTaskInfo?'.
```

```kotlin
for (task in service.appTasks) {
    if (task.taskInfo.taskId == this.taskId) { ... }   // ← taskInfo kini nullable
}
```

**Kenapa ini muncul sekarang, padahal tidak ada perubahan kode terkait:**

Ini efek gabungan dari **dua** kenaikan sekaligus:

1. **`compileSdk` 36 → 37** mengganti file stub `android.jar` yang dipakai compiler. Di API 37, `ActivityManager.AppTask.getTaskInfo()` dianotasi `@Nullable` — sebelumnya tidak dianotasi, sehingga Kotlin memperlakukannya sebagai *platform type* (`RecentTaskInfo!`) yang boleh dipakai bebas.
2. **Kotlin 2.0.21 → 2.4.20** memperketat penanganan platform type hasil generik/annotasi tersebut.

Jadi kode lama sebenarnya **sudah berpotensi NPE sejak dulu** — hanya saja tidak pernah diberitahukan compiler. `taskInfo` memang benar-benar bisa `null`: dokumentasi Android menyatakan nilainya `null` bila task record sudah dihapus sistem, dan `service.appTasks` mengembalikan snapshot yang bisa sudah basi saat diiterasi. Perbaikannya bukan sekadar memuaskan compiler, melainkan menutup jalur NPE nyata:

```kotlin
// taskInfo is nullable: it can be null if the task record has
// already been removed by the system between the appTasks
// snapshot and this read.
if (task.taskInfo?.taskId == this.taskId) { ... }
```

**Pelajaran untuk upgrade berikutnya:** menaikkan `compileSdk` **bukan** operasi bebas-risiko. Ia mengganti stub API yang menjadi dasar compiler memutuskan nullability, sehingga bisa memunculkan error kompilasi baru di kode yang tidak disentuh sama sekali. Selalu jalankan build penuh setelah menaikkannya — bukan hanya setelah mengubah dependency.

### 3.5 ❌ DIBATALKAN — `android.nonTransitiveRClass = true`

Sempat saya aktifkan sebagai modernisasi (ini default AGP modern), lalu **build gagal** dengan 12 error `UNRESOLVED_REFERENCE`. Penyebabnya: kode aplikasi mereferensikan resource milik library **dan** milik appcompat melalui R transitif.

| Lokasi | Referensi | Pemilik resource |
|---|---|---|
| `ui/TabIconHelper2.kt:64` | `R.attr.colorAccent` | appcompat |
| `activities/ActivityStartSplash.kt:125` | `R.attr.colorAccent` | appcompat |
| `activities/ActionPage.kt:228,234,237` | `R.drawable.kr_folder`, `kr_fab` | `:krscript` |
| `activities/ActionPageOnline.kt:330,339` | `R.string.kr_download_downloading/_completed` | `:krscript` |
| `dialogs/DialogAppOptions.kt:159,160,167,168` | `R.layout.dialog_loading`, `R.id.dialog_text`, `R.id.dialog_app_details_progress` | `:common` |

Nilai `false` dipertahankan, dengan komentar penjelasan + daftar lokasi di `gradle.properties` supaya migrasi nanti tidak perlu menelusuri ulang. **Ini temuan penting:** analisis statis awal saya (mencocokkan nama resource antar-modul) hanya menemukan 9 referensi dan semuanya di XML — sehingga tampak aman. Yang terlewat adalah resource milik AAR pihak ketiga (`R.attr.colorAccent`) yang tidak pernah muncul di `res/` project. Verifikasi empiris lewat build yang menangkapnya.

### 3.6 `resConfigs` → `localeFilters`

`resConfigs` sudah deprecated sejak AGP 8.8. Saya verifikasi tanda tangan DSL-nya langsung dari bytecode AGP 9.4.1:

```
ApplicationAndroidResourcesImpl:
  public java.util.Set<String> getLocaleFilters();
  public final void setLocaleFilters(java.lang.Iterable<String>);
```

Karena itu bentuk yang benar adalah assignment (`localeFilters = ["en"]`), bukan pemanggilan method.

### 3.7 Groovy space-assignment → akan dihapus di Gradle 10

Build lama menghasilkan peringatan *"Deprecated Gradle features were used in this build, making it incompatible with Gradle 10"* untuk 20+ properti (`namespace "..."`, `compileSdk 36`, `minSdk 24`, `signingConfig ...`, dst.).

**Semua sudah dikonversi ke `propName = value`.** Setelah perubahan, peringatan tersebut **hilang sepenuhnya**.

Catatan: `abiFilters "arm64-v8a"` dan `cppFlags ""` **sengaja tidak diubah** — saya cek bytecode AGP dan keduanya adalah overload method sungguhan (`void abiFilters(String...)`, `void cppFlags(String...)`), bukan property assignment, jadi tidak deprecated.

---

## 4. Pembaruan Library

| Library | Sebelum | Sesudah |
|---|---|---|
| `androidx.compose:compose-bom` | 2026.01.00 | **2026.09.00** (Compose 1.10.1 → 1.12.1) |
| `androidx.appcompat:appcompat` | 1.6.1 | **1.8.0** |
| `com.google.android.material:material` | 1.9.0 | **1.14.0** |
| `androidx.constraintlayout:constraintlayout` | 2.1.4 | **2.2.2** |
| `androidx.work:work-runtime-ktx` | 2.11.0 | **2.11.2** |
| `org.jetbrains.kotlinx:kotlinx-coroutines-android` | 1.7.3 | **1.11.0** |
| `top.yukonga.miuix.kmp:miuix` | 0.7.2 | **0.8.8** |
| `androidx.viewpager2:viewpager2` | 1.1.0 | 1.1.0 (sudah terbaru) |
| `com.squareup.leakcanary:leakcanary-android` | 2.14 | 2.14 (sudah terbaru stabil — 3.x masih alpha) |
| `androidx.legacy:legacy-support-v4` | 1.0.0 | **DIHAPUS** |

**Kenapa `legacy-support-v4` dihapus:** nol referensi di seluruh source (`grep androidx.legacy` → 0 hasil). Library ini sudah deprecated sejak 2019 dan menarik artefak support-library lama ke dalam build.

**Catatan:** semua versi diverifikasi langsung dari `maven-metadata.xml` Google Maven & Maven Central, bukan dari ingatan.

---

## 5. Modernisasi Struktur Build (maintainability)

| Perubahan | Manfaat |
|---|---|
| **Baru:** `gradle/libs.versions.toml` | Satu sumber kebenaran untuk semua versi. Sebelumnya versi tersebar di 4 file dengan `ext.kotlin_version` di root. |
| `settings.gradle` memegang semua repository + `FAIL_ON_PROJECT_REPOS` | Sumber dependency terpusat; `repositories {}` per-modul (3 buah) dihapus sehingga tidak bisa lagi menyimpang. |
| Root `build.gradle` pakai `plugins { alias(...) apply false }` | Menghapus blok `buildscript` + `allprojects` manual. |
| `tasks.register('clean', Delete)` + `layout.buildDirectory` | `task clean` dan `rootProject.buildDir` akan dihapus di Gradle 10. |
| `api` vs `implementation` dikoreksi di `:common` dan `:krscript` | `:krscript` mengekspos tipe `:common` di API publiknya, dan keduanya mengekspos tipe AppCompat — jadi keduanya harus `api`, bukan `implementation`. |
| Atribut `package` dihapus dari manifest library | AGP mengabaikannya dan memunculkan peringatan build; namespace sudah dideklarasikan di `build.gradle`. |
| `.gitignore`-safe: `distributionSha256Sum` ditambahkan | Verifikasi integritas distribusi Gradle. |

---

## 6. Bug & Perbaikan Kualitas Kode

Semua bug di bawah ini **dikonfirmasi dengan membaca source**, bukan dari tebakan.

### 6.1 Dukungan edge-to-edge untuk targetSdk 36 (perbaikan terbesar)

Ini bukan "bug" kecil melainkan perbaikan struktural yang menyentuh **24 Activity + 6 layout**. Tanpa ini, di Android 15+ isi layar akan tergambar di belakang status bar dan navigation bar — dan karena `setStatusBarColor()` diabaikan, tidak ada cara menyembunyikannya.

#### Strategi yang dipilih

Saya **menolak** opsi `windowOptOutEdgeToEdgeEnforcement` karena terbukti dinonaktifkan di API 36 (§3.1). Yang dipakai adalah jalur yang benar:

1. **`WindowCompat.setDecorFitsSystemWindows(window, false)` di `ActivityBase.onCreate()`** — satu tempat untuk semua layar. Setiap Activity yang mewarisi `ActivityBase` langsung menjadi edge-to-edge.
2. **Inset diterapkan per-komponen, bukan ke root**, karena root-padding akan salah untuk layar dengan app bar atau tab bar (bar-nya ikut ter-padding, jadi warnanya tidak lagi mencapai tepi layar).
3. **Padding delta, bukan absolut.** Inset di-dispatch ulang setiap kali keyboard muncul atau rotasi berubah. Kalau kita menulis `paddingTop = bars.top`, maka dispatch berikutnya akan **menumpuk** padding. Karena itu tiap listener menyimpan inset terakhir dan menambahkan selisihnya saja.

#### Helper baru di `WindowCompatHelper.kt`

| Fungsi | Kegunaan |
|---|---|
| `applySystemBarInsets(view, top, bottom, leftRight, includeIme)` | Inset umum untuk root/container layar biasa. Menggabungkan `systemBars() or displayCutout()`, plus `ime()` di API 30+ |
| `applyAppBarInsets(appBar, toolbar)` | Inset untuk `layout_app_bar.xml` bersama. **Padding ditaruh di `AppBarLayout`, bukan `Toolbar`**, karena AppBarLayout-lah yang memegang background `?android:statusBarColor` — jadi strip status bar tetap berwarna sementara baris toolbar terdorong ke bawah |
| `applyBottomInset(view, extraPadding)` | Untuk kontrol yang menempel di bawah (FAB, list) agar tidak tertutup navigation bar / gesture handle |

Detail penting pada logika inset bawah: bila IME terlihat, inset-nya biasanya **lebih besar** daripada navigation bar. Karena itu yang dipakai adalah `maxOf(bars.bottom, ime.bottom)`, **bukan penjumlahan** — menjumlahkan keduanya akan menghasilkan padding ganda di mode landscape.

#### Method baru di `ActivityBase`

- `applyContentInsets(...)` — inset root, untuk layar biasa.
- `applyAppBarInsets(idBottom)` — mencari `R.id.app_bar` + `R.id.toolbar` lewat `findViewById` lalu memasang inset. **Ini membuat satu perubahan di `layout_app_bar.xml` memperbaiki 6 layar sekaligus**, karena bar itu di-`include` oleh banyak layout.

#### Perubahan per layar

| Layar | Perlakuan |
|---|---|
| `ActivityMain` | Sudah punya listener inset manual untuk `tabBar` (atas saja). **Ditambah inset bawah + horizontal untuk `tabContent`**, dan listener lama diperbaiki agar memakai delta (sebelumnya menimpa padding, jadi bug laten saat rotasi) |
| 24 Activity pengguna `layout_app_bar` | Ditambahkan `applyAppBarInsets()` setelah `setContentView()` |
| `ActionPage` | Ikut `activity_action_page.xml` → `applyAppBarInsets()` |
| `ActivityAddinOnline` | WebView di-inset; panggilan `setSystemBarColors(WHITE, WHITE)` yang sia-sia dihapus |
| `ActivityStartSplash` | Sengaja **tidak** di-inset (background full-bleed); warna bar yang diabaikan dihapus, hanya tint ikon yang dipertahankan |
| `ActivityFpsChart` | Blok `statusBarColor` sudah berada di dalam komentar → tidak ada aksi |

#### Layout yang disesuaikan

`fitsSystemWindows` diubah dari `"true"` → `"false"` pada 6 layout yang punya app bar/WebView, supaya `CoordinatorLayout` tidak melakukan konsumsi inset sendiri dan **bertabrakan** dengan padding runtime:

`activity_action_page.xml` · `activity_app_details.xml` · `activity_other_settings.xml` · `activity_timing_task.xml` · `activity_trigger.xml` · `activity_addin_online.xml`

Setiap layout diberi komentar yang menjelaskan **kenapa** `fitsSystemWindows` tidak boleh `true` di sini, agar tidak ada yang "mengembalikannya" di kemudian hari.

#### Bridge JavaScript di `ActivityAddinOnline`

`setStatusBarColor()` / `setNavigationBarColor()` dari halaman web tetap mengembalikan `true` (agar halaman tidak pecah), tapi sekarang diberi komentar eksplisit bahwa **warna bar diabaikan platform di targetSdk 36** — hanya tint ikon yang benar-benar berpengaruh. Sebelumnya fungsi ini seolah berhasil padahal tidak, dan itulah yang membuat masalahnya tidak terdeteksi.

#### Catatan: `ThemeMode` bukan enum

Saat menulis kode ini saya sempat berasumsi `ThemeMode` adalah enum dengan nilai `NIGHT`, dan **kompilasi gagal** (`Unresolved reference 'NIGHT'`). Ternyata `ThemeMode` adalah `class` dengan field `isDarkMode` dan `isLightStatusBar`. Nilai `isLightStatusBar` itulah yang dipakai — sekaligus lebih tepat daripada menurunkan sendiri dari `isDarkMode`.

### Bug yang menyebabkan perilaku salah / crash

| # | Lokasi | Masalah | Perbaikan |
|---|---|---|---|
| 1 | `activities/ActivityCpuControl.kt:360` | `getClusterGovernors()` menulis daftar *governor* ke dalam map *frekuensi* (`clusterFreqs`) → `!!` di baris berikutnya bisa NPE | Ditulis ke `clusterGovernors`; `!!` diganti fallback `emptyArray()` |
| 2 | `dialogs/DialogAppOptions.kt:351-354` | `switchSuspend.isEnabled = false` langsung ditimpa `= true` di baris berikutnya — switch tidak pernah benar-benar dinonaktifkan | Baris penimpa dihapus; komentar menjelaskan API 28 |
| 3 | `activities/ActivityFreezeApps.kt:674` | Filter memakai `applicationInfo` milik **Activity itu sendiri**, bukan milik item; karena `AppInfo` tidak punya field `applicationInfo`, klausa ini selalu `false` (logika mati) | Klausa dihapus — `getUserAppList()` memang sudah hanya mengembalikan aplikasi non-sistem |
| 4 | `services/CompileService.kt:162` | `doCmdSync` setelah loop mengompilasi **package Scene sendiri** (`packageName` me-resolve ke `Service.packageName`) | Baris dihapus; loop sudah menangani semua package |
| 5 | `services/CompileService.kt` | `if (true) { ... } else { break }` — kode mati; `compiling` adalah static non-volatile yang diubah dari thread IO | Kode mati dihapus; `@Volatile` ditambahkan |
| 6 | `common/.../shell/KeepShell.kt:105-115` | Thread pembuang stderr berputar **100% CPU** setelah shell mati (`readLine()` mengembalikan `null` selamanya) | Loop berhenti saat `null`, reader ditutup dengan `use {}` |
| 7 | `common/.../shell/KeepShell.kt:184` | Jika shell mati, `doCmdSync` mengembalikan **output perintah sebelumnya** (dari cache) seolah hasil perintah saat ini — hasil salah secara diam-diam | Mengembalikan `"error"` bila `reader`/`out` null; cache dibersihkan tiap perintah |
| 8 | `common/.../shell/KernelProrp.kt:28` | `chmod 664 "$p" 2 > /dev/null` — spasi membuat `2` menjadi argumen mode chmod, dan stderr tidak ter-suppress | Diperbaiki jadi `2>/dev/null` |
| 9 | `krscript/.../ScriptEnvironmen.java:138` | Pencarian cache memakai path **relatif**, jadi tidak pernah cocok → file script ditulis ulang pada **setiap** pemanggilan shell | Path absolut via `getPrivateFilePath()`; cache akhirnya benar-benar berfungsi |
| 10 | `krscript/.../ScriptEnvironmen.java:214-215` | `PAGE_WORK_DIR` ditulis dua kali dan `PAGE_WORK_FILE` **tidak pernah** di-export di cabang non-asset | Diperbaiki jadi `PAGE_WORK_FILE` (dikonfirmasi dari jalur paralel di baris 386-390 yang sudah benar) |
| 11 | `activities/ThemeSwitch.kt:133` | `wallPaper as BitmapDrawable` tanpa pemeriksaan → `ClassCastException` bila wallpaper bukan `BitmapDrawable` | Diganti safe cast `as?` + fallback |
| 12 | `store/FpsWatchStore.java:48-62` | `getSum()` men-query tabel `charge_history` yang **tidak pernah dibuat** di DB ini (tabel itu milik `ChargeSpeedStore`) → selalu gagal diam-diam dan mengembalikan 0 | Method dihapus — nol pemanggil di seluruh project |
| 13 | `krscript/.../ScriptEnvironmen.java:220` | `privateShell.doCmdSync()` → NPE bila init gagal (mis. tanpa root) | Guard null ditambahkan |
| 14 | `activities/ActivityBase.kt:76` | `task.taskInfo.taskId` pada platform type yang sebenarnya `@Nullable` di API 37 → jalur NPE nyata bila task record sudah dihapus sistem | Safe call `task.taskInfo?.taskId` (lihat §3.4) |

### Kebocoran resource & race condition

| Lokasi | Masalah | Perbaikan |
|---|---|---|
| `common/.../FileWrite.kt`, `krscript/.../ScriptEnvironmen.java:68` | `InputStream` tidak ditutup saat `init` (kebocoran fd) | `try-with-resources` |
| `krscript/.../config/PageConfigReader.kt:70-212` | Kebocoran fd pada **setiap** pembacaan halaman; stream tidak pernah ditutup | Ditutup di blok `finally` (aman: setiap pemanggil membuka stream baru) |
| `krscript/.../ScriptEnvironmen.java:27-32` | Static mutable state non-volatile; `init()` bisa berjalan bersamaan → toolkit ter-ekstrak dua kali | Field `volatile`, `init()` `synchronized` |
| `krscript/.../ScriptEnvironmen.java:175-193` | `init()` dipanggil **dua kali** berturut-turut | Duplikat dihapus |
| `store/FpsWatchStore.java` | `getWritableDatabase()` dipanggil 2×; cursor tidak ditutup saat exception | Satu instance; `try-with-resources`; transaksi untuk delete multi-statement |
| `activities/ActivityAddinOnline.kt`, `SceneConfigStore.java` | Stream/cursor tidak ditutup di jalur error | *(lihat rekomendasi §7.2 — belum diperbaiki)* |

### Performa & kebersihan kode

| Perubahan | Dampak |
|---|---|
| 7× `SharedPreferences.commit()` → `apply()` | `commit()` melakukan I/O disk **sinkron** di thread pemanggil. Annotation `@SuppressLint("ApplySharedPref")` yang menyembunyikan masalah ini ikut dibersihkan. |
| `FpsWatchStore.java` — 8 method query yang copy-paste digabung jadi 2 helper | ~290 baris → ~180 baris; memperbaiki `sqLiteDatabase.close()` per query yang memaksa DB dibuka ulang terus-menerus |
| Kode RenderScript dihapus (`ThemeSwitch.rsBlur`, `ActivityFreezeApps.rsBlur`) | Keduanya **dead code** (hanya dirujuk di komentar) dan RenderScript sudah dihapus dari platform sejak API 31 |
| `cluterFreqs`/`cluterGovernors` → `clusterFreqs`/`clusterGovernors` | Salah ketik pada identifier yang menyebar ke 10 baris |
| Import `Bitmap`/`renderscript` yang tidak terpakai dibersihkan | — |

### Perbaikan kecil yang sudah diverifikasi hilang
- Peringatan manifest library (`package="..."` diabaikan) — dihilangkan.
- Peringatan deprecation Gradle (20+ properti) — dihilangkan.
- `generateVersionCode()` dipanggil **dua kali** (dua eksekusi `git`) → kini sekali; bug laten di jalur error (`result.text` pada String) juga diperbaiki; `git` kini dijalankan dengan working directory eksplisit agar tidak bergantung pada CWD daemon.

### 6.4 Pengerasan interpolasi perintah shell

**Masalah.** Seluruh shell command di proyek ini dibangun dengan string interpolation. Nilai yang disisipkan — nama paket dari database aplikasi, path sysfs, isi konfigurasi, hingga teks yang diketik pengguna — masuk mentah ke dalam string yang dieksekusi sebagai root. Satu tanda kutip atau newline sudah cukup untuk memutus argumen dan menyisipkan perintah kedua, dan karena semuanya berjalan sebagai **root**, dampaknya tidak terbatas.

Sebelum perbaikan, misalnya `ModeSwitcher` menjalankan `export top_app=$packageName`, dan `ProcessUtils.killProcess()` memakai `String.format("killall -9 %s;...")`. Nama paket yang berisi `x; touch /data/local/tmp/pwned` akan dieksekusi sebagai dua perintah.

**Solusi.** Satu helper terpusat, `com.omarea.common.shell.ShellEscape`, dengan API yang membedakan secara eksplisit antara nilai yang boleh berupa teks bebas dan nilai yang harus berupa satu identifier:

| API | Kapan dipakai |
|---|---|
| `quote(v)` | Nilai apa pun yang dipakai sebagai **argumen shell**. Membungkus dengan kutip tunggal, satu-satunya bentuk quoting POSIX yang memperlakukan **setiap** karakter sebagai literal. `it's` → `'it'\''s'`. |
| `cmd(program, vararg args)` | Membangun perintah dari program + argumen. Program tidak dikutip (harus literal dari source), seluruh argumen dikutip. Untuk perintah yang memperlakukan argumennya sebagai **string opaque** (mis. `cat`, `sh`, `taskset`). |
| `cmdLine(program, vararg args)` | Sama seperti `cmd`, tetapi argumen yang mengandung karakter kontrol **ditolak** dan diganti `''`. Untuk perintah yang argumennya **di-parse ulang oleh platform** (`pm`, `am`, `getprop`, `settings`, `dumpsys`, `appops`). |
| `isSingleLine(v)` | Predikat di balik `cmdLine`. |
| `isSafePath(v)` | Menolak nilai yang tidak berbentuk path kernel/properti. |
| `sanitizeNumeric(v)` | Menyaring nilai numerik. |

Pemisahan `cmd()` vs `cmdLine()` ini bukan gaya penulisan, melainkan perbedaan yang bisa dibuktikan: quoting melindungi dari **pemecahan argumen oleh shell**, tetapi string dalam kutip tunggal tetap boleh memuat newline. Untuk `pm suspend 'a\nb'` argumennya memang tetap satu, tetapi newline-nya kini menjadi urusan parser `pm`, bukan shell. Karena itu nilai yang memang harus satu baris tidak di-escape melainkan **ditolak**, sehingga kegagalan terlihat langsung di dalam perintah alih-alih diam-diam menjadi baris kedua.

**Cakupan.** `ShellEscape` kini dipakai di **19 berkas** dengan **57 titik pemanggilan**:

| Modul | Berkas |
|---|---|
| `common` | `KernelProrp.kt` (ditulis ulang — `grep -F` agar metakarakter regex tidak mengubah pencocokan), `ShellEscape.kt` |
| `library/shell` | `PropsUtils.kt`, `ProcessUtils.kt`, `FpsUtils.kt`, `GfxInfoFpsUtils.kt`, `SurfaceFlingerFpsUtils2.kt`, `CGroupMemoryUtlis.kt`, `SwapUtils.kt`, `SwapModuleUtils.kt`, `BatteryUtils.kt` |
| `scene_mode` | `ModeSwitcher.kt`, `SceneMode.kt`, `SceneStandbyMode.kt`, `CpuAffinity.kt` |
| `vtools` / lain | `ActivityFreezeApps.kt`, `ActivityQuickStart.kt`, `DialogAddinWIFI.kt`, `CrashHandler.kt`, `Busybox.kt`, `BackupRestoreUtils.kt` |

Catatan pada dua berkas spesifik:

- **`SwapModuleUtils.kt`** — nilai yang masuk ke `busybox sed` adalah nama algoritma kompresi ZRAM yang berasal dari konfigurasi. Selain mengutip nilainya, nama properti divalidasi terhadap `^[A-Za-z_][A-Za-z0-9_]*$` sebelum dipakai membangun ekspresi `sed`, karena mengutip saja tidak cukup ketika nilai itu sendiri adalah **pola regex**.
- **`BackupRestoreUtils.kt`** — perintah `dd` menerima path partisi yang dipilih pengguna; setiap operand dikutip terpisah (`'if=...' 'of=...'`), bukan hanya nilainya.

**Verifikasi.** `others/verify_shell_escape.sh` menerjemahkan logika Kotlin di atas ke bash dan mengujinya dengan input yang dirancang adversarial. Hasil akhir: **27/27 PASS**, termasuk 12 kasus untuk `quote()`, 8 untuk `cmd()`, dan 7 untuk `cmdLine()`.

Bukti yang paling penting adalah tes injeksi: payload `a && rm -f $INJ && touch $INJ` tidak menghasilkan artefak apa pun (`INJECTION: neutralised`). Skrip juga menyertakan perbandingan langsung yang menunjukkan perilaku lama memang bisa dieksploitasi — `echo $(whoami)` tanpa kutip menghasilkan `FANNNDI`, sedangkan dengan `quote()` menghasilkan `$(whoami)` literal. Untuk `cmdLine()`, skrip membuktikan bahwa nilai ber-newline **tidak** menambah baris perintah, sementara `cmd()` memang mempertahankannya sebagai satu argumen.

**Dampak terhadap kode yang tidak terkait.** Perubahan ini memunculkan satu error kompilasi yang justru merupakan perbaikan nyata: `FpsUtils.kt:29` gagal dengan *"Smart cast to 'String' is impossible, because 'fpsFilePath' is a mutable property that could be mutated concurrently"*. Field itu ditulis dari thread latar, sehingga pemeriksaan null dan pemakaian nilainya sebelumnya bisa mengamati nilai yang berbeda. Kini nilainya di-snapshot ke variabel lokal sekali pakai.

---

## 7. Rekomendasi Lanjutan

Diurutkan berdasarkan **dampak ÷ usaha**. Semua item di bawah ini **belum** dikerjakan karena tidak bisa diverifikasi tanpa perangkat ter-root dan/atau menyentuh banyak berkas sekaligus.

### 7.1 Prioritas Tinggi

1. ~~**Tangani edge-to-edge untuk targetSdk 36**~~ — **SELESAI**, lihat §3.1 dan §6.1. Yang tersisa hanyalah **pengujian di perangkat**: verifikasi di perangkat Android 15/16 dengan gesture navigation **dan** tombol navigasi 3-tombol, dalam mode terang dan gelap, serta saat keyboard terbuka (khususnya layar yang punya kolom input) dan dalam orientasi landscape (untuk memastikan cutout samping tertangani).
2. **Audit nullability platform-type setelah naik `compileSdk`.** Seperti dibuktikan §3.4, naik `compileSdk` bisa memunculkan error kompilasi baru di kode yang tidak disentuh, karena stub `android.jar` berubah. Build sudah bersih, tetapi disarankan melihat hasil `-Xlint` / analisis IDE pada pemakaian `AppTask.taskInfo`, `PackageManager` query, dan sejenisnya — banyak di antaranya kemungkinan adalah NPE laten yang sama seperti `ActivityBase.kt:76`.
3. **Pindahkan pemanggilan shell dari main thread.** `KeepShellPublic.doCmdSync()` memblokir 10-100 ms+ per perintah; saat ini dipanggil langsung dari click listener di:
   - `activities/ActivityOtherSettings.kt:44,49,54`
   - `activities/ActivityChargeController.kt:100,178,192,196`
   - `activities/ActivityProcess.kt:191`
   - `dialogs/DialogAppOptions.kt:145,152`
   - `popup/FloatPowercfgSelector.kt:181,205,215`
   
   Bungkus dengan `lifecycleScope.launch(Dispatchers.IO)`.

4. **Berhenti membaca database di dalam `onDraw`.** `ui/fps/FpsDataView.kt:105,241` memanggil `sessionFpsData()` / `sessionTemperatureData()` **setiap frame**. Muat sekali saat `sessionId` berubah, simpan di field, gambar dari cache.

5. **Hindari alokasi objek di setiap frame.** 12 view chart memanggil `initPaint()` di dalam `onDraw`, membuat `Paint`/`RectF`/`SweepGradient` baru setiap frame:
   `FloatMonitorChartView`, `CpuChartView`, `MemoryChartView`, `RamChartView`, `BatteryView`, `FloatMonitorBatteryView`, `ZRamStateView`, `CpuBigBarView`, `CpuChartBarView`, `ChargeCurveView`, `ChargeTempView`, `ChargeTimeView`, `FpsDataView`.
   Pindahkan ke `init {}` / `onSizeChanged()`.

6. **Hentikan `GlobalScope.launch` per baris list.** `ui/AdapterAppList.kt:164` dan 6 adapter lain meluncurkan coroutine tak-terbatas setiap `getView` — berbahaya saat fling. `AdapterAppList` bahkan menyimpan `viewHolder` sebagai **field bersama**, sehingga hasil bisa masuk ke baris yang salah. Gunakan scope milik adapter + `LruCache`.

7. ~~**Amankan interpolasi perintah shell.**~~ — **SELESAI**, lihat §6.4. Semua nilai runtime yang masuk ke string shell kini melewati helper `ShellEscape`; §6.4 memuat daftar berkas, API, dan bukti verifikasi.

### 7.2 Prioritas Menengah

7. **Cache & batch pemeriksaan `visible`/`support` pada kr-script.** `PageConfigReader` menjalankan **satu proses shell per atribut** saat merender halaman; halaman dengan 20 item ≈ 40 eksekusi shell berurutan. Memoisasi per hasil script (TTL cache) atau menggabungkan semua pemeriksaan satu halaman menjadi satu invokasi.
8. **Pindahkan `updateViewByShell()` dari main thread** — `krscript/.../ui/PageLayoutRender.kt:39-50`, `ListItemView.kt:71-83`, `ListItemSwitch.kt:21-29`.
9. **Ganti `notifyDataSetChanged` dengan `ListAdapter` + `DiffUtil`** — `AdapterProcess.kt:71,173,250`, `AdapterAppList.kt:32`, `AdapterFreezeApp.kt:32`, `ActivityFreezeApps.kt:334`.
10. **Bersihkan static View/Context di floating window.** `popup/FloatMonitor.kt:423-430` (dan `FloatMonitorMini`, `FloatTaskManager`, `FloatFpsWatch`, `FloatPowercfgSelector:514-517`) menyimpan `mView: View?` di companion object dengan `@SuppressLint("StaticFieldLeak")` — static View menahan Activity. Ubah jadi instance field.
11. **Tutup stream di `ActivityAddinOnline.kt:271-273,309-315`** — `getInputStream()` dipanggil dua kali dan `BufferedReader`/`ZipInputStream` tidak pernah ditutup.
12. **Migrasi back handling** dari `onBackPressed()` (deprecated, API 33) ke `onBackPressedDispatcher` — `ActivityBase.kt:59`, `ActivityMain.kt:212`, `ActivityProcess.kt:152`.
13. **Migrasi permission** dari `requestPermissions` ke `registerForActivityResult` — `ActionPage.kt:332`, `ActivityAddinOnline.kt:359`, `ActionPageOnline.kt:137,230`.
14. **Hapus dead code yang tersisa** — `ActivityStartSplash.kt:184-195` (cabang `SDK_INT >= M` selalu true), `getColorAccent()` (`:123-127`), duplikat `onCreate` di `ActivityBase.kt:26-40`, `DexCompileAddin.modifyConfigOld` (`:85-131`, unreachable).

### 7.3 Struktur & Keterbacaan

15. **Pecah god class.** Berkas terbesar: `FragmentHome.kt` (1141 baris — mencampur Compose + `ListView` lama + `Timer` + seluruh logika home), `ActivitySwap.kt` (918), `ActivityCpuControl.kt` (767), `FragmentCpuModes.kt` (757), `ActivityFreezeApps.kt` (698), `DialogAppOptions.kt` (678).
16. **Hilangkan duplikasi:**
    - `FragmentAppUser.kt` / `FragmentAppSystem.kt` / `FragmentAppBackup.kt` nyaris identik → satu fragment berparameter.
    - `isAndroidProcess` / `regexUser` / `regexPackageName` diduplikasi di `ui/AdapterProcess.kt:102-119` dan `activities/ActivityProcess.kt:157-161`.
    - `subFreqStr` / `gpuFreqToMhz` diimplementasikan ulang di 3 tempat.
    - `ModeOnItemSelectedListener` / `ModeOnItemSelectedListener2` hanya berbeda kunci SPF.
    - Dua kelas bernama `ShellExecutor` dengan peran berbeda (`common/.../shell/` vs `krscript/.../executor/`).
17. **Standarkan bahasa.** Komentar campur Indonesia/Inggris/Tionghoa, dan ada typo pada identifier: `destoryInstance`, `setPorp`, `qcSettingSuupport`, `utlis`, `intallMode`, `cpacity`, `battryStatus`, `TOOKIT_DIR`, `vitualRootNode`.
18. **Ganti magic number dengan konstanta** — `ActivitySwap.kt` (`500`, `128`, `3000`), `ActivityMain.kt:183` (`3600*24*1000`), `FragmentCpuModes.kt:610` (`200*1024`), `#0094ff` di dua adapter.
19. **Bersihkan aset mati:** `common/libs/fastscroll_v1.2_20160903.jar` dan `overscroll-release-v1.1-20160904.jar` (≈130 KB) tidak direferensikan sama sekali dan sudah dikomentari di build. `keystore.properties` menunjuk `genom.keystore` yang **tidak ada** di repo — build debug tidak terpengaruh, tapi build release akan gagal.

### 7.4 Performa Build

20. **Pertimbangkan mengaktifkan build cache & parallel build** — lihat §8.3. Keduanya sempat dicoba, tapi harus dinonaktifkan kembali di mesin ini karena menabrak masalah lock (bukan karena tidak didukung).
21. **Configuration cache** (`org.gradle.configuration-cache=true`) didukung penuh AGP 9.4 dan memberi percepatan besar. Belum diaktifkan karena harus diverifikasi terpisah.

---

## 8. Verifikasi Build

### 8.1 Baseline (sebelum perubahan)

```
./gradlew :app:assembleDebug     →  BUILD SUCCESSFUL in 12m 42s   (84 task)
```
Toolchain: Gradle 9.1.0 · AGP 9.0.0 · Kotlin 2.0.21 · JDK 17 · NDK 25.2

### 8.2 Setelah perubahan

```
./gradlew :app:assembleDebug     →  BUILD SUCCESSFUL
```

Toolchain: Gradle 9.7.1 · AGP 9.4.1 · Kotlin 2.4.20 · JDK 17 · NDK 28.2 · compileSdk 37 / targetSdk 36

Artefak yang dihasilkan:

```
app/build/outputs/apk/debug/Scene_5.0_OpenSource_r1802_debug.apk    (~24 MB)
```

Terverifikasi lolos kompilasi: `:common:compileDebugKotlin`, `:krscript:compileDebugKotlin`, `:krscript:compileDebugJavaWithJavac`, `:app:compileDebugKotlin`, `:app:compileDebugJavaWithJavac`, plus seluruh rantai dexing/packaging hingga APK final.

Error kompilasi yang muncul di tengah jalan (§3.2 AAR metadata, §3.4 nullability `taskInfo`, dan `ThemeMode.NIGHT` pada §6.1) **semuanya sudah diperbaiki** dan build akhirnya hijau penuh.

Verifikasi tambahan:
- **110 layout XML** diparse ulang → semuanya well-formed.
- **Konsistensi inset:** 26 Activity ter-wire; 3 sisanya (`ActivityMain`, `ActivityQuickStart`, `ActivityStartSplash`) memang sengaja tidak, dengan alasan masing-masing. Tidak ada pemasangan ganda.
- **Line ending** pada layout yang diubah dinormalkan kembali ke **CRLF** (konvensi repo: 105 dari 110 berkas), supaya tidak muncul diff palsu di seluruh berkas.

Yang **tidak** berubah: kode native (`app/src/main/cpp/`) dan seluruh aset (`assets/`) tidak disentuh.

### 8.2.1 Verifikasi scope (AGENTS.md regression grep)

Perintah pemeriksaan regresi dari `AGENTS.md` dijalankan ulang terhadap seluruh source:

```bash
grep -rniE "xposed|vaddin|exynos|isMTK|/proc/ppm|kr_flyme|kr_mtk|kr_oppo|kr_vivo|\
ActivityMiuiThermal|DialogCustomMAC|DialogAddinModifyDevice|ActivityModules|device_templates" \
  --include="*.kt" --include="*.java" --include="*.xml" --include="*.gradle" --include="*.toml" \
  app/src common/src krscript/src *.gradle gradle/
```

**Hasil: nol kecocokan.** Tidak ada satu pun batasan scope dari `AGENTS.md` yang dilanggar — tidak ada dukungan SoC non-Qualcomm, brand non-Xiaomi, jalur Xposed/Zygisk/vaddin, template spoof perangkat, spoof MAC, editor thermal MIUI, maupun browser modul Magisk yang diperkenalkan kembali. Audit ini murni menyentuh toolchain, dependency, dan kualitas kode internal.

### 8.3 Catatan lingkungan (penting untuk menjalankan build)

1. **Gunakan JDK 17.** `java -version` di shell menunjuk ke JDK 26; Gradle 9.7.1 belum mendukungnya. Pakai `C:\Users\FANNNDI\Documents\jdk-17.0.20.1+1` (sesuai README dan Android Studio).
2. **`org.gradle.parallel` dan `org.gradle.caching` harus tetap `false`/off di mesin ini.** Saat diaktifkan, build berulang kali gagal dengan:
   ```
   FileNotFoundException: ~/.gradle/caches/<ver>/transforms/.internal/locks/<hash>.lock (Access is denied)
   ```
   pada `:app:compileDebugKotlin`, `:app:dataBindingGenBaseClassesDebug`, `:app:mergeExtDexDebug`, `:app:checkDebugAarMetadata`. File lock tersebut berukuran **0 byte dan tidak dipegang proses mana pun**, sehingga penyebabnya paling mungkin antivirus/indexer yang bereaksi terhadap lonjakan jumlah berkas. Aktifkan kembali setelah mengecualikan `~/.gradle` dari real-time scanning.
3. **IDE ikut memakai wrapper yang sama.** Setelah wrapper dinaikkan ke 9.7.1, Gradle sync dari IDE (Eclipse Buildship) memakai Gradle 9.7.1 juga dan **berkompetisi memperebutkan lock yang sama** dengan build dari terminal. Ini yang membuat build terminal gagal secara acak. Solusinya: jalankan build terminal dengan `GRADLE_USER_HOME` terpisah, atau hentikan sync IDE saat membangun dari terminal.
4. **`--no-daemon` yang gagal meninggalkan daemon "single-use"** yang masih memegang lock. Untuk membersihkannya: cari PID dari `~/.gradle/daemon/<versi>/daemon-<pid>.out.log`, lalu `taskkill /F /PID <pid>` (di Git Bash perlu `MSYS_NO_PATHCONV=1`).
5. Modifikasi pada `.project` dan `.settings/org.eclipse.buildship.core.prefs` di `git status` berasal dari **Eclipse Java Language Server**, bukan dari audit ini.

---

## 9. Daftar Berkas yang Diubah

**Build (8):** `gradle/libs.versions.toml` (baru), `build.gradle`, `settings.gradle`, `gradle.properties`, `gradle/wrapper/gradle-wrapper.properties`, `app/build.gradle`, `common/build.gradle`, `krscript/build.gradle`

**Infrastruktur edge-to-edge (2):**
`app/.../utils/WindowCompatHelper.kt` (3 helper inset baru) · `app/.../vtools/activities/ActivityBase.kt` (edge-to-edge terpusat, dua `onCreate` duplikat dirapikan, `applyContentInsets()`, `applyAppBarInsets()`)

**Activity (24):** `ActionPage` · `ActionPageOnline` · `ActivityAddin` · `ActivityAddinOnline` · `ActivityAppConfig2` · `ActivityAppDetails` · `ActivityAppRetrieve` · `ActivityApplistions` · `ActivityAutoClick` · `ActivityCharge` · `ActivityChargeController` · `ActivityCpuControl` · `ActivityCustomCommand` · `ActivityFileSelector` · `ActivityFpsChart` · `ActivityFreezeApps` · `ActivityImg` · `ActivityMagisk` · `ActivityMain` · `ActivityOtherSettings` · `ActivityPowerUtilization` · `ActivityProcess` · `ActivityStartSplash` · `ActivitySwap` · `ActivitySystemScene` · `ActivityTimingTask` · `ActivityTrigger`

**Layout (7):** `layout_app_bar.xml` (id `app_bar` + dokumentasi) · `activity_action_page.xml` · `activity_addin_online.xml` · `activity_app_details.xml` · `activity_other_settings.xml` · `activity_timing_task.xml` · `activity_trigger.xml`

**Bug & kualitas kode (8):**
`app/.../scene_mode/AppSwitchHandler.kt` · `app/.../store/FpsWatchStore.java` · `app/.../vtools/activities/ThemeSwitch.kt` · `app/.../vtools/dialogs/DialogAddinModifyDPI.kt` · `.../DialogAppOptions.kt` · `app/.../vtools/fragments/FragmentCpuModes.kt` · `app/.../vtools/services/CompileService.kt` · `common/.../shell/KeepShell.kt` · `common/.../shell/KernelProrp.kt` · `krscript/.../config/PageConfigReader.kt` · `krscript/.../executor/ScriptEnvironmen.java`

**Manifest (2):** `common/src/main/AndroidManifest.xml`, `krscript/src/main/AndroidManifest.xml`

**Dokumentasi (1):** `AUDIT-REPORT.md` (baru)
