package com.omarea.common.shared;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class BitmapUtil {
    public Bitmap temp;

    /**
     * Scales by the specified height (source is a bitmap)
     */
    public Bitmap bitmapZoomByHeight(Bitmap srcBitmap, float newHeight) {
        float scale = newHeight / (((float) srcBitmap.getHeight()));
        return bitmapZoomByScale(srcBitmap, scale, scale);
    }

    /**
     * Scales by the specified height (source is a drawable)
     */
    public Bitmap bitmapZoomByHeight(Drawable drawable, float newHeight) {
        Bitmap bitmap = drawableToBitmap(drawable);
        float scale = newHeight / (((float) bitmap.getHeight()));
        return bitmapZoomByScale(bitmap, scale, scale);
    }

    /**
     * Scales by the specified width and height scale factors
     */
    public Bitmap bitmapZoomByScale(Bitmap srcBitmap, float scaleWidth, float scaleHeight) {
        int width = srcBitmap.getWidth();
        int height = srcBitmap.getHeight();
        Matrix matrix = new Matrix();
        matrix.postScale(scaleWidth, scaleHeight);
        Bitmap bitmap = Bitmap.createBitmap(srcBitmap, 0, 0, width, height, matrix, true);
        if (bitmap != null) {
            return bitmap;
        } else {
            return srcBitmap;
        }
    }

    /**
     * Converts a drawable to a bitmap
     */
    public Bitmap drawableToBitmap(Drawable drawable) {
        int width = drawable.getIntrinsicWidth();
        int height = drawable.getIntrinsicHeight();
        Bitmap.Config config = drawable.getOpacity() != PixelFormat.OPAQUE ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
        Bitmap bitmap = Bitmap.createBitmap(width, height, config);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, width, height);
        drawable.draw(canvas);
        return bitmap;
    }

    /**
     * Converts a drawable to a bitmap
     */
    public Bitmap drawableToBitmap2(Drawable drawable) {
        BitmapDrawable bd = (BitmapDrawable) drawable;
        Bitmap bm = bd.getBitmap();
        return bm;
    }

    /**
     * Saves a bitmap as an image on the SD card
     */
    public void saveBitmapToSDCard(Bitmap bitmap, String path) {
        File file = new File(path);
        if (file.exists()) {
            file.delete();
        }
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(file);
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, ((OutputStream) fileOutputStream));// with PNG, transparent areas will not turn black

            fileOutputStream.close();
            System.out.println("----------save success-------------------");
        } catch (Exception v0) {
            v0.printStackTrace();
        }
    }

    /**
     * Loads a bitmap from an image on the SD card
     */
    public Bitmap getBitmapFromSDCard(String path) {
        Bitmap bitmap = null;
        try {
            FileInputStream fileInputStream = new FileInputStream(path);
            if (fileInputStream != null) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 2; // very large images can cause an OOM; both dimensions are halved, so the image is a quarter of its original size
                bitmap = BitmapFactory.decodeStream(((InputStream) fileInputStream), null, options);
            }
        } catch (Exception e) {
            return null;
        }

        return bitmap;
    }
}