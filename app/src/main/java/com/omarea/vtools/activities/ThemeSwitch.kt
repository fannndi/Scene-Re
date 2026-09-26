@file:Suppress("DEPRECATION")

package com.omarea.vtools.activities

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.PermissionChecker
import com.omarea.common.ui.ThemeMode
import com.omarea.store.SpfConfig
import com.omarea.utils.WindowCompatHelper
import com.omarea.vtools.R

object ThemeSwitch {
    private var globalSPF: SharedPreferences? = null

    private fun checkPermission(context: Context, permission: String): Boolean = PermissionChecker.checkSelfPermission(context, permission) == PermissionChecker.PERMISSION_GRANTED

    internal fun switchTheme(activity: Activity): ThemeMode {
        val themeMode = ThemeMode()
        if (globalSPF == null) {
            globalSPF = activity.getSharedPreferences(SpfConfig.GLOBAL_SPF, Context.MODE_PRIVATE)
        }

        val theme = globalSPF!!.getInt(SpfConfig.GLOBAL_SPF_THEME, -1)

        // Using the wallpaper as background requires external storage access (fall back to the default theme without it)
        if (theme == 10 && !(checkPermission(activity, Manifest.permission.READ_EXTERNAL_STORAGE) && checkPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE))) {
            globalSPF!!.edit().remove(SpfConfig.GLOBAL_SPF_THEME).apply()
            return switchTheme(activity)
        }

        if (theme < 0) {
            themeMode.isDarkMode = (activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES

            val themeId = when (theme) {
                -2 -> {
                    themeMode.isDarkMode = true
                    themeMode.isLightStatusBar = false
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                    R.style.AppThemeNoActionBarNight
                }
                -3 -> {
                    themeMode.isDarkMode = false
                    themeMode.isLightStatusBar = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    R.style.AppThemeWhite
                }
                else -> {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    if (themeMode.isDarkMode) {
                        themeMode.isLightStatusBar = false
                        R.style.AppThemeNoActionBarNight
                    } else {
                        themeMode.isLightStatusBar = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        R.style.AppThemeWhite
                    }
                }
            }
            if (activity is AppCompatActivity) {
                activity.getDelegate().setLocalNightMode(AppCompatDelegate.getDefaultNightMode())
            }
            activity.setTheme(themeId)

            if (themeMode.isLightStatusBar) {
                WindowCompatHelper.applyEdgeToEdge(activity.window, lightStatusBars = true, lightNavBars = true)
            }
        } else if (theme == 10) {
            val wallpaper = WallpaperManager.getInstance(activity)
            val wallpaperInfo = wallpaper.wallpaperInfo
            activity.setTheme(R.style.AppThemeWallpaper)

            // Live wallpaper
            if (wallpaperInfo != null && wallpaperInfo.packageName != null) {
                // activity.window.setBackgroundDrawable(activity.getDrawable(R.drawable.window_transparent));
                activity.window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)

                themeMode.isDarkMode = true
            } else {
                val wallpaperDrawable = wallpaper.drawable
                        ?: run {
                            themeMode.isDarkMode = true
                            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                            return themeMode
                        }

                // Dark static wallpaper
                if (isDarkColor(wallpaperDrawable)) {
                    themeMode.isDarkMode = true
                } else {
                    // Light static wallpaper
                    themeMode.isDarkMode = false
                    themeMode.isLightStatusBar = true
                    WindowCompatHelper.applyEdgeToEdge(activity.window, lightStatusBars = true, lightNavBars = true)

                    if (!(activity is ActivityMain)) {
                        WindowCompatHelper.setSystemBarColors(activity.window, null, Color.TRANSPARENT)
                    }
                }

                activity.window.setBackgroundDrawable(wallpaperDrawable)
            }

            if (themeMode.isDarkMode) {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            } else {
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            }
        }
        return themeMode
    }

    private fun isDarkColor(wallPaper: Drawable): Boolean {
        // Set the theme from the wallpaper colors
        // The system wallpaper is not guaranteed to be a BitmapDrawable
        // (it can be a ColorDrawable or a vendor-specific drawable), so an
        // unchecked cast here would crash. Fall back to light mode.
        val bitmap = (wallPaper as? BitmapDrawable)?.bitmap ?: return false
        val h = bitmap.height - 1
        val w = bitmap.width - 1

        var darkPoint = 0
        var lightPoint = 0

        // Sample point count
        val pointCount = if (h > 24 && w > 24) 24 else 1

        for (i in 0..pointCount) {
            val y = h / pointCount * i
            val x = w / pointCount * i
            val pixel = bitmap.getPixel(x, y)

            // Read the color
            val redValue = Color.red(pixel)
            val blueValue = Color.blue(pixel)
            val greenValue = Color.green(pixel)

            if (redValue > 150 && blueValue > 150 && greenValue > 150) {
                lightPoint += 1
            } else {
                darkPoint += 1
            }
        }
        return darkPoint > lightPoint
    }
}
