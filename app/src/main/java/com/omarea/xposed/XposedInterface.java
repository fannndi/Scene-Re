package com.omarea.xposed;

import android.app.Activity;
import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;

import com.omarea.store.XposedExtension;
import com.omarea.xposed.wx.WeChatScanHook;

import org.json.JSONObject;

import java.util.Iterator;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.getObjectField;

/**
 * Created by helloklf on 2016/10/1.
 */
public class XposedInterface implements IXposedHookLoadPackage, IXposedHookZygoteInit {
    private static XSharedPreferences prefs;

    public XposedExtension.AppConfig getAppConfig(String packageName) {
        try {
            String configJson = prefs.getString(packageName, "{}");
            JSONObject config = new JSONObject(configJson);
            XposedExtension.AppConfig appConfig = new XposedExtension.AppConfig(packageName);

            Iterator<String> iter = config.keys();
            while (iter.hasNext()) {
                String key = (String) iter.next();
                switch (key) {
                    case "dpi": {
                        appConfig.setDpi(config.getInt(key));
                        break;
                    }
                    case "excludeRecent": {
                        appConfig.setExcludeRecent(config.getBoolean(key));
                        break;
                    }
                    case "smoothScroll": {
                        appConfig.setSmoothScroll(config.getBoolean(key));
                        break;
                    }
                    case "webDebug": {
                        appConfig.setWebDebug(config.getBoolean(key));
                        break;
                    }
                }
            }

            return appConfig;
        } catch (Exception ex) {
        }

        return null;
    }

