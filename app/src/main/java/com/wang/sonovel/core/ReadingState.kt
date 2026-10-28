package com.wang.sonovel.core

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/** Small memory cache backed by a transactional store: long books retain early chapter variables. */
internal object ReadingState {
    private val gson=Gson()
    private val type=object:TypeToken<Map<String,String>>(){}.type
    private var database:SQLiteDatabase?=null
    private val records=linkedMapOf<String,Map<String,String>>()
    private val dirty=linkedSetOf<String>()

    @Synchronized fun init(directory:File) {
        if(database!=null) return
        val db=SQLiteDatabase.openOrCreateDatabase(File(directory,"reading-variables.db"),null)
        db.enableWriteAheadLogging()
        db.execSQL("CREATE TABLE IF NOT EXISTS variables (id TEXT PRIMARY KEY, body TEXT NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX IF NOT EXISTS variables_updated ON variables(updated)")
        database=db
        val legacy=File(directory,"reading-variables.json")
        val empty=db.rawQuery("SELECT count(*) FROM variables",null).use { it.moveToFirst();it.getLong(0)==0L }
        if(empty && legacy.exists() && legacy.length()<=4*1024*1024) runCatching {
            val old=gson.fromJson<Map<String,Map<String,String>>>(legacy.readText(),object:TypeToken<Map<String,Map<String,String>>>(){}.type)
            records.putAll(old);dirty.addAll(old.keys);flush()
        }
    }
    @Synchronized fun get(source:String,url:String):Map<String,String> {
        val key="$source|$url"
        records[key]?.let { return it.toMap() }
        val loaded=database?.query("variables",arrayOf("body"),"id=?",arrayOf(key),null,null,null)?.use {
            if(it.moveToFirst()) runCatching { gson.fromJson<Map<String,String>>(it.getString(0),type) }.getOrNull() else null
        } ?: return emptyMap()
        records[key]=loaded
        trim()
        return loaded.toMap()
    }
    @Synchronized fun put(source:String,url:String,values:Map<String,String>) {
        if(values.isEmpty()) return
        val key="$source|$url"
        val bounded=values.filter { it.value.length<=32768 }.entries.take(100).associate { it.toPair() }
        require(gson.toJson(bounded).length<=256*1024) { "书源变量过大" }
        records.remove(key);records[key]=bounded;dirty+=key
        if(dirty.size>=256) flush()
        trim()
    }
    private fun trim() {
        while(records.size>512) {
            val candidate=records.keys.firstOrNull { it !in dirty } ?: break
            records.remove(candidate)
        }
    }
    @Synchronized fun flush() {
        val db=database ?: return
        if(dirty.isEmpty()) return
        db.beginTransaction()
        try {
            val time=System.currentTimeMillis()
            for(key in dirty) {
                val values=ContentValues().apply { put("id",key);put("body",gson.toJson(records[key]));put("updated",time) }
                db.insertWithOnConflict("variables",null,values,SQLiteDatabase.CONFLICT_REPLACE)
            }
            // Retain many complete novels while bounding old cached rule data.
            db.execSQL("DELETE FROM variables WHERE id IN (SELECT id FROM variables ORDER BY updated DESC, rowid DESC LIMIT -1 OFFSET 50000)")
            db.setTransactionSuccessful()
            dirty.clear()
        } finally { db.endTransaction() }
        trim()
    }
}
