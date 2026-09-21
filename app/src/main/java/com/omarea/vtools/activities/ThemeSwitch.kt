@file:Suppress("DEPRECATION")

package com.omarea.vtools.activities

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
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

        // Using the wallpaper as the background requires external storage read permission (fall back to the default theme if not granted)
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
                // Use a Gaussian-blurred wallpaper as the window background
                // activity.window.setBackgroundDrawable(BitmapDrawable(activity.resources, rsBlur((wallPaper as BitmapDrawable).bitmap, 25, activity)))
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
        // Set the theme based on wallpaper colors
        val bitmap = (wallPaper as BitmapDrawable).bitmap
        val h = bitmap.height - 1
        val w = bitmap.width - 1

        var darkPoint = 0
        var lightPoint = 0

        // Number of sample points
        val pointCount = if (h > 24 && w > 24) 24 else 1

        for (i in 0..pointCount) {
            val y = h / pointCount * i
            val x = w / pointCount * i
            val pixel = bitmap.getPixel(x, y)

            // Get color
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

    @Suppress("DEPRECATION")
    private fun rsBlur(source: Bitmap, radius: Int, context: Context): Bitmap {
        val inputBmp = source
        val renderScript = RenderScript.create(context);

        // Allocate memory for Renderscript to work with
        //(2)
        val input = Allocation.createFromBitmap(renderScript, inputBmp);
        val output = Allocation.createTyped(renderScript, input.getType());
        //(3)
        // Load up an instance of the specific script that we want to use.
        val scriptIntrinsicBlur = ScriptIntrinsicBlur.create(renderScript, Element.U8_4(renderScript));
        //(4)
        scriptIntrinsicBlur.setInput(input);
        //(5)
        // Set the blur radius
        scriptIntrinsicBlur.setRadius(radius.toFloat());
        //(6)
        // Start the ScriptIntrinisicBlur
        scriptIntrinsicBlur.forEach(output);
        //(7)
        // Copy the output to the blurred bitmap
        output.copyTo(inputBmp);
        //(8)
        renderScript.destroy();

        return inputBmp;
    }
}
