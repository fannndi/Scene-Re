package com.omarea.data;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import com.omarea.data.SceneConfigInfo;
import com.omarea.vtools.R;

import java.util.ArrayList;
import java.util.List;

public class SceneConfigStore extends SQLiteOpenHelper {
    private static final int DB_VERSION = 6;
    private final Context context;

    public SceneConfigStore(Context context) {
        super(context, "scene3_config", null, DB_VERSION);
        this.context = context;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        try {
            db.execSQL(
                "create table scene_config3(" +
                    "id text primary key, " + // id
                    "freeze int default(0)," + // 休眠
                    "fg_cgroup_mem text default('')," + // cgroup
                    "bg_cgroup_mem text default('')," + // cgroup
                    "dynamic_boost_mem int default(0)," + //
                    "show_monitor int default(0)" + //
                ")");

        } catch (Exception e) {
        }
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 4) {
            try {
                db.execSQL("alter table scene_config3 add column fg_cgroup_mem text default('')");
                db.execSQL("alter table scene_config3 add column bg_cgroup_mem text default('')");
            } catch (Exception ignored) {
            }
        }

        if (oldVersion < 5) {
            try {
                db.execSQL("alter table scene_config3 add column dynamic_boost_mem text default(0)");
            } catch (Exception ignored) {
            }
        }

        if (oldVersion < 6) {
            try {
                db.execSQL("alter table scene_config3 add column show_monitor text default(0)");
            } catch (Exception ignored) {
            }
        }
    }

    public ArrayList<SceneConfigInfo> queryAppConfig(String selection, String[] selectionArgs) {
        ArrayList<SceneConfigInfo> configInfoList = new ArrayList<>();
        try {
            SQLiteDatabase sqLiteDatabase = this.getReadableDatabase();
            Cursor cursor = sqLiteDatabase.query(
                "scene_config3", new String[]{ "*" }, selection, selectionArgs, null, null, null
            );
            while (cursor.moveToNext()) {
                configInfoList.add(getAppConfig(cursor));
            }
            cursor.close();
            sqLiteDatabase.close();
        } catch (Exception ignored) {
        }
        return configInfoList;
    }


    public SceneConfigInfo getAppConfig(Cursor cursor) {
        SceneConfigInfo sceneConfigInfo = new SceneConfigInfo();
        sceneConfigInfo.packageName = cursor.getString(cursor.getColumnIndex("id"));
        sceneConfigInfo.freeze = cursor.getInt(cursor.getColumnIndex("freeze")) == 1;
        sceneConfigInfo.fgCGroupMem = cursor.getString(cursor.getColumnIndex("fg_cgroup_mem"));
        sceneConfigInfo.bgCGroupMem = cursor.getString(cursor.getColumnIndex("bg_cgroup_mem"));
        sceneConfigInfo.dynamicBoostMem = cursor.getInt(cursor.getColumnIndex("dynamic_boost_mem")) == 1;
        sceneConfigInfo.showMonitor = cursor.getInt(cursor.getColumnIndex("show_monitor")) == 1;

        return sceneConfigInfo;
    }

    public SceneConfigInfo getAppConfig(String app) {
        SceneConfigInfo sceneConfigInfo = null;
        try {
            SQLiteDatabase sqLiteDatabase = this.getReadableDatabase();
            Cursor cursor = sqLiteDatabase.rawQuery("select * from scene_config3 where id = ?", new String[]{app});
            if (cursor.moveToNext()) {
                sceneConfigInfo = getAppConfig(cursor);
            }
            cursor.close();
            sqLiteDatabase.close();
        } catch (Exception ignored) {
        } finally {
            if (sceneConfigInfo == null) {
                sceneConfigInfo = new SceneConfigInfo();
                sceneConfigInfo.packageName = app;
            }
        }
        return sceneConfigInfo;
    }

    public boolean setAppConfig(SceneConfigInfo sceneConfigInfo) {
        SQLiteDatabase database = getWritableDatabase();
        getWritableDatabase().beginTransaction();
        try {
            database.execSQL("delete from scene_config3 where id = ?", new String[]{sceneConfigInfo.packageName});
            database.execSQL("insert into scene_config3(id, freeze, fg_cgroup_mem, bg_cgroup_mem, dynamic_boost_mem, show_monitor) values (?, ?, ?, ?, ?, ?)", new Object[]{
                    sceneConfigInfo.packageName,
                    sceneConfigInfo.freeze ? 1 : 0,
                    sceneConfigInfo.fgCGroupMem,
                    sceneConfigInfo.bgCGroupMem,
                    sceneConfigInfo.dynamicBoostMem ? 1 : 0,
                    sceneConfigInfo.showMonitor ? 1 : 0
            });
            database.setTransactionSuccessful();
            return true;
        } catch (Exception ex) {
            return false;
        } finally {
            database.endTransaction();
        }
    }

    public boolean resetAll() {
        try {
            SQLiteDatabase database = getWritableDatabase();
            database.execSQL("update scene_config3 set fg_cgroup_mem = '', bg_cgroup_mem = '', dynamic_boost_mem = 0, show_monitor = 0");
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public boolean removeAppConfig(String packageName) {
        try {
            SQLiteDatabase database = getWritableDatabase();
            database.execSQL("delete from scene_config3 where id = ?", new String[]{packageName});
            return true;
        } catch (Exception ex) {
            return false;
        }
    }

    public ArrayList<String> getFreezeAppList() {
        ArrayList<String> list = new ArrayList<String>();
        try {
            SQLiteDatabase sqLiteDatabase = this.getReadableDatabase();
            Cursor cursor = sqLiteDatabase.rawQuery("select * from scene_config3 where freeze == 1", null);
            while (cursor.moveToNext()) {
                list.add(cursor.getString(0));
            }
            cursor.close();
            sqLiteDatabase.close();
        } catch (Exception ignored) {
        }
        return list;
    }
}
