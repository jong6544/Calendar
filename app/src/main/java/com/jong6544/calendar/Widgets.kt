package com.jong6544.calendar

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** 위젯별 상태(보고 있는 월, 선택한 날짜). 날짜가 바뀌면 오늘로 되돌아간다. */
data class WidgetState(val month: YearMonth, val selected: LocalDate) {
    companion object {
        private const val PREFS = "widget_state"

        fun load(context: Context, widgetId: Int, today: LocalDate): WidgetState {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (p.getLong("on_$widgetId", Long.MIN_VALUE) != today.toEpochDay()) {
                return WidgetState(YearMonth.from(today), today)
            }
            val ym = p.getInt("ym_$widgetId", today.year * 12 + today.monthValue - 1)
            val sel = p.getLong("sel_$widgetId", today.toEpochDay())
            return WidgetState(YearMonth.of(ym / 12, ym % 12 + 1), LocalDate.ofEpochDay(sel))
        }

        fun save(context: Context, widgetId: Int, today: LocalDate, state: WidgetState) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("on_$widgetId", today.toEpochDay())
                .putInt("ym_$widgetId", state.month.year * 12 + state.month.monthValue - 1)
                .putLong("sel_$widgetId", state.selected.toEpochDay())
                .apply()
        }

        fun clear(context: Context, widgetId: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .remove("on_$widgetId").remove("ym_$widgetId").remove("sel_$widgetId")
                .apply()
        }
    }
}

object Widgets {
    const val ACTION_SELECT = "com.jong6544.calendar.widget.SELECT"
    const val ACTION_NAV = "com.jong6544.calendar.widget.NAV"
    const val ACTION_TODAY = "com.jong6544.calendar.widget.TODAY"
    const val ACTION_REFRESH = "com.jong6544.calendar.widget.REFRESH"
    const val EXTRA_DAY = "day"
    const val EXTRA_DELTA = "delta"
    /** "다가오는 일정" 위젯이 내다보는 일수. */
    const val UPCOMING_DAYS = 90L

    private val providers = listOf(
        MonthAgendaWidget::class.java,
        AgendaWidget::class.java,
        TodayWidget::class.java,
    )

    /** 일정·테마·날짜가 바뀌었을 때 홈 화면의 모든 위젯을 다시 그린다. */
    fun updateAll(context: Context) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        var any = false
        for (provider in providers) {
            val ids = manager.getAppWidgetIds(ComponentName(context, provider))
            if (ids.isEmpty()) continue
            any = true
            for (id in ids) update(context, manager, provider, id)
        }
        if (any) {
            scheduleMidnightRefresh(context)
            if (DeviceCalendar.isEnabled(context)) DeviceCalendar.watchChanges(context)
        }
    }

    fun update(context: Context, manager: AppWidgetManager, provider: Class<*>, id: Int) {
        val today = LocalDate.now()
        val options = manager.getAppWidgetOptions(id)
        val views = when (provider) {
            MonthAgendaWidget::class.java -> {
                val month = WidgetState.load(context, id, today).month
                val agenda = Agenda.load(context, month.atDay(1), month.atEndOfMonth())
                WidgetRenderer.monthAgenda(context, id, options, agenda, today)
            }
            AgendaWidget::class.java -> WidgetRenderer.agenda(
                context, options, Agenda.load(context, today, today.plusDays(UPCOMING_DAYS)), today,
            )
            else -> WidgetRenderer.today(context, Agenda.load(context, today, today), today)
        }
        manager.updateAppWidget(id, views)
    }

    /** 자정이 지나면 "오늘" 표시가 바뀌도록 다음 자정에 새로고침을 예약한다. */
    fun scheduleMidnightRefresh(context: Context) {
        val alarms = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val nextMidnight = LocalDate.now().plusDays(1)
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, RefreshReceiver::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarms.set(AlarmManager.RTC, nextMidnight + 1000, pi)
    }
}

