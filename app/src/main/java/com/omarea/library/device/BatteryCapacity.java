package com.omarea.library.device;

import android.annotation.SuppressLint;
import android.content.Context;

public class BatteryCapacity {
    /**
     * Get battery capacity in mAh
     * Source file: frameworks/base/core/res\res/xml/power_profile.xml
     * Java reflection file: frameworks\base\core\java\com\android\internal\os\PowerProfile.java
     */
    @SuppressLint("PrivateApi")
    public double getBatteryCapacity(Context context) {
        Object mPowerProfile;
        double batteryCapacity = 0;
        final String POWER_PROFILE_CLASS = "com.android.internal.os.PowerProfile";

        try {
            mPowerProfile = Class.forName(POWER_PROFILE_CLASS).getConstructor(Context.class).newInstance(context);
            batteryCapacity = (int) ((double) Class.forName(POWER_PROFILE_CLASS).getMethod("getBatteryCapacity").invoke(mPowerProfile));
        } catch (Exception e) {
            e.printStackTrace();
        }

        return batteryCapacity;
    }
}
