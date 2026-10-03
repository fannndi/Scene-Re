package com.omarea.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.BatteryManager;

import java.util.ArrayList;

/**
 * Battery usage history (schema v2).
 *
 * v2: `dt_ms` per sample (real spacing), temperature read as REAL (no more
 * int truncation), robust peaks (average of the 5 most extreme samples instead
 * of a single spike) and a fixed `lastCapacity()` query.
 *
 * Responsibility: persist usage samples and serve aggregate queries.
 * Non-goals: current sampling (BatterySampler) and UI.
 */
public class BatteryHistoryStore extends SQLiteOpenHelper {
    private static final int DB_VERSION = 2;
    private static final long DEFAULT_DT_MS = 3000L;

    public BatteryHistoryStore(Context context) {
        super(context, "battery-history3", null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        try {
            db.execSQL(
                    "create table battery_io(" +
                            "time text primary key, " +
                            "temperature REAL default(-1), " +
                            "status int default(-1)," +
                            "mode text," +
                            "io int default(-1)," +
                            "package text," +
                            "screen_on INTEGER," +
                            "capacity INTEGER," +
                            "dt_ms INTEGER default(3000)" +
                            ")");
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE battery_io ADD COLUMN dt_ms INTEGER default(3000)");
            } catch (Exception ignored) {
            }
        }
    }

    public boolean insertHistory(BatteryStatus batteryStatus) {
        try {
            ContentValues values = new ContentValues();
            values.put("time", "" + batteryStatus.time);
            values.put("temperature", batteryStatus.temperature);
            values.put("status", batteryStatus.status);
            values.put("mode", batteryStatus.mode);
            values.put("io", batteryStatus.io);
            values.put("package", batteryStatus.packageName);
            values.put("screen_on", batteryStatus.screenOn ? 1 : 0);
            values.put("capacity", batteryStatus.capacity);
            values.put("dt_ms", batteryStatus.dtMs > 0 ? batteryStatus.dtMs : DEFAULT_DT_MS);
            SQLiteDatabase database = getWritableDatabase();
            return database.insert("battery_io", null, values) != -1;
        } catch (Exception ignored) {
            return false;
        }
    }

    /** Max temperature (REAL, no truncation); 0 when empty. */

    /** Capacity of the newest sample (was broken: `TOP(1)` is not SQLite). */
    public int lastCapacity() {
        try {
            SQLiteDatabase database = getReadableDatabase();
            Cursor cursor = database.rawQuery(
                    "select capacity from battery_io order by CAST(time AS INTEGER) desc limit 1", new String[]{});
            try {
                if (cursor.moveToNext()) return cursor.getInt(0);
            } finally {
                cursor.close();
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /**
     * Peak input/output current: average of the 5 most extreme samples, so a
     * single transition spike cannot define the "peak" forever.
     */


    private int robustPeak(int batteryStatus, boolean highest) {
        try {
            SQLiteDatabase database = getReadableDatabase();
            Cursor cursor = database.rawQuery(
                    "select avg(io) from (select io from battery_io where status = ? " +
                            "order by io " + (highest ? "desc" : "asc") + " limit 5)",
                    new String[]{"" + batteryStatus});
            try {
                if (cursor.moveToNext() && !cursor.isNull(0)) return (int) cursor.getFloat(0);
            } finally {
                cursor.close();
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    public ArrayList<BatteryAvgStatus> getAvgData() {
        try {
            SQLiteDatabase sqLiteDatabase = getReadableDatabase();
            Cursor cursor = sqLiteDatabase.rawQuery(
                    "select avg(io) AS io, avg(temperature) as avg, min(temperature) as min," +
                            " max(temperature) as max, package, mode, count(io), sum(dt_ms), sum(capacity * dt_ms) " +
                            "from battery_io where status in (?, ?) and package != ? group by package, mode order by io",
                    new String[]{
                            "" + BatteryManager.BATTERY_STATUS_DISCHARGING,
                            "" + BatteryManager.BATTERY_STATUS_NOT_CHARGING,
                            ""
                    });
            ArrayList<BatteryAvgStatus> data = new ArrayList<>();
            while (cursor.moveToNext()) {
                BatteryAvgStatus batteryAvgStatus = new BatteryAvgStatus();
                batteryAvgStatus.io = cursor.getInt(0);
                batteryAvgStatus.avgTemperature = cursor.getFloat(1);
                batteryAvgStatus.minTemperature = cursor.getFloat(2);
                batteryAvgStatus.maxTemperature = cursor.getFloat(3);
                batteryAvgStatus.packageName = cursor.getString(4);
                batteryAvgStatus.mode = cursor.getString(5);
                batteryAvgStatus.count = cursor.getInt(6);
                batteryAvgStatus.totalMs = cursor.getLong(7);
                batteryAvgStatus.capacityMillis = cursor.getLong(8);
                data.add(batteryAvgStatus);
            }
            cursor.close();
            return data;
        } catch (Exception ignored) {
        }
        return new ArrayList<>();
    }

    public ArrayList<PowerHistory> getCurve() {
        ArrayList<PowerHistory> histories = new ArrayList<>();
        try {
            SQLiteDatabase sqLiteDatabase = getReadableDatabase();
            final Cursor cursor = sqLiteDatabase.rawQuery(
                    "select time, capacity, screen_on, status from battery_io order by CAST(time AS INTEGER) asc",
                    new String[]{}
            );
            PowerHistory prev = null;
            while (cursor.moveToNext()) {
                PowerHistory row = new PowerHistory() {{
                    startTime = cursor.getLong(0);
                    endTime = startTime;
                    capacity = cursor.getInt(1);
                    screenOn = cursor.getInt(2) == 1;
                    charging = cursor.getInt(3) != BatteryManager.BATTERY_STATUS_DISCHARGING;
                }};
                if (prev == null) {
                    prev = row;
                } else if (!(row.capacity == prev.capacity && row.screenOn == prev.screenOn && row.startTime - prev.endTime < 10000)) {
                    histories.add(prev);
                    prev = row;
                } else {
                    prev.endTime = row.endTime;
                }
            }
            if (prev != null) {
                histories.add(prev);
            }
            cursor.close();
            sqLiteDatabase.close();
        } catch (Exception ignored) {
        }
        return histories;
    }

    public boolean clearData() {
        try {
            SQLiteDatabase database = getWritableDatabase();
            database.delete("battery_io", " 1 = 1", new String[]{});
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
