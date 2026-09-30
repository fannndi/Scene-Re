package com.omarea.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;

/**
 * FPS record storage (schema v2).
 *
 * v2 adds measurement provenance: `dt_ms` (real sample spacing), `source`,
 * `refresh_hz`, `jank_frames`, `frames`, `valid` — statistics can then be
 * dt-weighted and never mix sentinel/invalid reads into real numbers.
 *
 * Responsibility: persist sessions + samples, expose raw rows and per-metric
 * series for the chart.
 * Non-goals: statistics (FpsMetrics) and source selection (FpsSampler).
 */
public class FpsWatchStore extends SQLiteOpenHelper {
    private static final int DB_VERSION = 2;

    public FpsWatchStore(Context context) {
        super(context, "fps_watch_log2", null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        try {
            db.execSQL("create table session(" +
                    "id INTEGER primary key, " +
                    "package_name text," +
                    "time_begin INTEGER default(-1)," +
                    "time_end INTEGER default(-1)" +
                    ")");
            db.execSQL(HISTORY_DDL);
        } catch (Exception ignored) {
        }
    }

    private static final String HISTORY_DDL =
            "create table fps_history(" +
                    "id INTEGER primary key AUTOINCREMENT," +
                    "time INTEGER," +
                    "session INTEGER," +
                    "fps REAL," +
                    "cpu_load REAL," +
                    "gpu_load REAL," +
                    "capacity INTEGER," +
                    "temperature REAL," +
                    "power_mode text," +
                    "dt_ms INTEGER default(1000)," +
                    "source text," +
                    "refresh_hz INTEGER default(0)," +
                    "jank_frames INTEGER default(-1)," +
                    "frames INTEGER default(-1)," +
                    "valid INTEGER default(1)" +
                    ")";

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            addColumn(db, "fps_history", "dt_ms INTEGER default(1000)");
            addColumn(db, "fps_history", "source text");
            addColumn(db, "fps_history", "refresh_hz INTEGER default(0)");
            addColumn(db, "fps_history", "jank_frames INTEGER default(-1)");
            addColumn(db, "fps_history", "frames INTEGER default(-1)");
            addColumn(db, "fps_history", "valid INTEGER default(1)");
        }
    }

    private static void addColumn(SQLiteDatabase db, String table, String ddl) {
        try {
            db.execSQL("ALTER TABLE " + table + " ADD COLUMN " + ddl);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------- sessions
    public ArrayList<FpsWatchSession> sessions() {
        ArrayList<FpsWatchSession> histories = new ArrayList<>();
        try {
            SQLiteDatabase sqLiteDatabase = getReadableDatabase();
            final Cursor cursor = sqLiteDatabase.rawQuery("select * from session order by id", new String[]{});
            while (cursor.moveToNext()) {
                histories.add(new FpsWatchSession() {{
                    sessionId = cursor.getLong(cursor.getColumnIndex("id"));
                    packageName = cursor.getString(cursor.getColumnIndex("package_name"));
                    beginTime = cursor.getLong(cursor.getColumnIndex("time_begin"));
                    endTime = cursor.getLong(cursor.getColumnIndex("time_end"));
                }});
            }
            cursor.close();
            sqLiteDatabase.close();
        } catch (Exception ignored) {
        }
        return histories;
    }

    // 创建会话
    public long createSession(String packageName) {
        SQLiteDatabase database = getWritableDatabase();
        long time = System.currentTimeMillis();
        try {
            ContentValues values = new ContentValues();
            values.put("id", time);
            values.put("package_name", packageName);
            values.put("time_begin", time);
            values.put("time_end", -1);
            return database.insert("session", null, values) == -1 ? -1 : time;
        } catch (Exception ex) {
            return -1;
        }
    }

    /** Marks the session as finished (accurate session duration). */
    public boolean endSession(long sessionId) {
        try {
            ContentValues values = new ContentValues();
            values.put("time_end", System.currentTimeMillis());
            SQLiteDatabase database = getWritableDatabase();
            return database.update("session", values, "id = ?", new String[]{"" + sessionId}) > 0;
        } catch (Exception ex) {
            return false;
        }
    }

    // -------------------------------------------------------------- samples
    // 添加记录（v2：带测量元数据）
    public boolean addHistory2(long session, float fps, double cpuLoad, double gpuLoad,
                               int capacity, double temperature, String powerMode,
                               long dtMs, String source, int refreshHz,
                               int jankFrames, int frames, boolean valid) {
        try {
            ContentValues values = new ContentValues();
            values.put("time", System.currentTimeMillis());
            values.put("session", session);
            values.put("fps", fps);
            values.put("cpu_load", cpuLoad);
            values.put("gpu_load", gpuLoad);
            values.put("capacity", capacity);
            values.put("temperature", temperature);
            values.put("power_mode", powerMode);
            values.put("dt_ms", dtMs);
            values.put("source", source);
            values.put("refresh_hz", refreshHz);
            values.put("jank_frames", jankFrames);
            values.put("frames", frames);
            values.put("valid", valid ? 1 : 0);
            SQLiteDatabase database = getWritableDatabase();
            return database.insert("fps_history", null, values) != -1;
        } catch (Exception ex) {
            return false;
        }
    }

    // 添加记录（legacy 调用方）
    public boolean addHistory(long session, float fps, double cpuLoad, double gpuLoad,
                              int capacity, double temperature, String powerMode) {
        return addHistory2(session, fps, cpuLoad, gpuLoad, capacity, temperature, powerMode,
                1000L, "legacy", 0, -1, -1, true);
    }

    /** Raw rows (ordered), for [com.omarea.util.fps.FpsMetrics]. */
    public ArrayList<FpsHistoryRow> sessionSamples(long sessionId) {
        ArrayList<FpsHistoryRow> rows = new ArrayList<>();
        try {
            SQLiteDatabase sqLiteDatabase = getReadableDatabase();
            final Cursor cursor = sqLiteDatabase.rawQuery(
                    "select time, session, fps, cpu_load, gpu_load, capacity, temperature, power_mode," +
                            " dt_ms, source, refresh_hz, jank_frames, frames, valid" +
                            " from fps_history where session = ? order by time",
                    new String[]{"" + sessionId});
            while (cursor.moveToNext()) {
                FpsHistoryRow row = new FpsHistoryRow();
                row.time = cursor.getLong(0);
                row.session = cursor.getLong(1);
                row.fps = cursor.getFloat(2);
                row.cpuLoad = cursor.getDouble(3);
                row.gpuLoad = cursor.getDouble(4);
                row.capacity = cursor.getInt(5);
                row.temperature = cursor.getDouble(6);
                row.powerMode = cursor.getString(7);
                row.dtMs = cursor.getLong(8);
                row.source = cursor.getString(9);
                row.refreshHz = cursor.getInt(10);
                row.jankFrames = cursor.getInt(11);
                row.frames = cursor.getInt(12);
                row.valid = cursor.getInt(13) != 0;
                rows.add(row);
            }
            cursor.close();
            sqLiteDatabase.close();
        } catch (Exception ignored) {
        }
        return rows;
    }

    // ------------------------------------------------------ chart series
    public ArrayList<Float> sessionFpsData(long sessionId) {
        return floatColumn(sessionId, "fps");
    }

    public ArrayList<Float> sessionTemperatureData(long sessionId) {
        return floatColumn(sessionId, "temperature");
    }

    public ArrayList<Float> sessionCpuLoadData(long sessionId) {
        return floatColumn(sessionId, "cpu_load");
    }

    public ArrayList<Float> sessionGpuLoadData(long sessionId) {
        return floatColumn(sessionId, "gpu_load");
    }

    public ArrayList<Float> sessionCapacityData(long sessionId) {
        return floatColumn(sessionId, "capacity");
    }

    private ArrayList<Float> floatColumn(long sessionId, String column) {
        ArrayList<Float> histories = new ArrayList<>();
        try {
            SQLiteDatabase sqLiteDatabase = getReadableDatabase();
            final Cursor cursor = sqLiteDatabase.rawQuery(
                    "select " + column + " from fps_history where session = ? order by time",
                    new String[]{"" + sessionId});
            while (cursor.moveToNext()) {
                histories.add(cursor.getFloat(0));
            }
            cursor.close();
            sqLiteDatabase.close();
        } catch (Exception ignored) {
        }
        return histories;
    }

    // 获取会话中的平静帧率
    public float sessionAvgFps(long sessionId) {
        Float value = floatQuery(sessionId, "avg(fps)");
        return value == null ? 0 : value;
    }

    // 获取会话中的最低帧率
    public float sessionMinFps(long sessionId) {
        Float value = floatQuery(sessionId, "min(fps)");
        return value == null ? 0 : value;
    }

    // 获取会话中的最高帧率
    public float sessionMaxFps(long sessionId) {
        Float value = floatQuery(sessionId, "max(fps)");
        return value == null ? 0 : value;
    }

    private Float floatQuery(long sessionId, String expression) {
        try {
            SQLiteDatabase sqLiteDatabase = getReadableDatabase();
            final Cursor cursor = sqLiteDatabase.rawQuery(
                    "select " + expression + " from fps_history where session = ? and valid = 1",
                    new String[]{"" + sessionId});
            try {
                if (cursor.moveToNext() && !cursor.isNull(0)) {
                    return cursor.getFloat(0);
                }
            } finally {
                cursor.close();
                sqLiteDatabase.close();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    // ----------------------------------------------------------------- misc
    public boolean clearAll() {
        try {
            SQLiteDatabase database = getWritableDatabase();
            database.execSQL("delete from session", new String[]{});
            database.execSQL("delete from fps_history", new String[]{});
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public boolean deleteSession(long sessionId) {
        try {
            SQLiteDatabase database = getWritableDatabase();
            database.execSQL("delete from session where id = ?", new Object[]{sessionId});
            database.execSQL("delete from fps_history where session = ?", new Object[]{sessionId});
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
