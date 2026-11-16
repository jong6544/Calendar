package com.jong6544.calendar

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class EventStore private constructor(context: Context) :
    SQLiteOpenHelper(context, "events.db", null, 3) {

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
                repeat INTEGER NOT NULL DEFAULT 0,
                uid TEXT
            )"""
        )

        createDoneTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createDoneTable(db)
        if (oldVersion < 3) db.execSQL("ALTER TABLE events ADD COLUMN uid TEXT")
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

    private fun values(e: Event) = ContentValues().apply {
        put("title", e.title)
        put("epoch_day", e.epochDay)
        put("all_day", if (e.allDay) 1 else 0)
        put("start_min", e.startMin)
        put("end_min", e.endMin)
        put("color", e.color)
        put("repeat", e.repeat)
        // 수정 화면은 uid를 모르므로, 없을 때는 기존 값을 건드리지 않는다.
        if (e.uid != null) put("uid", e.uid)
    }

    /** id가 0이면 새로 추가, 아니면 수정. */
    fun save(e: Event): Long {
        val v = values(e)
        return if (e.id == 0L) {
            writableDatabase.insertOrThrow("events", null, v)
        } else {
            writableDatabase.update("events", v, "id = ?", arrayOf(e.id.toString()))
            e.id
        }
    }

    /** 파일에서 읽은 일정을 한꺼번에 저장한다. 이미 가져온 것(같은 uid)은 갱신. (새로 추가, 갱신) 개수를 돌려준다. */
    fun importAll(events: List<Event>): Pair<Int, Int> {
        val db = writableDatabase
        var added = 0
        var updated = 0
        db.beginTransaction()
        try {
            val existing = HashMap<String, Long>()
            db.rawQuery("SELECT uid, id FROM events WHERE uid IS NOT NULL", null).use { c ->
                while (c.moveToNext()) existing[c.getString(0)] = c.getLong(1)
            }
            for (e in events) {
                val uid = e.uid ?: continue
                val id = existing[uid]
                if (id == null) {
                    existing[uid] = db.insertOrThrow("events", null, values(e))
                    added++
                } else {
                    // 사용자가 바꾼 색은 유지한다.
                    db.update("events", values(e).apply { remove("color") }, "id = ?", arrayOf(id.toString()))
                    updated++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return added to updated
    }

    fun importedCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM events WHERE uid IS NOT NULL", null)
            .use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    /** 파일에서 가져온 일정과 그 완수 기록을 모두 지운다. */
    fun deleteImported(): Int {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                "DELETE FROM done WHERE EXISTS (SELECT 1 FROM events " +
                    "WHERE uid IS NOT NULL AND done.key LIKE 'L' || events.id || '@%')"
            )
            val n = db.delete("events", "uid IS NOT NULL", null)
            db.setTransactionSuccessful()
            return n
        } finally {
            db.endTransaction()
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
        uid = getString(getColumnIndexOrThrow("uid")),
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
