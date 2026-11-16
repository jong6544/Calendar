package com.jong6544.calendar

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class EventStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "events.db", null, 2) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                epoch_day INTEGER NOT NULL,
                all_day INTEGER NOT NULL,
                start_min INTEGER NOT NULL,
                end_min INTEGER NOT NULL,
                color INTEGER NOT NULL,
                repeat INTEGER NOT NULL DEFAULT 0
            )"""
        )

        createDoneTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createDoneTable(db)
    }

    /** 완수한 일정(일정+날짜 열쇠) 목록. */
    private fun createDoneTable(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS done (key TEXT PRIMARY KEY)")
    }

    fun doneKeys(): Set<String> =
        readableDatabase.rawQuery("SELECT key FROM done", null).use { c ->
            val out = HashSet<String>(c.count)
            while (c.moveToNext()) out += c.getString(0)
            out
        }

    fun setDone(key: String, done: Boolean) {
        if (done) {
            writableDatabase.insertWithOnConflict(
                "done", null, ContentValues().apply { put("key", key) }, SQLiteDatabase.CONFLICT_IGNORE,
            )
        } else {
            writableDatabase.delete("done", "key = ?", arrayOf(key))
        }
    }

    fun toggleDone(key: String) = setDone(key, key !in doneKeys())

    fun all(): List<Event> =
        readableDatabase.rawQuery("SELECT * FROM events", null).use { c ->
            val out = ArrayList<Event>(c.count)
            while (c.moveToNext()) out += c.toEvent()
            out
        }

    fun get(id: Long): Event? =
        readableDatabase.rawQuery("SELECT * FROM events WHERE id = ?", arrayOf(id.toString()))
            .use { c -> if (c.moveToFirst()) c.toEvent() else null }

    /** id가 0이면 새로 추가, 아니면 수정. */
    fun save(e: Event): Long {
        val v = ContentValues().apply {
            put("title", e.title)
            put("epoch_day", e.epochDay)
            put("all_day", if (e.allDay) 1 else 0)
            put("start_min", e.startMin)
            put("end_min", e.endMin)
            put("color", e.color)
            put("repeat", e.repeat)
        }
        return if (e.id == 0L) {
            writableDatabase.insertOrThrow("events", null, v)
        } else {
            writableDatabase.update("events", v, "id = ?", arrayOf(e.id.toString()))
            e.id
        }
    }

    fun delete(id: Long) {
        writableDatabase.delete("events", "id = ?", arrayOf(id.toString()))
        writableDatabase.delete("done", "key LIKE ?", arrayOf("L$id@%"))
    }

    private fun Cursor.toEvent() = Event(
        id = getLong(getColumnIndexOrThrow("id")),
        title = getString(getColumnIndexOrThrow("title")),
        epochDay = getLong(getColumnIndexOrThrow("epoch_day")),
        allDay = getInt(getColumnIndexOrThrow("all_day")) != 0,
        startMin = getInt(getColumnIndexOrThrow("start_min")),
        endMin = getInt(getColumnIndexOrThrow("end_min")),
        color = getInt(getColumnIndexOrThrow("color")),
        repeat = getInt(getColumnIndexOrThrow("repeat")),
    )

    companion object {
        @Volatile
        private var instance: EventStore? = null

        fun get(context: Context): EventStore =
            instance ?: synchronized(this) {
                instance ?: EventStore(context.applicationContext).also { instance = it }
            }
    }
}