    @Override
    public void initZygote(IXposedHookZygoteInit.StartupParam startupParam) throws Throwable {
        prefs = new XSharedPreferences("com.omarea.vaddin", "xposed");

        // bypass permission restrictions to read config, because SharedPreferences cannot be MODE_WORLD_READABLE on Android N
        prefs.makeWorldReadable();
        final boolean disServiceForeground = prefs.getBoolean("android_dis_service_foreground", false);

        XposedHelpers.findAndHookMethod(Service.class, "setForeground", boolean.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (disServiceForeground) {
                    param.args[0] = false;
                }
                super.afterHookedMethod(param);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                try {
                    if ((Boolean) param.args[0]) {
                        callMethod(param.thisObject, "setForeground", false);
                    }
                } catch (Exception ignored) {
                }
            }
        });
        XposedHelpers.findAndHookMethod(Service.class, "startForeground", int.class, Notification.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                if (disServiceForeground) {
                    callMethod(param.thisObject, "stopForeground", true);
                    /*
                    try {
                        Service service = (Service) param.thisObject;
                        NotificationManager notificationManager = (NotificationManager) service.getSystemService(Service.NOTIFICATION_SERVICE);
                        notificationManager.cancel((Integer) param.args[0]);
                    } catch (Exception ex) {

                    }
                    */
                }
            }
        });
    }

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam loadPackageParam) throws Throwable {
        if (!loadPackageParam.isFirstApplication) {
            return;
        }
        prefs.reload();
        final String packageName = loadPackageParam.packageName;
        final XposedExtension.AppConfig appConfig = getAppConfig(packageName);

        // dedicated options
        switch (packageName) {
            // used to check whether Xposed is active
            case "com.omarea.vtools":
            case "com.omarea.vboot":
                new ActiveCheck().isActive(loadPackageParam);
                break;
            case "com.tencent.mm": {
                new WeChatScanHook().hook(loadPackageParam);
                break;
            }
        }

        // launch frozen biased apps via Xposed
        new AppFreezeInjector().appFreezeInject(loadPackageParam);

        if (!packageName.equals("android") && !packageName.equals("com.android.systemui")) {
            // smooth scrolling
            if (appConfig.getSmoothScroll() || prefs.getBoolean("android_scroll", false)) {
                new ViewConfig().handleLoadPackage(loadPackageParam);
            }
        }

        // hide from recent tasks list
        if (appConfig.getExcludeRecent()) {
            new ExcludeRecent().handleLoadPackage(loadPackageParam);
        }
        // WebView debugging
        if (appConfig.getWebDebug()) {
            new WebView().allowDebug();
        }

        // reverse optimization (global)
        if (prefs.getBoolean("reverse_optimizer", false)) {
            new ReverseOptimizer().handleLoadPackage(loadPackageParam);
        }

        // DPI
        final int dpi = appConfig.getDpi();
        if (dpi >= 96) {
            XposedHelpers.findAndHookMethod("android.app.Application", loadPackageParam.classLoader, "attach", Context.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);

                    Context context = (Context) param.args[0];
                    if (context == null)
                        return;

                    try {
                        Configuration origConfig = context.getResources().getConfiguration();
                        origConfig.densityDpi = dpi;// get the factory default densityDpi
                        context.getResources().updateConfiguration(origConfig, context.getResources().getDisplayMetrics());
                        context.getResources().getDisplayMetrics().density = dpi / 160.0f;
                        context.getResources().getDisplayMetrics().densityDpi = dpi;
                        context.getResources().getDisplayMetrics().scaledDensity = dpi / 160.0f;
                    } catch (Exception ex) {
                        XposedBridge.log(ex);
                    }
                }
            });

            XposedHelpers.findAndHookMethod(Display.class, "getMetrics", DisplayMetrics.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object mDisplayInfo = XposedHelpers.getObjectField(param.thisObject, "mDisplayInfo");
                    XposedHelpers.setIntField(mDisplayInfo, "logicalDensityDpi", dpi);
                    DisplayMetrics displayMetrics = (DisplayMetrics) param.args[0];
                    displayMetrics.scaledDensity = dpi / 160.0f;
                    displayMetrics.densityDpi = dpi;
                    displayMetrics.density = dpi / 160.0f;
                }
            });

            XposedHelpers.findAndHookMethod("android.content.res.ResourcesImpl", loadPackageParam.classLoader, "getDisplayMetrics", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    DisplayMetrics displayMetrics = (DisplayMetrics) getObjectField(param.thisObject, "mMetrics");
                    if (displayMetrics != null) {
                        displayMetrics.scaledDensity = dpi / 160.0f;
                        displayMetrics.densityDpi = dpi;
                        displayMetrics.density = dpi / 160.0f;
                    }
                }
            });
            XposedBridge.hookAllMethods(DisplayMetrics.class, "getDeviceDensity", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    param.setResult(dpi);
                }
            });

            // Resources wrapper, @NonNull TypedValue value, int id, int density, @Nullable Resources.Theme theme
            XposedHelpers.findAndHookMethod("android.content.res.ResourcesImpl", loadPackageParam.classLoader, "loadDrawable", Resources.class, TypedValue.class, int.class, int.class, Resources.Theme.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    DisplayMetrics displayMetrics = (DisplayMetrics) param.getResult();
                    if (displayMetrics != null) {
                        displayMetrics.scaledDensity = dpi / 160.0f;
                        displayMetrics.densityDpi = dpi;
                        displayMetrics.density = dpi / 160.0f;
                    }
                }
            });

            XposedHelpers.findAndHookMethod("android.app.Activity", loadPackageParam.classLoader, "onCreate", Bundle.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);

                    try {
                        Activity activity = (Activity) param.thisObject;
                        Resources resources = activity.getWindow().getDecorView().getResources();
                        DisplayMetrics displayMetrics = resources.getDisplayMetrics();

                        Configuration origConfig = resources.getConfiguration();
                        origConfig.densityDpi = dpi;// get the factory default densityDpi
                        resources.updateConfiguration(origConfig, displayMetrics);

                        displayMetrics.density = dpi / 160.0f;
                        displayMetrics.densityDpi = dpi;
                        displayMetrics.scaledDensity = dpi / 160.0f;
                    } catch (Exception ex) {
                        XposedBridge.log(ex);
                    }
                }
            });

            /*
            XposedHelpers.findAndHookMethod("android.util.DisplayMetrics", loadPackageParam.classLoader, "setTo", DisplayMetrics.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    DisplayMetrics displayMetrics = (DisplayMetrics) (param.args[0]);
                    if (displayMetrics != null) {
                        displayMetrics.density = dpi / 160.0f;
                        displayMetrics.densityDpi = dpi;
                        displayMetrics.scaledDensity = dpi / 160.0f;
                    }
                }
            });
            XposedHelpers.findAndHookMethod("android.util.DisplayMetrics", loadPackageParam.classLoader, "getRealMetrics", DisplayMetrics.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    DisplayMetrics displayMetrics = (DisplayMetrics) (param.args[0]);
                    if (displayMetrics != null) {
                        displayMetrics.density = dpi / 160.0f;
                        displayMetrics.densityDpi = dpi;
                        displayMetrics.scaledDensity = dpi / 160.0f;
                    }
                }
            });
            */
        }
    }
}
