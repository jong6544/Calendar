package com.jong6544.calendar

import android.Manifest
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** 기기 캘린더(삼성 캘린더·구글 캘린더 등)에 저장된 일정 한 건. 여러 날에 걸칠 수 있다. */
data class DeviceItem(
    val ref: DeviceRef,
    val title: String,
    val start: LocalDate,
    val end: LocalDate,
    val allDay: Boolean,
    val startMin: Int,
    val endMin: Int,
    val color: Int,
) {
    /** [d] 하루에 해당하는 부분을 화면 표시용 일정으로 바꾼다. */
    fun toEvent(d: LocalDate): Event {
        val from = if (allDay || d != start) 0 else startMin
        val to = if (allDay || d != end) 1440 else endMin
        val wholeDay = allDay || (from == 0 && to == 1440)
        return Event(
            title = title,
            epochDay = d.toEpochDay(),
            allDay = wholeDay,
            startMin = if (wholeDay) 0 else from,
            endMin = if (wholeDay) 0 else to,
            color = color,
            device = ref,
        )
    }

    companion object {
        /** 캘린더 저장소의 시작/종료 시각(밀리초)을 날짜·분 단위로 풀어낸다. */
        fun of(eventId: Long, title: String?, begin: Long, end: Long, allDay: Boolean, color: Int, zone: ZoneId): DeviceItem {
            // 종일 일정은 UTC 자정 기준으로 저장되고, 종료 시각은 마지막 날의 다음 날 0시다.
            val z = if (allDay) ZoneOffset.UTC else zone
            val startAt = Instant.ofEpochMilli(begin).atZone(z)
            val lastMoment = Instant.ofEpochMilli(if (end > begin) end - 1 else begin).atZone(z)
            val endAt = Instant.ofEpochMilli(maxOf(begin, end)).atZone(z)
            val endDate = lastMoment.toLocalDate()
            return DeviceItem(
                ref = DeviceRef(eventId, begin, end),
                title = title?.takeIf { it.isNotBlank() } ?: "(제목 없음)",
                start = startAt.toLocalDate(),
                end = endDate,
                allDay = allDay,
                startMin = startAt.hour * 60 + startAt.minute,
                endMin = if (endAt.toLocalDate().isAfter(endDate)) 1440 else endAt.hour * 60 + endAt.minute,
                color = color or 0xFF000000.toInt(),
            )
        }
    }
}

/** 앱에 저장한 일정과 기기 캘린더 일정을 합쳐 날짜별로 돌려준다. */
class Agenda(
    private val local: List<Event>,
    private val device: List<DeviceItem> = emptyList(),
    private val done: Set<String> = emptySet(),
) {

    /** [d]의 일정을 종일 → 시작 시간 순으로. */
    fun on(d: LocalDate): List<Event> {
        val out = ArrayList<Event>()
        val day = d.toEpochDay()
        for (e in local) {
            if (e.occursOn(d)) out += e.copy(shownDay = day, done = e.doneKey(day) in done)
        }
        for (item in device) {
            if (!d.isBefore(item.start) && !d.isAfter(item.end)) {
                val e = item.toEvent(d)
                out += e.copy(done = e.doneKey() in done)
            }
        }
        out.sortWith(compareBy<Event>({ !it.allDay }, { it.startMin }, { it.title }))
        return out
    }

    fun has(d: LocalDate): Boolean =
        local.any { it.occursOn(d) } || device.any { !d.isBefore(it.start) && !d.isAfter(it.end) }

    /** 일정이 있고 전부 완수한 날(스티커를 붙이는 날). */
    fun allDone(d: LocalDate): Boolean = on(d).let { it.isNotEmpty() && it.all { e -> e.done } }

    companion object {
        /** [from]~[to] 범위를 그리는 데 필요한 일정을 읽는다. */
        fun load(context: Context, from: LocalDate, to: LocalDate): Agenda {
            val store = EventStore.get(context)
            return Agenda(
                store.all(),
                if (DeviceCalendar.isEnabled(context)) DeviceCalendar.query(context, from, to) else emptyList(),
                store.doneKeys(),
            )
        }
    }
}

/** 기기에 등록된 캘린더 하나(예: 내 캘린더, 구글 계정, 기념일). */
data class DeviceCalendarInfo(val id: Long, val name: String, val account: String) {
    val label: String
        get() = if (account.isBlank() || account == name) name else "$name ($account)"
}

/** 캘린더 앱이 자동으로 넣는 공휴일·기념일·절기를 제목으로 알아본다. */
object Holidays {
    private val keywords = listOf(
        "신정", "새해", "설날", "구정", "삼일절", "3·1절", "3.1절", "어린이날", "어버이날",
        "부처님", "석가탄신", "현충일", "광복절", "추석", "한가위", "개천절", "한글날",
        "성탄", "크리스마스", "기독탄신", "공휴일", "대체 휴일", "대체휴일", "연휴", "식목일",
        "제헌절", "선거", "기념일", "정월대보름", "단오", "칠석", "초복", "중복", "말복",
        "발렌타인", "밸런타인", "화이트데이", "할로윈", "핼러윈",
    )

    // 24절기 등은 다른 말에 섞여 나오기 쉬워 제목이 정확히 같을 때만.
    private val exact = setOf(
        "입춘", "우수", "경칩", "춘분", "청명", "곡우", "입하", "소만", "망종", "하지", "소서", "대서",
        "입추", "처서", "백로", "추분", "한로", "상강", "입동", "소설", "대설", "동지", "소한", "대한",
        "한식",
    )