object WidgetRenderer {
    private const val IMMUTABLE = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private val weekdayIds = intArrayOf(
        R.id.wd0, R.id.wd1, R.id.wd2, R.id.wd3, R.id.wd4, R.id.wd5, R.id.wd6
    )

    private fun heightDp(options: Bundle?, fallback: Int): Int {
        val h = options?.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0) ?: 0
        return if (h > 0) h else fallback
    }

    private fun openApp(context: Context, date: LocalDate): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            MainActivity.intent(context, date)
                .setData(Uri.parse("calwidget://open/${date.toEpochDay()}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            IMMUTABLE,
        )

    private fun addEvent(context: Context, date: LocalDate): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            EventEditActivity.newIntent(context, date)
                .setData(Uri.parse("calwidget://add/${date.toEpochDay()}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            IMMUTABLE,
        )

    private fun broadcast(context: Context, widgetId: Int, action: String, key: String, extra: (Intent) -> Unit = {}): PendingIntent {
        val intent = Intent(context, MonthAgendaWidget::class.java)
            .setAction(action)
            .setData(Uri.parse("calwidget://$widgetId/$key"))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
        extra(intent)
        return PendingIntent.getBroadcast(context, 0, intent, IMMUTABLE)
    }

    private fun eventRow(context: Context, theme: WidgetTheme, e: Event, label: String): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_event_row).apply {
            setInt(R.id.ev_dot, "setColorFilter", e.color)
            setTextViewText(R.id.ev_title, e.title)
            setTextColor(R.id.ev_title, theme.text)
            setTextViewText(R.id.ev_time, label)
            setTextColor(R.id.ev_time, theme.subText)
        }

    private fun note(context: Context, theme: WidgetTheme, text: String): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_note).apply {
            setTextViewText(R.id.note_text, text)
            setTextColor(R.id.note_text, theme.subText)
        }

    /** 왼쪽에 월 달력, 오른쪽에 선택한 날의 일정을 보여주는 위젯. */
    fun monthAgenda(
        context: Context, widgetId: Int, options: Bundle?, agenda: Agenda, today: LocalDate,
    ): RemoteViews {
        val theme = WidgetTheme.current(context)
        val state = WidgetState.load(context, widgetId, today)
        val month = state.month
        val selected = state.selected
        val pkg = context.packageName

        val rv = RemoteViews(pkg, R.layout.widget_month_agenda)
        rv.setInt(R.id.root, "setBackgroundResource", theme.backgroundRes)

        rv.setTextViewText(R.id.title, "${month.year}년 ${month.monthValue}월")
        rv.setTextColor(R.id.title, theme.text)
        rv.setInt(R.id.btn_prev, "setColorFilter", theme.subText)
        rv.setInt(R.id.btn_next, "setColorFilter", theme.subText)
        rv.setInt(R.id.btn_add, "setColorFilter", theme.text)
        rv.setOnClickPendingIntent(R.id.btn_prev, broadcast(context, widgetId, Widgets.ACTION_NAV, "prev") {
            it.putExtra(Widgets.EXTRA_DELTA, -1)
        })
        rv.setOnClickPendingIntent(R.id.btn_next, broadcast(context, widgetId, Widgets.ACTION_NAV, "next") {
            it.putExtra(Widgets.EXTRA_DELTA, 1)
        })
        rv.setOnClickPendingIntent(R.id.title, broadcast(context, widgetId, Widgets.ACTION_TODAY, "today"))

        for (c in 0..6) {
            rv.setTextColor(
                weekdayIds[c],
                when (c) { 0 -> theme.sunday; 6 -> theme.saturday; else -> theme.subText },
            )
        }

        // 달력 격자
        rv.removeAllViews(R.id.grid)
        val offset = Dates.column(month.atDay(1))
        val length = month.lengthOfMonth()
        for (row in 0 until Dates.weekRows(month)) {
            val rowViews = RemoteViews(pkg, R.layout.widget_week_row)
            for (col in 0..6) {
                val cell = RemoteViews(pkg, R.layout.widget_day_cell)
                val day = row * 7 + col - offset + 1
                if (day in 1..length) {
                    val date = month.atDay(day)
                    val isToday = date == today
                    val isSelected = date == selected
                    cell.setTextViewText(R.id.day_num, day.toString())
                    cell.setTextColor(
                        R.id.day_num,
                        when {
                            isToday -> 0xFFFFFFFF.toInt()
                            col == 0 -> theme.sunday
                            col == 6 -> theme.saturday
                            else -> theme.text
                        },
                    )
                    if (isToday || isSelected) {
                        cell.setImageViewResource(
                            R.id.day_bg,
                            if (isToday) R.drawable.widget_circle else R.drawable.widget_ring,
                        )
                        cell.setInt(R.id.day_bg, "setColorFilter", theme.accent)
                        cell.setViewVisibility(R.id.day_bg, View.VISIBLE)
                    }
                    // 오늘은 원으로 채워지므로 밑줄을 겹쳐 그리지 않는다.
                    if (!isToday && agenda.has(date)) {
                        cell.setInt(R.id.day_mark, "setColorFilter", theme.accent)
                        cell.setViewVisibility(R.id.day_mark, View.VISIBLE)
                    }
                    cell.setOnClickPendingIntent(
                        R.id.cell,
                        broadcast(context, widgetId, Widgets.ACTION_SELECT, "day/${date.toEpochDay()}") {
                            it.putExtra(Widgets.EXTRA_DAY, date.toEpochDay())
                        },
                    )
                }
                rowViews.addView(R.id.week_row, cell)
            }
            rv.addView(R.id.grid, rowViews)
        }

        // 선택한 날의 일정
        rv.setTextViewText(R.id.sel_day, selected.dayOfMonth.toString())
        rv.setTextColor(R.id.sel_day, theme.text)
        val monthPrefix = if (YearMonth.from(selected) != month) "${selected.monthValue}월 · " else ""
        rv.setTextViewText(R.id.sel_weekday, monthPrefix + Dates.weekdayLong(selected))
        rv.setTextColor(R.id.sel_weekday, theme.subText)
        rv.setOnClickPendingIntent(R.id.btn_add, addEvent(context, selected))
        rv.setOnClickPendingIntent(R.id.side, openApp(context, selected))

        rv.removeAllViews(R.id.events)
        val dayEvents = agenda.on(selected)
        val capacity = ((heightDp(options, 180) - 60) / 38).coerceIn(1, 7)
        if (dayEvents.isEmpty()) {
            rv.addView(R.id.events, note(context, theme, context.getString(R.string.no_events)))
        } else {
            val shown = if (dayEvents.size > capacity) capacity - 1 else dayEvents.size
            for (e in dayEvents.take(shown)) {
                rv.addView(R.id.events, eventRow(context, theme, e, e.timeLabel()))
            }
            if (dayEvents.size > shown) {
                rv.addView(R.id.events, note(context, theme, "+${dayEvents.size - shown}개 더"))
            }
        }
        return rv
    }

    /** 오늘 날짜와 다가오는 일정을 보여주는 위젯. */
    fun agenda(context: Context, options: Bundle?, agenda: Agenda, today: LocalDate): RemoteViews {
        val theme = WidgetTheme.current(context)
        val rv = RemoteViews(context.packageName, R.layout.widget_agenda)
        rv.setInt(R.id.root, "setBackgroundResource", theme.backgroundRes)
        rv.setTextViewText(R.id.big_day, today.dayOfMonth.toString())
        rv.setTextColor(R.id.big_day, theme.text)
        rv.setTextViewText(R.id.big_sub, "${today.monthValue}월, ${Dates.weekdayLong(today)}")
        rv.setTextColor(R.id.big_sub, theme.subText)
        rv.setInt(R.id.btn_add, "setColorFilter", theme.text)
        rv.setOnClickPendingIntent(R.id.btn_add, addEvent(context, today))
        rv.setOnClickPendingIntent(R.id.root, openApp(context, today))

        rv.removeAllViews(R.id.events)
        val capacity = ((heightDp(options, 110) - 28) / 38).coerceIn(1, 8)
        val upcoming = upcoming(agenda, today, capacity)
        if (upcoming.isEmpty()) {
            rv.addView(R.id.events, note(context, theme, context.getString(R.string.no_upcoming)))
        }
        for ((date, e) in upcoming) {
            val prefix = when (date) {
                today -> ""
                today.plusDays(1) -> "내일 · "
                else -> "${date.monthValue}/${date.dayOfMonth} (${Dates.weekdayShort(date)}) · "
            }
            rv.addView(R.id.events, eventRow(context, theme, e, prefix + e.timeLabel()))
        }
        return rv
    }

    /** 오늘 날짜를 크게 보여주는 작은 위젯. */
    fun today(context: Context, agenda: Agenda, today: LocalDate): RemoteViews {
        val theme = WidgetTheme.current(context)
        val rv = RemoteViews(context.packageName, R.layout.widget_today)
        rv.setInt(R.id.root, "setBackgroundResource", theme.backgroundRes)
        rv.setTextViewText(R.id.today_top, "${today.monthValue}월 ${Dates.weekdayLong(today)}")
        rv.setTextColor(R.id.today_top, theme.accent)
        rv.setTextViewText(R.id.today_day, today.dayOfMonth.toString())
        rv.setTextColor(R.id.today_day, theme.text)
        val todays = agenda.on(today)
        rv.setTextViewText(
            R.id.today_summary,
            when (todays.size) {
                0 -> context.getString(R.string.no_events)
                1 -> todays[0].title
                else -> "${todays[0].title} 외 ${todays.size - 1}개"
            },
        )
        rv.setTextColor(R.id.today_summary, theme.subText)
        rv.setOnClickPendingIntent(R.id.root, openApp(context, today))
        return rv
    }

    /** 오늘부터 [Widgets.UPCOMING_DAYS]일 안의 일정을 날짜순으로 [limit]개까지. */
    fun upcoming(agenda: Agenda, today: LocalDate, limit: Int): List<Pair<LocalDate, Event>> {
        val out = ArrayList<Pair<LocalDate, Event>>()
        var d = today
        repeat(Widgets.UPCOMING_DAYS.toInt() + 1) {
            for (e in agenda.on(d)) {
                out += d to e
                if (out.size >= limit) return out
            }
            d = d.plusDays(1)
        }
        return out
    }
}

