@file:Suppress("DEPRECATION")

package com.omarea.common.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.ImageView

@Suppress("DEPRECATION")
class BlurBackground(private val activity: Activity) {
    private var dialogBg: ImageView? = null
    private var originalW = 0
    private var originalH = 0
    private var mHandler: Handler = Handler(Looper.getMainLooper())

    private fun captureScreen(activity: Activity): Bitmap? {
        activity.window.decorView.destroyDrawingCache() // clear the screen drawing cache first (important)
        activity.window.decorView.isDrawingCacheEnabled = true
        val cache = activity.window.decorView.drawingCache ?: return null
        var bmp: Bitmap = cache
        // Get the original image dimensions
        originalW = bmp.getWidth()
        originalH = bmp.getHeight()
        // Downscale the original image to speed up the Gaussian blur that follows
        bmp = Bitmap.createScaledBitmap(bmp, originalW / 4, originalH / 4, false)
        return bmp
    }

    private fun asyncRefresh(`in`: Boolean) {
        // Fade in/out implementation
        if (`in`) {    // Fade in
            Thread {
                var i = 0
                while (i < 256) {
                    refreshUI(i) // Refresh the view on the UI thread
                    try {
                        Thread.sleep(4)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                    i += 5
                }
            }.start()
        } else {    // Fade out
            Thread {
                var i = 255
                while (i >= 0) {
                    refreshUI(i) // Refresh the view on the UI thread
                    try {
                        Thread.sleep(4)
                    } catch (e: InterruptedException) {
                        e.printStackTrace()
                    }
                    i -= 5
                }
                // After the fade-out completes, message mHandler to make the dialog background invisible
                mHandler.sendEmptyMessage(0)
            }.start()
        }
    }

    private fun runOnUiThread(runnable: Runnable) {
        mHandler.post(runnable)
    }

    private fun refreshUI(i: Int) {
        runOnUiThread(Runnable { dialogBg?.setImageAlpha(i) })
    }

    private fun hideBlur() {
        // Hide the dialog background
        asyncRefresh(false)
        System.gc()
    }

    private fun blur(bitmap: Bitmap): Bitmap? {
        // Apply a Gaussian blur to the image with RenderScript
        val output = Bitmap.createBitmap(bitmap) // Create the output bitmap
        val rs: RenderScript = RenderScript.create(activity) // Create a RenderScript instance
        val gaussianBlue: ScriptIntrinsicBlur = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs)) //
        // Create the Gaussian blur script
        val allIn: Allocation = Allocation.createFromBitmap(rs, bitmap) // Allocate the input memory
        val allOut: Allocation = Allocation.createFromBitmap(rs, output) // Allocate the output memory
        val radius = 10f // Blur radius
        gaussianBlue.setRadius(radius) // Set the blur radius; the range is 0f<radius<=25f
        gaussianBlue.setInput(allIn) // Set the input memory
        gaussianBlue.forEach(allOut) // Run the blur and write the result into the output memory
        allOut.copyTo(output) // Copy the output memory into a bitmap; mind the image size
        rs.destroy()
        //rs.releaseAllContexts(); // close the RenderScript object; on API>=23 use rs.releaseAllContexts()
        return output
    }

    private fun handleBlur() {
        dialogBg?.run {
            val captured = captureScreen(activity) ?: return
            val blurred = blur(captured) ?: return
            var bp = blurred

            // Scale the blurred image back to the original size and show it
            bp = Bitmap.createScaledBitmap(bp, originalW, originalH, false)
            setImageBitmap(bp)
            setVisibility(View.VISIBLE)
            // Fade the background in on a worker thread to avoid blocking the UI thread
            asyncRefresh(true)
        }
    }

    fun setScreenBgLight(dialog: Dialog) {
        val window: Window? = dialog.getWindow()
        val lp: WindowManager.LayoutParams
        if (window != null) {
            lp = window.getAttributes()
            lp.dimAmount = 0.2f
            window.setAttributes(lp)
        }
        handleBlur()
    }

}
