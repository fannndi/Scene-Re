package com.omarea.utils

import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.Window
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

object WindowCompatHelper {
    fun getRealDisplaySize(windowManager: WindowManager): Point {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Point(bounds.width(), bounds.height())
        } else {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val point = Point()
            @Suppress("DEPRECATION")
            display.getRealSize(point)
            point
        }
    }

    fun overlayWindowType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }
    }

    fun applyEdgeToEdge(window: Window, lightStatusBars: Boolean, lightNavBars: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = lightStatusBars
        controller.isAppearanceLightNavigationBars = lightNavBars
    }

    fun setSystemBarColors(window: Window, statusBarColor: Int?, navigationBarColor: Int?) {
        if (statusBarColor != null) {
            @Suppress("DEPRECATION")
            window.statusBarColor = statusBarColor
        }
        if (navigationBarColor != null) {
            @Suppress("DEPRECATION")
            window.navigationBarColor = navigationBarColor
        }
    }

    /**
     * Applies system bar insets as padding to [view].
     *
     * WHY THIS EXISTS
     * ---------------
     * With targetSdk 36, edge-to-edge is mandatory on Android 15+ and there is no
     * opt-out: `R.attr#windowOptOutEdgeToEdgeEnforcement` is "deprecated and disabled"
     * for API 36 targets, and `Window.setStatusBarColor()` / `setNavigationBarColor()`
     * became no-ops. Consequently the app draws behind the status and navigation bars,
     * so every top-level screen has to inset its own content or the first/last rows
     * end up underneath a system bar.
     *
     * `android:fitsSystemWindows="true"` cannot be used as the fix here: it only
     * consumes insets when the window is *not* laying out edge-to-edge, which is why
     * the existing usages of it silently stopped working.
     *
     * This uses [ViewCompat.setOnApplyWindowInsetsListener] plus explicit padding
     * rather than [ViewCompat.setRootWindowInsets], because the latter only exists in
     * newer androidx-core versions and this project supports appcompat 1.8.0.
     *
     * @param view the root view of the screen (usually the content view)
     * @param top pad the top by the status-bar / display-cutout inset
     * @param bottom pad the bottom by the navigation-bar / gesture-handle inset
     * @param leftRight when true, also pad horizontally. Pass false when the view's
     *   own padding already provides horizontal insets (e.g. lists), but note that on
     *   landscape with a display cutout the side inset must still be applied.
     * @param includeIme when true, also pad the bottom by the IME inset so text fields
     *   stay visible while the keyboard is open
     */
    fun applySystemBarInsets(
        view: View,
        top: Boolean = true,
        bottom: Boolean = true,
        leftRight: Boolean = true,
        includeIme: Boolean = true,
        extraTopPadding: Int = 0,
        extraBottomPadding: Int = 0
    ) {
        val initialLeft = view.paddingLeft
        val initialTop = view.paddingTop
        val initialRight = view.paddingRight
        val initialBottom = view.paddingBottom

        // Inset dispatch repeats on every keyboard show/hide and rotation change, so
        // remember what we applied last and only add the difference. Assigning the
        // absolute value would accumulate padding on each dispatch.
        var previousLeft = 0
        var previousTop = 0
        var previousRight = 0
        var previousBottom = 0

        ViewCompat.setOnApplyWindowInsetsListener(view) { target, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            // Below API 30 the IME is reported as part of the system bars, so only
            // query it separately where the two types are properly distinct.
            val ime = if (includeIme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            } else {
                Insets.NONE
            }

            val startInset = if (leftRight) bars.left else 0
            val endInset = if (leftRight) bars.right else 0
            val topInset = if (top) bars.top + extraTopPadding else extraTopPadding
            // While the IME is visible its inset is normally larger than the
            // navigation-bar inset, so take the larger of the two rather than summing
            // them (summing would double-pad in landscape).
            val bottomInset = if (bottom) {
                maxOf(bars.bottom, ime.bottom) + extraBottomPadding
            } else {
                ime.bottom + extraBottomPadding
            }

            target.setPadding(
                initialLeft + startInset - previousLeft,
                initialTop + topInset - previousTop,
                initialRight + endInset - previousRight,
                initialBottom + bottomInset - previousBottom
            )
            previousLeft = startInset
            previousTop = topInset
            previousRight = endInset
            previousBottom = bottomInset
            windowInsets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /**
     * Convenience wrapper for screens whose root layout is a plain container and
     * which have no dedicated insets-consuming child (tab bar, app bar, FAB, ...).
     * It simply insets the root, which is correct for ordinary scrolling pages.
     */
    fun applyEdgeToEdgeInsetsToRoot(root: View) {
        applySystemBarInsets(root, top = true, bottom = true, leftRight = true)
    }

    /**
     * Inset helper for the shared `layout_app_bar.xml` app bar.
     *
     * The bar is included by every "back arrow" screen. Because the window is
     * edge-to-edge (targetSdk 36), the AppBarLayout would be drawn under the status
     * bar. Padding the *AppBarLayout* (not the Toolbar) is the right place: it carries
     * the `?android:statusBarColor` background, so the status-bar strip keeps its
     * colour while the toolbar row is pushed below the inset.
     *
     * @param appBar the AppBarLayout (id `app_bar`)
     * @param toolbar the Toolbar (id `toolbar`); used to also honour a landscape
     *   display cutout on the leading/trailing edge
     */
    fun applyAppBarInsets(appBar: View?, toolbar: View?) {
        if (appBar == null) return

        val initialTop = appBar.paddingTop
        val initialBottom = appBar.paddingBottom
        // Remember the previously applied horizontal inset so repeated dispatches
        // (keyboard show/hide, rotation) do not accumulate padding.
        var previousToolbarLeft = 0
        var previousToolbarRight = 0

        ViewCompat.setOnApplyWindowInsetsListener(appBar) { target, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            target.setPadding(
                target.paddingLeft,
                initialTop + bars.top,
                target.paddingRight,
                initialBottom
            )
            // Apply the *delta* to the toolbar's horizontal padding, in case the
            // screen runs in landscape with a notch on the side edge.
            toolbar?.let { bar ->
                bar.setPadding(
                    bar.paddingLeft + (bars.left - previousToolbarLeft),
                    bar.paddingTop,
                    bar.paddingRight + (bars.right - previousToolbarRight),
                    bar.paddingBottom
                )
                previousToolbarLeft = bars.left
                previousToolbarRight = bars.right
            }
            windowInsets
        }
        ViewCompat.requestApplyInsets(appBar)
    }

    /**
     * Inset helper for screens that use the shared app bar and additionally have a
     * bottom-anchored control (a FAB, a list, ...). Insets [bottomTarget] by the
     * navigation-bar / gesture inset so it stays reachable.
     */
    fun applyBottomInset(bottomTarget: View?, extraPadding: Int = 0) {
        if (bottomTarget == null) return

        val initialBottom = bottomTarget.paddingBottom
        var previousBottom = 0

        ViewCompat.setOnApplyWindowInsetsListener(bottomTarget) { target, windowInsets ->
            val bars = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            val ime = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            } else {
                Insets.NONE
            }
            val bottomInsetNow = maxOf(bars.bottom, ime.bottom)
            target.setPadding(
                target.paddingLeft,
                target.paddingTop,
                target.paddingRight,
                initialBottom + extraPadding + bottomInsetNow - previousBottom
            )
            previousBottom = bottomInsetNow
            windowInsets
        }
        ViewCompat.requestApplyInsets(bottomTarget)
    }

    /**
     * True when the device is in landscape. Used to decide whether the horizontal
     * display-cutout inset has to be honoured (a side notch is only possible in
     * landscape).
     */
    fun isLandscape(view: View): Boolean {
        return view.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    }

    /** Margins helper used by layouts that must inset a child rather than themselves. */
    fun applyTopMargin(view: View, margin: Int) {
        val params = view.layoutParams
        if (params is ViewGroup.MarginLayoutParams) {
            params.topMargin = margin
            view.layoutParams = params
        }
    }
}
