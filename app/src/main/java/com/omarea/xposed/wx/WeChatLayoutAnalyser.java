package com.omarea.xposed.wx;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import de.robv.android.xposed.XposedBridge;
import androidx.core.content.pm.PackageInfoCompat;

public class WeChatLayoutAnalyser {
    // find the next node after ScanMaskView (WeChat 7.0)
    private RelativeLayout getScanMaskViewNext(View view, int level) {
        if (view instanceof ViewGroup) {
            ViewGroup vp = (ViewGroup) view;
            for (int i = 0; i < vp.getChildCount(); i++) {
                View child = vp.getChildAt(i);
                String className = child.getClass().getName();
                // log output for analyzing the layout hierarchy
                // XposedBridge.log("Scene WeChat " + prefixSpace(level) + className);

                // based on logs, there is a container suitable for inserting controls after ScanMaskView
                // so after finding ScanMaskView, return its next node
                if (className.equals("com.tencent.mm.plugin.scanner.ui.ScanMaskView")) {
                    if (i + 1 < vp.getChildCount()) {
                        return (RelativeLayout) vp.getChildAt(i + 1);
                    }
                } else {
                    // iterate child nodes
                    RelativeLayout relativeLayout = getScanMaskViewNext(child, level + 1);
                    if (relativeLayout != null) {
                        return relativeLayout;
                    }
                }
            }
        }
        return null;
    }

    // find a RelativeLayout among ScanSharedMaskView's child nodes
    private RelativeLayout getRelativeLayout(ViewGroup scanSharedMaskView) {
        for (int i = 0; i < scanSharedMaskView.getChildCount(); i++) {
            View sc = scanSharedMaskView.getChildAt(i);
            String className2 = sc.getClass().getName();
            if (className2.equals("android.widget.RelativeLayout")) {
                return (RelativeLayout) sc;
            }
        }
        return null;
    }

    // find the next node after ScanMaskView (WeChat 8.0)
    private RelativeLayout getScanSharedMaskViewChild(View view, int level) {
        if (view instanceof ViewGroup) {
            ViewGroup vp = (ViewGroup) view;
            for (int i = 0; i < vp.getChildCount(); i++) {
                View child = vp.getChildAt(i);
                String className = child.getClass().getName();
                // log output for analyzing the layout hierarchy
                // XposedBridge.log("Scene WeChat " + prefixSpace(level) + className);

                // based on logs, ScanSharedMaskView contains a container suitable for inserting controls
                // so after finding ScanSharedMaskView, return one of its container views
                if (className.equals("com.tencent.mm.plugin.scanner.ui.widget.ScanSharedMaskView")) {
                    return getRelativeLayout((ViewGroup) child);
                } else {
                    // iterate child nodes
                    RelativeLayout relativeLayout = getScanSharedMaskViewChild(child, level + 1);
                    if (relativeLayout != null) {
                        return relativeLayout;
                    }
                }
            }
        }
        return null;
    }

    public RelativeLayout getInjectContainer(Activity wxActivity) {
        XposedBridge.log("Scene WeChat BaseScanUI onResume -> getInjectContainer");

        int versionCode = 1841; // WeChat 8.0.1
        View rootView = wxActivity.getWindow().getDecorView();
        try {
            versionCode = (int) PackageInfoCompat.getLongVersionCode(
                    wxActivity.getPackageManager().getPackageInfo(wxActivity.getPackageName(), 0)
            );
        } catch (Exception ignored) {
        }
        return (versionCode >= 1841) ? getScanSharedMaskViewChild(rootView, 0) : getScanMaskViewNext(rootView, 0);
    }
}