    fun looksLikeHoliday(title: String): Boolean {
        val t = title.trim()
        if (t in exact) return true
        if (keywords.any { t.contains(it) }) return true
        // "순국선열의 날", "국군의 날" / "부활절", "노동절"
        return t.endsWith("의 날") || t.endsWith("의날") || (t.length <= 6 && t.endsWith("절"))
    }
}

/** 같은 제목의 기기 캘린더 일정 묶음: 가장 이른 날짜, 횟수, 모두 종일인지. */
data class DeviceTitle(val title: String, val first: LocalDate, val count: Int, val allDay: Boolean) {
    val label: String
        get() {
            val parts = ArrayList<String>()
            parts += "${first.monthValue}/${first.dayOfMonth}"
            if (count > 1) parts += "${count}회"
            if (allDay) parts += "종일"
            return "$title (${parts.joinToString(", ")})"
        }
}

/** 기기 캘린더 연동(읽기 전용). */
object DeviceCalendar {
    private const val PREFS = "settings"
    private const val KEY = "device_calendar"
    private const val KEY_HIDDEN = "hidden_calendars"
    private const val KEY_HIDDEN_TITLES = "hidden_titles"

    /** 연동에서 뺀 캘린더 id. */
    fun hiddenIds(context: Context): Set<Long> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }.toSet()

    fun setHiddenIds(context: Context, ids: Set<Long>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_HIDDEN, ids.map { it.toString() }.toSet()).apply()
    }

    /** 숨기기로 한 일정 제목. 같은 제목의 기기 캘린더 일정은 모두 표시하지 않는다. */
    fun hiddenTitles(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_HIDDEN_TITLES, emptySet()).orEmpty().toSet()

    fun setHiddenTitles(context: Context, titles: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_HIDDEN_TITLES, titles.toSet()).apply()
    }

    /** [from]~[to]에 있는 기기 캘린더 일정을 제목별로 묶은 목록(숨긴 제목 포함). 가까운 날짜순. */
    fun titles(context: Context, from: LocalDate, to: LocalDate): List<DeviceTitle> =
        query(context, from, to, includeHiddenTitles = true)
            .groupBy { it.title }
            .map { (title, items) ->
                DeviceTitle(title, items.minOf { it.start }, items.size, items.all { it.allDay })
            }
            .sortedWith(compareBy({ it.first }, { it.title }))

    /** 기기 캘린더 앱에서 표시 중인 캘린더 목록. */
    fun calendars(context: Context): List<DeviceCalendarInfo> {
        val out = ArrayList<DeviceCalendarInfo>()
        try {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(
                    CalendarContract.Calendars._ID,
                    CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                    CalendarContract.Calendars.ACCOUNT_NAME,
                ),
                "${CalendarContract.Calendars.VISIBLE} = 1", null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out += DeviceCalendarInfo(
                        id,
                        c.getString(1)?.takeIf { it.isNotBlank() } ?: "캘린더 $id",
                        c.getString(2).orEmpty(),
                    )
                }
            }
        } catch (e: Exception) {
            // 권한이 없거나 캘린더 저장소가 없으면 빈 목록.
        }
        return out.sortedBy { it.label }
    }
    private const val JOB_ID = 1001

    fun hasPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false) &&
            hasPermission(context)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, enabled).apply()
        if (enabled) watchChanges(context) else {
            (context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler)?.cancel(JOB_ID)
        }
    }

    /** 표시 중인 캘린더의 [from]~[to] 일정. 반복 일정은 저장소가 날짜별로 펼쳐서 준다. */
    fun query(
        context: Context, from: LocalDate, to: LocalDate, includeHiddenTitles: Boolean = false,
    ): List<DeviceItem> {
        val zone = ZoneId.systemDefault()
        // 종일 일정이 UTC 기준이라 앞뒤로 하루씩 넉넉히 읽고 아래에서 걸러낸다.
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let {
            ContentUris.appendId(it, from.minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
            ContentUris.appendId(it, to.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli())
            it.build()
        }
        val columns = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.DISPLAY_COLOR,
            CalendarContract.Instances.CALENDAR_ID,
        )
        val hidden = hiddenIds(context)
        val hiddenTitles = if (includeHiddenTitles) emptySet() else hiddenTitles(context)
        val out = ArrayList<DeviceItem>()
        try {
            context.contentResolver.query(
                uri, columns, "${CalendarContract.Instances.VISIBLE} = 1", null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getLong(6) in hidden) continue
                    val item = DeviceItem.of(
                        eventId = c.getLong(0),
                        title = c.getString(1),
                        begin = c.getLong(2),
                        end = c.getLong(3),
                        allDay = c.getInt(4) != 0,
                        color = c.getInt(5),
                        zone = zone,
                    )
                    if (item.title in hiddenTitles) continue
                    if (!item.end.isBefore(from) && !item.start.isAfter(to)) out += item
                }
            }
        } catch (e: Exception) {
            // 권한이 회수됐거나 캘린더 저장소가 없는 기기: 앱 일정만 보여준다.
        }
        return out
    }

    /** 기기 캘린더가 바뀌면 위젯을 다시 그리도록 감시를 건다. */
    fun watchChanges(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        val job = JobInfo.Builder(JOB_ID, ComponentName(context, CalendarChangeJob::class.java))
            .addTriggerContentUri(
                JobInfo.TriggerContentUri(
                    CalendarContract.CONTENT_URI,
                    JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                )
            )
            .setTriggerContentUpdateDelay(1000)
            .setTriggerContentMaxDelay(5000)
            .build()
        try {
            scheduler.schedule(job)
        } catch (e: Exception) {
            // 예약에 실패해도 위젯은 30분 주기 갱신으로 따라온다.
        }
    }
}

class CalendarChangeJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        // 한 번 울리면 감시가 끝나므로 updateAll 안에서 다시 건다.
        Widgets.updateAll(this)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false
}
