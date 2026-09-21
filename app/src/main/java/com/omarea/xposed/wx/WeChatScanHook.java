package com.omarea.xposed.wx;

import android.app.Activity;
import android.graphics.Color;
import android.hardware.Camera;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class WeChatScanHook {


    public boolean supported() {
        if (CameraHookProvider.devices.contains(Build.MODEL)) {
            return Camera.getNumberOfCameras() > 2;
        }
        return false;
    }

    public void hook(final XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (supported()) {
            // hook camera startup to change the target camera id
            XposedHelpers.findAndHookMethod(Camera.class, "open", int.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    VirtualCameraInfo targetCamera = cameraHookProvider.getCameraIdHook();
                    param.args[0] = targetCamera.cameraId;

                    XposedBridge.log("Scene: WeChat start camera CameraId [" + param.args[0] + "] Total: " + Camera.getNumberOfCameras());
                }
            });

            // hook all activities and filter the scan page (tested on WeChat 7.0 and 8.0)
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    String className = param.thisObject.getClass().getName();
                    if (className.equals("com.tencent.mm.plugin.scanner.ui.BaseScanUI")) {
                        scanActivityInject(param);
                    }
                    // XposedBridge.log("Scene: Activity onResume [" + className + "]");
                }
            });


            // hook all activities and filter the scan page (tested on WeChat 7.0 and 8.0)
            XposedHelpers.findAndHookMethod(Activity.class, "onPause", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    super.beforeHookedMethod(param);
                    String className = param.thisObject.getClass().getName();
                    if (className.equals("com.tencent.mm.plugin.scanner.ui.BaseScanUI")) {
                        // restore hook parameters after leaving the scan page so other pages can use the camera
                        cameraHookProvider.resetHooK();
                    }
                }
            });
        }
    }

    private final CameraHookProvider cameraHookProvider = new CameraHookProvider();
    private final WeChatLayoutAnalyser weChatLayoutAnalyser = new WeChatLayoutAnalyser();

    // inject a camera switch button into the WeChat UI
    private void scanActivityInject(XC_MethodHook.MethodHookParam param) {
        if (cameraHookProvider.cameraList.length > 1) {

            final Activity activity = (Activity) param.thisObject;
            // find a suitable container to insert the button into
            RelativeLayout container = weChatLayoutAnalyser.getInjectContainer(activity);
            if (container != null) {
                TextView textView = createControls(container);

                // switch camera on click
                textView.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        cameraHookProvider.setCameraIdHook(
                                cameraHookProvider.getCameraIdHookNext()
                        );

                        // just change the hook parameter and restart the activity
                        activity.recreate();
                    }
                });
            }
        }
    }

    // create a button and add it to the container
    private TextView createControls(ViewGroup container) {// create a button and set its appearance
        TextView textView = new TextView(container.getContext());
        textView.setTextColor(Color.WHITE);
        textView.setPadding(100, 0, 100, 0);
        RelativeLayout.LayoutParams layoutParams = new RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
        );
        textView.setTextSize(40);
        layoutParams.addRule(RelativeLayout.CENTER_IN_PARENT);

        // show the current zoom ratio
        textView.setText(
                cameraHookProvider.getCameraIdHook().cameraName
        );

        container.addView(textView, layoutParams);

        return textView;
    }
}
