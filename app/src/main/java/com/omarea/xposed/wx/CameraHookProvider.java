package com.omarea.xposed.wx;

import android.hardware.Camera;
import android.os.Build;

import java.util.ArrayList;

import de.robv.android.xposed.XposedBridge;

public class CameraHookProvider {
    public static ArrayList<String> devices = new ArrayList<String>() {{
            add("MI 9");
            add("MI CC9 Pro");
            // Mi 10 Pro has not been verified yet
            // add("Mi 10 Pro");
    }};

    public VirtualCameraInfo[] cameraList;
    private int defaultCameraIndex;

    // print the IDs of all rear cameras
    private void dumpCameraList() {
        // enumerate all cameras
        XposedBridge.log("Scene: camera count " + Camera.getNumberOfCameras());
        for (int cameraId = 0; cameraId < Camera.getNumberOfCameras(); cameraId++) {
            Camera.CameraInfo cameraInfo = new Camera.CameraInfo();
            Camera.getCameraInfo(cameraId, cameraInfo);
            // if it is a rear camera
            if (cameraInfo.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                XposedBridge.log("Scene [Dump CameraInfo] cameraId: " + cameraId);
            }
        }
    }

    public CameraHookProvider() {

        String model = Build.MODEL;
        switch (model) {
            case "MI 9": {
                // the camera list varies by device model
                // Mi9: 0 wide, 2 telephoto, 3 ultra-wide
                this.cameraList = new VirtualCameraInfo[]{
                        // Gnew VirtualCameraInfo(3, 0.6), // ultra-wide is useless for scanning, removed
                        new VirtualCameraInfo(0, "❶"),
                        new VirtualCameraInfo(2, "❷")
                };
                this.defaultCameraIndex = 0; // index of the default camera in cameraList
                break;
            }
            case "MI CC9 Pro": {
                // the camera list varies by device model
                // CC9Pro: 0 wide, 2 telephoto, 3 ultra-wide, 4 macro, 5 super telephoto
                this.cameraList = new VirtualCameraInfo[]{
                        // new VirtualCameraInfo(3, 0.6), // ultra-wide is useless for scanning, removed
                        new VirtualCameraInfo(0, "❶"),
                        new VirtualCameraInfo(2, "❷"),
                        new VirtualCameraInfo(5, "❹"),
                };
                this.defaultCameraIndex = 0; // index of the default camera in cameraList
                break;
            }
            case "Mi 10 Pro": {
                this.cameraList = new VirtualCameraInfo[]{
                        new VirtualCameraInfo(0, "❶")
                };
                this.defaultCameraIndex = 0; // index of the default camera in cameraList
                break;
            }
            default: {
                this.cameraList = new VirtualCameraInfo[]{
                        new VirtualCameraInfo(0, 1.0),
                };
                this.defaultCameraIndex = 0; // index of the default camera in cameraList
                dumpCameraList();
                break;
            }
        }
    }

    private int hackCameraIndex = -1; // -1 means default
    private boolean valueKeepOnece = false;

    public void setCameraIdHook(int cameraIndex) {
        hackCameraIndex = cameraIndex;
        valueKeepOnece = true;
        XposedBridge.log("Scene: switch camera " + cameraIndex);
    }

    public VirtualCameraInfo getCameraIdHook() {
        if (hackCameraIndex > -1) {
            return cameraList[hackCameraIndex];
        }
        return cameraList[defaultCameraIndex];
    }

    public int getCameraIdHookNext() {
        if (hackCameraIndex > -1) {
            return (hackCameraIndex + 1) % cameraList.length;
        } else {
            return (defaultCameraIndex + 1) % cameraList.length;
        }
    }

    public void resetHooK() {
        if (valueKeepOnece) {
            valueKeepOnece = false;
        } else {
            setCameraIdHook(-1);
        }
    }
}
