package com.omarea.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;

/**
 * Charging speed history (schema v2).
 *
 * v2 adds a per-plug `session` id and `dt_ms`, so curves/statistics no longer
 * mix several charging sessions, and "total charged" integrates io*dt instead
 * of assuming exactly 1 Hz samples.
 *
 * Responsibility: persist charge samples and serve the chart series.
 * Non-goals: sampling (ChargeCurve) and UI.
 */
public class ChargeSpeedStore extends SQLiteOpenHelper {
    private static final int DB_VERSION = 2;
    private static final long DEFAULT_DT_MS = 1000L;

    public ChargeSpeedStore(Context context) {
        super(context, "charge_history2", null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        try {
            db.execSQL("create table charge_history(" +
                    "id INTEGER primary key AUTOINCREMENT, " +
                    "time INTEGER, " +
                    "io INTEGER, " +
                    "capacity INTEGER, " +
                    "temperature REAL, " +
                    "dt_ms INTEGER default(1000), " +
                    "session INTEGER default(-1)" +
                    ")");
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE charge_history ADD COLUMN dt_ms INTEGER default(1000)");
            } catch (Exception ignored) {
            }
            try {
                db.execSQL("ALTER TABLE charge_history ADD COLUMN session INTEGER default(-1)");
            } catch (Exception ignored) {
            }
        }
    }

    /** Total charged energy: integral of io (mA) over dt -> mAh. */
    public int getSum() {
        try {
            SQLiteDatabase sqLiteDatabase = this.getReadableDatabase();
            final Cursor cursor = sqLiteDatabase.rawQuery(
                    "select sum(io * dt_ms) / 3600000.0 as total from charge_history", new String[]{});
            try {
                if (cursor.moveToNext() && !cursor.isNull(0)) {
                    return (int) cursor.getFloat(0);
                }
            } finally {
                cursor.close();
                sqLiteDatabase.close();
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    /** Speed curve of the newest session (older sessions are kept for history). */
    public ArrayList<ChargeSpeedHistory> statistics() {
        ArrayList<ChargeSpeedHistory> histories = new ArrayList<>();
        try {
            SQLiteDatabase sqLiteDatabase = this.getReadableDatabase();
            final Cursor cursor = sqLiteDatabase.rawQuery(
                    "select capacity, avg(io) as io from charge_history " +
                            "where session = (select max(session) from charge_history) " +
                            "group by capacity order by capacity", new String[]{});
            while (cursor.moveToNext()) {
                histories.add(new ChargeSpeedHistory() {{
                    capacity = cursor.getInt(cursor.getColumnIndex("capacity"));
                    io = cursor.getLong(cursor.getColumnIndex("io"));
                }});
            }
            cursor.close();
            sqLiteDatabase.close();
        } catch (Exception ignored) {
        }
        return histories;
    }

    public ArrayList<ChargeSpeedHistory> getTemperature() {
        ArrayList<ChargeSpeedHistory> histories = new ArrayList<>();
        try {
            SQLiteDatabase sqLiteDatabase = this.getReadableDatabase();
            final Cursor cursor = sqLiteDatabase.rawQuery(
                    "select capacity, max(temperature) as temperature from charge_history " +
                            "where session = (select max(session) from charge_history) " +
                            "group by capacity", new String[]{});
            while (cursor.moveToNext()) {
                histories.add(new ChargeSpeedHistory() {{
                    capacity = cursor.getInt(cursor.getColumnIndex("capacity"));
                    temperature = cursor.getFloat(cursor.getColumnIndex("temperature"));
                }});
            }
            cursor.close();
            sqLiteDatabase.close();
        } catch (Exception ignored) {
        }
        return histories;
    }

    public ArrayList<ChargeTimeHistory> chargeTime() {
        ArrayList<ChargeTimeHistory> histories = new ArrayList<>();
        try {
            SQLiteDatabase sqLiteDatabase = this.getReadableDatabase();
            final Cursor cursor = sqLiteDatabase.rawQuery(
                    "select capacity, min(time) as start_time, max(time) as end_time from charge_history " +
                            "where session = (select max(session) from charge_history) " +
                            "group by capacity", new String[]{});
            while (cursor.moveToNext()) {
                histories.add(new ChargeTimeHistory() {{
                    startTime = cursor.getLong(cursor.getColumnIndex("start_time"));
                    endTime = cursor.getLong(cursor.getColumnIndex("end_time"));
                    capacity = cursor.getInt(cursor.getColumnIndex("capacity"));
                }});
            }
            cursor.close();
            sqLiteDatabase.close();
        } catch (Exception ignored) {
        }
        return histories;
    }

    public boolean addHistory(long io, int capacity, double temperature, long dtMs, long session) {
        try {
            ContentValues values = new ContentValues();
            values.put("time", System.currentTimeMillis());
            values.put("io", io);
            values.put("capacity", capacity);
            values.put("temperature", temperature);
            values.put("dt_ms", dtMs > 0 ? dtMs : DEFAULT_DT_MS);
            values.put("session", session);
            SQLiteDatabase database = getWritableDatabase();
            return database.insert("charge_history", null, values) != -1;
        } catch (Exception ex) {
            return false;
        }
    }

    /** Legacy overload (no session/dt): kept for completeness. */
    public boolean addHistory(long io, int capacity, double temperature) {
        return addHistory(io, capacity, temperature, DEFAULT_DT_MS, -1);
    }

    /** Capacity of the newest sample (was max(capacity) - wrong). */
    public int lastCapacity() {
        try {
            SQLiteDatabase sqLiteDatabase = this.getReadableDatabase();
            final Cursor cursor = sqLiteDatabase.rawQuery(
                    "select capacity from charge_history order by time desc limit 1", new String[]{});
            try {
                if (cursor.moveToNext()) {
                    return cursor.getInt(0);
                }
            } finally {
                cursor.close();
                sqLiteDatabase.close();
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    public boolean clearAll() {
        try {
            SQLiteDatabase database = getWritableDatabase();
            database.execSQL("delete from charge_history", new String[]{});
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public boolean handleConflics(int capacity) {
        try {
            SQLiteDatabase database = getWritableDatabase();
            database.execSQL(
                    "delete from charge_history where session = (select max(session) from charge_history) and capacity >= ?",
                    new Object[]{capacity});
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
