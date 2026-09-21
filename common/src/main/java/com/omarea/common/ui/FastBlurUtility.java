package com.omarea.common.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptIntrinsicBlur;
import android.view.View;

public class FastBlurUtility {

    /**
     * Get a blurred background bitmap
     *
     * @param activity activity to capture for the blurred background
     * @return the blurred background bitmap
     */
    public static Bitmap getBlurBackgroundDrawer(Activity activity) {
        Bitmap bmp = takeScreenShot(activity);
        return startBlurBackground(bmp);

        /*
        Long startTime = System.currentTimeMillis();
        Log.d("Scene", "takeScreenShot");
        Bitmap bmp = takeScreenShot(activity);
        Log.d("Scene", "startBlurBackground" + (System.currentTimeMillis() - startTime));
        startBlurBackground(bmp);
        // Bitmap bitmap = startBlurBackground(bmp);
        Log.d("Scene", "Blur Completed!"+ (System.currentTimeMillis() - startTime));
        Bitmap bitmap = blur(bmp, activity);
        Log.d("Scene", "Blur Completed!"+ (System.currentTimeMillis() - startTime));
        return bitmap;
        */
    }


    // Measured: RenderScript blur actually performs worse...
    private static Bitmap blur(Bitmap bitmap, Context context) {
        if (bitmap == null) {
            return bitmap;
        }

        // Apply Gaussian blur with RenderScript
        Bitmap output = Bitmap.createBitmap(bitmap); // Create the output bitmap
        RenderScript rs = RenderScript.create(context); // Create a RenderScript instance
        ScriptIntrinsicBlur gaussianBlue = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs)); //
        // Create the Gaussian blur script
        Allocation allIn = Allocation.createFromBitmap(rs, bitmap); // Allocate input memory
        Allocation allOut = Allocation.createFromBitmap(rs, output); // Allocate output memory
        float radius = 10f; // Blur radius
        gaussianBlue.setRadius(radius); // Set the blur radius; range 0f < radius <= 25f
        gaussianBlue.setInput(allIn); // Set the input allocation
        gaussianBlue.forEach(allOut); // Run the blur and write into the output allocation
        allOut.copyTo(output); // Copy the output allocation into the bitmap
        rs.destroy();
        //rs.releaseAllContexts(); // Destroy the RenderScript object; on API>=23 use rs.releaseAllContexts()
        return output;
    }

    /**
     * Take a screenshot
     *
     * @param activity activity to capture
     * @return the screenshot bitmap
     */
    private static Bitmap takeScreenShot(Activity activity) {
        View view = activity.getWindow().getDecorView();
        view.setDrawingCacheEnabled(true);
        view.buildDrawingCache();
        Bitmap b1 = view.getDrawingCache();

        if (b1 != null) {
            // Get the screen width and height
            int width = activity.getResources().getDisplayMetrics().widthPixels;
            int height = activity.getResources().getDisplayMetrics().heightPixels;

            try {
                Bitmap bmp = Bitmap.createBitmap(b1, 0, 0, width, height);
                view.destroyDrawingCache();
                return bmp;
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    // Dim the bitmap
    public static Bitmap getDimmedBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }

        int width, height;
        height = bitmap.getHeight();
        width = bitmap.getWidth();
        Bitmap bmpGrayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmpGrayscale);
        Paint paint = new Paint();
        paint.setAntiAlias(true);
        ColorMatrix cm = new ColorMatrix();
        // cm.setSaturation(0);
        float contrast = 0.95f;
        cm.set(new float[]{
                contrast, 0, 0, 0, 0,
                0, contrast, 0, 0, 0,
                0, 0, contrast, 0, 0,
                0, 0, 0, 1, 0});
        ColorMatrixColorFilter f = new ColorMatrixColorFilter(cm);
        paint.setColorFilter(f);
        c.drawBitmap(bitmap, 0, 0, paint);
        return bmpGrayscale;
    }

    private static Bitmap startBlurBackground(Bitmap bkg) {
        if (bkg == null) {
            return null;
        }
        float radius = 8; // Blur amount

        Bitmap overlay = fastBlur(small(bkg), (int) radius);
        return big(getDimmedBitmap(overlay));
    }

    /**
     * Scale up the bitmap
     *
     * @param bitmap bitmap to scale up
     * @return the scaled-up bitmap
     */
    private static Bitmap big(Bitmap bitmap) {
        Matrix matrix = new Matrix();
        matrix.postScale(4f, 4f);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    /**
     * Scale down the bitmap
     *
     * @param bitmap bitmap to scale down
     * @return the scaled-down bitmap
     */
    private static Bitmap small(Bitmap bitmap) {
        Matrix matrix = new Matrix();
        matrix.postScale(0.10f, 0.10f);
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
    }

    /**
     * Blur the bitmap
     *
     * @param sentBitmap bitmap to blur
     * @param radius     blur amount
     * @return the blurred bitmap
     */
    private static Bitmap fastBlur(Bitmap sentBitmap, int radius) {
        Bitmap bitmap = sentBitmap.copy(sentBitmap.getConfig(), true);

        if (radius < 1) {
            return (null);
        }

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();

        int[] pix = new int[w * h];
        bitmap.getPixels(pix, 0, w, 0, 0, w, h);

        int wm = w - 1;
        int hm = h - 1;
        int wh = w * h;
        int div = radius + radius + 1;

        int[] r = new int[wh];
        int[] g = new int[wh];
        int[] b = new int[wh];
        int rsum, gsum, bsum, x, y, i, p, yp, yi, yw;
        int[] vmin = new int[Math.max(w, h)];

        int divsum = (div + 1) >> 1;
        divsum *= divsum;
        int[] dv = new int[256 * divsum];
        for (i = 0; i < 256 * divsum; i++) {
            dv[i] = (i / divsum);
        }

        yw = yi = 0;

        int[][] stack = new int[div][3];
        int stackpointer;
        int stackstart;
        int[] sir;
        int rbs;
        int r1 = radius + 1;
        int routsum, goutsum, boutsum;
        int rinsum, ginsum, binsum;

        for (y = 0; y < h; y++) {
            rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
            for (i = -radius; i <= radius; i++) {
                p = pix[yi + Math.min(wm, Math.max(i, 0))];
                sir = stack[i + radius];
                sir[0] = (p & 0xff0000) >> 16;
                sir[1] = (p & 0x00ff00) >> 8;
                sir[2] = (p & 0x0000ff);
                rbs = r1 - Math.abs(i);
                rsum += sir[0] * rbs;
                gsum += sir[1] * rbs;
                bsum += sir[2] * rbs;
                if (i > 0) {
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                } else {
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                }
            }
            stackpointer = radius;

            for (x = 0; x < w; x++) {

                r[yi] = dv[rsum];
                g[yi] = dv[gsum];
                b[yi] = dv[bsum];

                rsum -= routsum;
                gsum -= goutsum;
                bsum -= boutsum;

                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];

                routsum -= sir[0];
                goutsum -= sir[1];
                boutsum -= sir[2];

                if (y == 0) {
                    vmin[x] = Math.min(x + radius + 1, wm);
                }
                p = pix[yw + vmin[x]];

                sir[0] = (p & 0xff0000) >> 16;
                sir[1] = (p & 0x00ff00) >> 8;
                sir[2] = (p & 0x0000ff);

                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];

                rsum += rinsum;
                gsum += ginsum;
                bsum += binsum;

                stackpointer = (stackpointer + 1) % div;
                sir = stack[(stackpointer) % div];

                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];

                rinsum -= sir[0];
                ginsum -= sir[1];
                binsum -= sir[2];

                yi++;
            }
            yw += w;
        }
        for (x = 0; x < w; x++) {
            rinsum = ginsum = binsum = routsum = goutsum = boutsum = rsum = gsum = bsum = 0;
            yp = -radius * w;
            for (i = -radius; i <= radius; i++) {
                yi = Math.max(0, yp) + x;

                sir = stack[i + radius];

                sir[0] = r[yi];
                sir[1] = g[yi];
                sir[2] = b[yi];

                rbs = r1 - Math.abs(i);

                rsum += r[yi] * rbs;
                gsum += g[yi] * rbs;
                bsum += b[yi] * rbs;

                if (i > 0) {
                    rinsum += sir[0];
                    ginsum += sir[1];
                    binsum += sir[2];
                } else {
                    routsum += sir[0];
                    goutsum += sir[1];
                    boutsum += sir[2];
                }

                if (i < hm) {
                    yp += w;
                }
            }
            yi = x;
            stackpointer = radius;
            for (y = 0; y < h; y++) {
                pix[yi] = (0xff000000 & pix[yi]) | (dv[rsum] << 16) | (dv[gsum] << 8) | dv[bsum];

                rsum -= routsum;
                gsum -= goutsum;
                bsum -= boutsum;

                stackstart = stackpointer - radius + div;
                sir = stack[stackstart % div];

                routsum -= sir[0];
                goutsum -= sir[1];
                boutsum -= sir[2];

                if (x == 0) {
                    vmin[y] = Math.min(y + r1, hm) * w;
                }
                p = x + vmin[y];

                sir[0] = r[p];
                sir[1] = g[p];
                sir[2] = b[p];

                rinsum += sir[0];
                ginsum += sir[1];
                binsum += sir[2];

                rsum += rinsum;
                gsum += ginsum;
                bsum += binsum;

                stackpointer = (stackpointer + 1) % div;
                sir = stack[stackpointer];

                routsum += sir[0];
                goutsum += sir[1];
                boutsum += sir[2];

                rinsum -= sir[0];
                ginsum -= sir[1];
                binsum -= sir[2];

                yi += w;
            }
        }

        bitmap.setPixels(pix, 0, w, 0, 0, w, h);

        return (bitmap);
    }
}

