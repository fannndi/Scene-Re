package com.omarea.xposed;

/**
 * Created by helloklf on 2017/6/3.
 */

public class XposedCheck {
    private static int check = 0;

    // check whether the Xposed module is active (hooked to return true in the Xposed part)
    public static boolean xposedIsRunning() {
        check %= 1;
        return false;
    }
}
