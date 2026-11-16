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
class Agenda(private val local: List<Event>, private val device: List<DeviceItem> = emptyList()) {

    /** [d]의 일정을 종일 → 시작 시간 순으로. */
    fun on(d: LocalDate): List<Event> {
        val out = ArrayList<Event>()
        local.filterTo(out) { it.occursOn(d) }
        for (item in device) {
            if (!d.isBefore(item.start) && !d.isAfter(item.end)) out += item.toEvent(d)
        }
        out.sortWith(compareBy<Event>({ !it.allDay }, { it.startMin }, { it.title }))
        return out
    }

    fun has(d: LocalDate): Boolean =
        local.any { it.occursOn(d) } || device.any { !d.isBefore(it.start) && !d.isAfter(it.end) }

    companion object {
        /** [from]~[to] 범위를 그리는 데 필요한 일정을 읽는다. */
        fun load(context: Context, from: LocalDate, to: LocalDate): Agenda = Agenda(
            EventStore.get(context).all(),
            if (DeviceCalendar.isEnabled(context)) DeviceCalendar.query(context, from, to) else emptyList(),
        )
    }
}

/** 기기에 등록된 캘린더 하나(예: 내 캘린더, 구글 계정, 기념일). */
data class DeviceCalendarInfo(val id: Long, val name: String, val account: String) {
    val label: String
        get() = if (account.isBlank() || account == name) name else "$name ($account)"
}

/** 기기 캘린더 연동(읽기 전용). */
object DeviceCalendar {
    private const val PREFS = "settings"
    private const val KEY = "device_calendar"
    private const val KEY_HIDDEN = "hidden_calendars"

    /** 연동에서 뺀 캘린더 id. */
    fun hiddenIds(context: Context): Set<Long> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_HIDDEN, emptySet()).orEmpty()
            .mapNotNull { it.toLongOrNull() }.toSet()

    fun setHiddenIds(context: Context, ids: Set<Long>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_HIDDEN, ids.map { it.toString() }.toSet()).apply()
    }

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
    fun query(context: Context, from: LocalDate, to: LocalDate): List<DeviceItem> {
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
