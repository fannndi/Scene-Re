package com.omarea.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.omarea.benchmark.BenchmarkMetrics;
import com.omarea.benchmark.BenchMode;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Map;

/**
 * Benchmark history (aggregates only; per-second rows live in the exported
 * CSV bundle under files/benchmark/).
 *
 * Responsibility: persist runs + scenario aggregates for in-app comparison.
 * Non-goals: raw samples and report rendering.
 */
public class BenchmarkStore extends SQLiteOpenHelper {
    private static final int DB_VERSION = 1;

    public BenchmarkStore(Context context) {
        super(context, "benchmark.db", null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("create table run(" +
                "id INTEGER primary key autoincrement," +
                "started_at INTEGER, finished_at INTEGER," +
                "mode text, target text," +
                "aborted INTEGER default(0), abort_reason text," +
                "capacity_start INTEGER default(-1), capacity_end INTEGER default(-1)," +
                "total_mwh REAL default(-1), max_battery_temp REAL default(-1)," +
                "tuning_hash text, dir text" +
                ")");
        db.execSQL("create table scenario(" +
                "id INTEGER primary key autoincrement," +
                "run_id INTEGER, scenario text," +
                "duration_ms INTEGER, samples INTEGER," +
                "avg_ma REAL, avg_mw REAL, max_mw REAL, mah REAL, mwh REAL," +
                "pct_of_design REAL, pct_per_hour REAL," +
                "avg_battery_temp REAL, max_battery_temp REAL, delta_battery_temp REAL, max_soc_temp REAL," +
                "avg_cpu0_khz REAL, avg_cpu6_khz REAL, avg_cpu_load REAL," +
                "avg_gpu_mhz REAL, avg_gpu_load REAL," +
                "fps_avg REAL, fps_p95 REAL, fps_min REAL, jank_pct REAL," +
                "work_units INTEGER, work_per_sec REAL, mwh_per_kilowork REAL," +
                "hist0 text, hist6 text" +
                ")");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    }

    public long startRun(BenchMode mode, String target, String tuningHash, String dir) {
        try {
            ContentValues values = new ContentValues();
            values.put("started_at", System.currentTimeMillis());
            values.put("mode", mode.name());
            values.put("target", target);
            values.put("tuning_hash", tuningHash);
            values.put("dir", dir);
            return getWritableDatabase().insert("run", null, values);
        } catch (Exception ex) {
            return -1;
        }
    }

    public void finishRun(long runId, boolean aborted, String reason,
                          int capacityStart, int capacityEnd,
                          double totalMwh, double maxBatteryTemp) {
        if (runId <= 0) return;
        try {
            ContentValues values = new ContentValues();
            values.put("finished_at", System.currentTimeMillis());
            values.put("aborted", aborted ? 1 : 0);
            values.put("abort_reason", reason);
            values.put("capacity_start", capacityStart);
            values.put("capacity_end", capacityEnd);
            values.put("total_mwh", totalMwh);
            values.put("max_battery_temp", maxBatteryTemp);
            getWritableDatabase().update("run", values, "id = ?", new String[]{"" + runId});
        } catch (Exception ignored) {
        }
    }

    public void insertScenario(long runId, BenchmarkMetrics.Summary s) {
        if (runId <= 0) return;
        try {
            ContentValues values = new ContentValues();
            values.put("run_id", runId);
            values.put("scenario", s.getScenario().getId());
            values.put("duration_ms", s.getDurationMs());
            values.put("samples", s.getSamples());
            values.put("avg_ma", s.getAvgBatteryMa());
            values.put("avg_mw", s.getAvgMw());
            values.put("max_mw", s.getMaxMw());
            values.put("mah", s.getMah());
            values.put("mwh", s.getMwh());
            values.put("pct_of_design", s.getPctOfDesign());
            values.put("pct_per_hour", s.getPctPerHour());
            values.put("avg_battery_temp", s.getAvgBatteryTempC());
            values.put("max_battery_temp", s.getMaxBatteryTempC());
            values.put("delta_battery_temp", s.getDeltaBatteryTempC());
            values.put("max_soc_temp", s.getMaxSocTempC());
            values.put("avg_cpu0_khz", s.getAvgCpu0Khz());
            values.put("avg_cpu6_khz", s.getAvgCpu6Khz());
            values.put("avg_cpu_load", s.getAvgCpuLoadPct());
            values.put("avg_gpu_mhz", s.getAvgGpuMhz());
            values.put("avg_gpu_load", s.getAvgGpuLoadPct());
            values.put("fps_avg", s.getFpsAvg());
            values.put("fps_p95", s.getFpsP95());
            values.put("fps_min", s.getFpsMin());
            values.put("jank_pct", s.getJankPct());
            values.put("work_units", s.getWorkUnits());
            values.put("work_per_sec", s.getWorkPerSec());
            values.put("mwh_per_kilowork", s.getMwhPerKiloWork());
            values.put("hist0", histogramJson(s.getFreqHistogram0()));
            values.put("hist6", histogramJson(s.getFreqHistogram6()));
            getWritableDatabase().insert("scenario", null, values);
        } catch (Exception ignored) {
        }
    }

    private static String histogramJson(Map<Long, Double> histogram) {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<Long, Double> entry : histogram.entrySet()) {
                obj.put("" + entry.getKey(), entry.getValue());
            }
            return obj.toString();
        } catch (Exception ex) {
            return "{}";
        }
    }

    /** Newest first: {id, mode, target, startedAt, aborted}. */
    public ArrayList<String[]> runs(int limit) {
        ArrayList<String[]> rows = new ArrayList<>();
        try {
            Cursor cursor = getReadableDatabase().rawQuery(
                    "select id, mode, target, started_at, aborted from run order by id desc limit ?",
                    new String[]{"" + limit});
            while (cursor.moveToNext()) {
                rows.add(new String[]{
                        "" + cursor.getLong(0), cursor.getString(1), cursor.getString(2),
                        "" + cursor.getLong(3), "" + cursor.getInt(4)
                });
            }
            cursor.close();
        } catch (Exception ignored) {
        }
        return rows;
    }

    public boolean clearAll() {
        try {
            SQLiteDatabase db = getWritableDatabase();
            db.execSQL("delete from run");
            db.execSQL("delete from scenario");
            return true;
        } catch (Exception ex) {
            return false;
        }
    }
}
