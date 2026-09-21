package com.omarea.xposed;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Created by helloklf on 2017/11/30.
 */

public class SystemUI {
    public void hideSUIcon(final XC_LoadPackage.LoadPackageParam loadPackageParam) {
        // hide the CM status bar su icon
        XposedBridge.hookAllMethods(
                XposedHelpers.findClass(
                        "com.android.systemui.statusbar.phone.PhoneStatusBarPolicy",
                        loadPackageParam.classLoader
                ),
                "updateSu",
                new XC_MethodHook() {
                    protected void beforeHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
                        param.setResult(0x0);
                    }
                });
    }
}
