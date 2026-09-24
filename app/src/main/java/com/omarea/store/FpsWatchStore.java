package com.omarea.store;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.omarea.model.FpsWatchSession;

import java.util.ArrayList;

public class FpsWatchStore extends SQLiteOpenHelper {
    private static final int DB_VERSION = 1;

    public FpsWatchStore(Context context) {
        super(context, "fps_watch_log2", null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("create table session(" +
                "id INTEGER primary key, " +
                "package_name text," +
                "time_begin INTEGER default(-1)," +
                "time_end INTEGER default(-1)" +
                ")");
        db.execSQL("create table fps_history(" +
                "id INTEGER primary key AUTOINCREMENT," +
                "time INTEGER," +
                "session INTEGER," +
                "fps REAL," +
                "cpu_load REAL," +
                "gpu_load REAL," +
                "capacity INTEGER," +
                "temperature REAL," +
                "power_mode text" +
                ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    // --- Query helpers -------------------------------------------------------
    // The helper keeps the underlying database open, so these deliberately do
    // not close it; doing so would force a reopen on every single call.

    /** Returns the first column of the first row, or {@code fallback} if empty/failed. */
    private float queryFloat(String sql, String[] args, float fallback) {
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, args)) {
            if (cursor.moveToFirst()) {
                return cursor.getFloat(0);
            }
        } catch (Exception ignored) {
            // Fall through to the fallback value.
        }
        return fallback;
    }

    /** Collects the first column of every row as a list of floats. */
    private ArrayList<Float> queryFloatList(String sql, String[] args) {
        ArrayList<Float> values = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, args)) {
            while (cursor.moveToNext()) {
                values.add(cursor.getFloat(0));
            }
        } catch (Exception ignored) {
            // Return whatever was collected before the failure.
        }
        return values;
    }

    private static String[] sessionArgs(long sessionId) {
        return new String[]{String.valueOf(sessionId)};
    }

    // --- Reads ---------------------------------------------------------------

    /** Lists all recorded sessions, newest id first. */
    public ArrayList<FpsWatchSession> sessions() {
        ArrayList<FpsWatchSession> histories = new ArrayList<>();
        String sql = "select id, package_name, time_begin from session order by id desc";
        try (Cursor cursor = getReadableDatabase().rawQuery(sql, null)) {
            while (cursor.moveToNext()) {
                FpsWatchSession session = new FpsWatchSession();
                session.sessionId = cursor.getLong(0);
                session.packageName = cursor.getString(1);
                session.beginTime = cursor.getLong(2);
                histories.add(session);
            }
        } catch (Exception ignored) {
            // Return whatever was collected before the failure.
        }
        return histories;
    }

    public ArrayList<Float> sessionFpsData(long sessionId) {
        return queryFloatList("select fps from fps_history where session = ? order by time", sessionArgs(sessionId));
    }

    public ArrayList<Float> sessionTemperatureData(long sessionId) {
        return queryFloatList("select temperature from fps_history where session = ? order by time", sessionArgs(sessionId));
    }

    public ArrayList<Float> sessionCpuLoadData(long sessionId) {
        return queryFloatList("select cpu_load from fps_history where session = ? order by time", sessionArgs(sessionId));
    }

    public ArrayList<Float> sessionGpuLoadData(long sessionId) {
        return queryFloatList("select gpu_load from fps_history where session = ? order by time", sessionArgs(sessionId));
    }

    public ArrayList<Float> sessionCapacityData(long sessionId) {
        return queryFloatList("select capacity from fps_history where session = ? order by time", sessionArgs(sessionId));
    }

    public float sessionAvgFps(long sessionId) {
        return queryFloat("select avg(fps) from fps_history where session = ?", sessionArgs(sessionId), 0f);
    }

    public float sessionMinFps(long sessionId) {
        return queryFloat("select min(fps) from fps_history where session = ?", sessionArgs(sessionId), 0f);
    }

    public float sessionMaxFps(long sessionId) {
        return queryFloat("select max(fps) from fps_history where session = ?", sessionArgs(sessionId), 0f);
    }

    // --- Writes --------------------------------------------------------------

    /** Creates a session and returns its id (which doubles as the start time), or -1 on failure. */
    public long createSession(String packageName) {
        SQLiteDatabase database = getWritableDatabase();
        long time = System.currentTimeMillis();
        database.beginTransaction();
        try {
            database.execSQL("insert into session(id, package_name, time_begin) values (?, ?, ?)", new Object[]{
                    time,
                    packageName,
                    time
            });
            database.setTransactionSuccessful();
            return time;
        } catch (Exception ex) {
            return -1;
        } finally {
            database.endTransaction();
        }
    }

    public boolean addHistory(long session, float fps, double cpuLoad, double gpuLoad, int capacity, double temperature, String powerMode) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.execSQL("insert into fps_history(time, session, fps, cpu_load, gpu_load, capacity, temperature, power_mode) values (?, ?, ?, ?, ?, ?, ?, ?)", new Object[]{
                    System.currentTimeMillis(),
                    session,
                    fps,
                    cpuLoad,
                    gpuLoad,
                    capacity,
                    temperature,
                    powerMode
            });
            database.setTransactionSuccessful();
            return true;
        } catch (Exception ex) {
            return false;
        } finally {
            database.endTransaction();
        }
    }

    /** Clears all sessions and history in a single transaction. */
    public boolean clearAll() {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.execSQL("delete from session");
            database.execSQL("delete from fps_history");
            database.setTransactionSuccessful();
            return true;
        } catch (Exception ex) {
            return false;
        } finally {
            database.endTransaction();
        }
    }

    /** Deletes one session and all of its history rows in a single transaction. */
    public boolean deleteSession(long sessionId) {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.execSQL("delete from session where id = ?", new Object[]{sessionId});
            database.execSQL("delete from fps_history where session = ?", new Object[]{sessionId});
            database.setTransactionSuccessful();
            return true;
        } catch (Exception ex) {
            return false;
        } finally {
            database.endTransaction();
        }
    }
}