abstract class BaseWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) Widgets.update(context, manager, javaClass, id)
        Widgets.scheduleMidnightRefresh(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, manager: AppWidgetManager, appWidgetId: Int, newOptions: Bundle?,
    ) {
        Widgets.update(context, manager, javaClass, appWidgetId)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) WidgetState.clear(context, id)
    }
}

class MonthAgendaWidget : BaseWidget() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        val action = intent.action
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID ||
            (action != Widgets.ACTION_SELECT && action != Widgets.ACTION_NAV && action != Widgets.ACTION_TODAY)
        ) {
            super.onReceive(context, intent)
            return
        }

        val today = LocalDate.now()
        val state = WidgetState.load(context, id, today)
        val next = when (action) {
            Widgets.ACTION_SELECT -> {
                val day = intent.getLongExtra(Widgets.EXTRA_DAY, today.toEpochDay())
                state.copy(selected = LocalDate.ofEpochDay(day))
            }
            Widgets.ACTION_NAV -> {
                val month = state.month.plusMonths(intent.getIntExtra(Widgets.EXTRA_DELTA, 0).toLong())
                val selected = if (YearMonth.from(today) == month) today else month.atDay(1)
                WidgetState(month, selected)
            }
            else -> WidgetState(YearMonth.from(today), today)
        }
        WidgetState.save(context, id, today, next)
        Widgets.update(context, AppWidgetManager.getInstance(context), javaClass, id)
    }
}

class AgendaWidget : BaseWidget()

class TodayWidget : BaseWidget()

/** 자정·시간 변경·재부팅 때 위젯을 다시 그린다. */
class RefreshReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Widgets.updateAll(context)
    }
}
