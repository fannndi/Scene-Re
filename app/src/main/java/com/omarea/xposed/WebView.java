package com.omarea.xposed;

import android.graphics.Paint;
import android.view.View;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Created by helloklf on 2017/11/30.
 */

public class WebView {
    public void allowDebug() {
        // force enable WebView debugging
        XposedBridge.hookAllConstructors(android.webkit.WebView.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    XposedHelpers.callStaticMethod(android.webkit.WebView.class, "setWebContentsDebuggingEnabled", true);
                } catch (Exception ex) {
                    XposedBridge.log("Force WebView debug mode callStaticMethod setWebContentsDebuggingEnabled " + ex.getMessage());
                }
            }

            // force hardware rendering
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                // hardware rendering
                XposedHelpers.callMethod(param.thisObject, "setLayerType", View.LAYER_TYPE_HARDWARE, null);
            }
        });

        // force hardware rendering
        XposedHelpers.findAndHookMethod(android.webkit.WebView.class, "setLayerType", int.class, Paint.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                param.args[0] = View.LAYER_TYPE_HARDWARE;
            }
        });
        XposedHelpers.findAndHookMethod(android.webkit.WebView.class, "setWebContentsDebuggingEnabled", boolean.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                param.args[0] = true;
            }
        });
    }
}
