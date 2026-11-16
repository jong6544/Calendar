package com.jong6544.calendar

import android.app.Activity
import android.Manifest
import android.app.AlertDialog
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.CalendarContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.YearMonth

class MainActivity : Activity() {

    private lateinit var monthView: MonthView
    private lateinit var titleView: TextView
    private lateinit var dayLabel: TextView
    private lateinit var emptyView: TextView
    private val adapter = EventAdapter()

    private var month: YearMonth = YearMonth.now()
    private var selected: LocalDate = LocalDate.now()
    private var agenda = Agenda(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        monthView = findViewById(R.id.month_view)
        titleView = findViewById(R.id.title)
        dayLabel = findViewById(R.id.day_label)
        emptyView = findViewById(R.id.empty)

        val list = findViewById<ListView>(R.id.event_list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            val e = adapter.items[position]
            if (e.device != null) openInDeviceCalendar(e.device)
            else startActivity(EventEditActivity.editIntent(this, e.id))
        }

        list.setOnItemLongClickListener { _, _, position, _ ->
            val e = adapter.items[position]
            if (e.device != null) confirmHideTitle(e.title)
            e.device != null
        }

        monthView.eventColors = { d -> agenda.on(d).map { it.color } }
        monthView.onDateSelected = { select(it) }
        monthView.onMonthSwipe = { moveMonth(it) }

        findViewById<View>(R.id.btn_prev).setOnClickListener { moveMonth(-1) }
        findViewById<View>(R.id.btn_next).setOnClickListener { moveMonth(1) }
        findViewById<View>(R.id.btn_today).setOnClickListener { select(LocalDate.now()) }
        findViewById<View>(R.id.btn_more).setOnClickListener { showMenu(it) }
        findViewById<View>(R.id.btn_add).setOnClickListener {
            startActivity(EventEditActivity.newIntent(this, selected))
        }

        val saved = savedInstanceState?.getLong(STATE_DATE, Long.MIN_VALUE) ?: Long.MIN_VALUE
        if (saved != Long.MIN_VALUE) {
            selected = LocalDate.ofEpochDay(saved)
            month = YearMonth.from(selected)
        } else {
            applyIntent(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
        refresh()
    }

    private fun applyIntent(intent: Intent?) {
        val day = intent?.getLongExtra(EXTRA_DATE, Long.MIN_VALUE) ?: Long.MIN_VALUE
        if (day != Long.MIN_VALUE) {
            selected = LocalDate.ofEpochDay(day)
            month = YearMonth.from(selected)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_DATE, selected.toEpochDay())
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun select(date: LocalDate) {
        selected = date
        month = YearMonth.from(date)
        refresh()
    }

    private fun moveMonth(delta: Int) {
        month = month.plusMonths(delta.toLong())
        val today = LocalDate.now()
        selected = if (YearMonth.from(today) == month) today else month.atDay(1)
        refresh()
    }

    private fun refresh() {
        agenda = Agenda.load(this, month.atDay(1), month.atEndOfMonth())
        titleView.text = "${month.year}년 ${month.monthValue}월"
        monthView.month = month
        monthView.selected = selected
        dayLabel.text = "${selected.monthValue}월 ${selected.dayOfMonth}일 ${Dates.weekdayLong(selected)}"
        adapter.items = agenda.on(selected)
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showMenu(anchor: View) {
        val syncOn = DeviceCalendar.isEnabled(this)
        PopupMenu(this, anchor).apply {
            menu.add(0, MENU_SYNC, 0, if (syncOn) R.string.sync_turn_off else R.string.sync_turn_on)
            if (syncOn) menu.add(0, MENU_CALENDARS, 1, R.string.choose_calendars)
            if (syncOn) menu.add(0, MENU_HIDE, 1, R.string.choose_hidden)
            menu.add(0, MENU_THEME, 2, R.string.widget_theme)
            setOnMenuItemClickListener {
                when (it.itemId) {
                    MENU_SYNC -> toggleSync(!syncOn)
                    MENU_CALENDARS -> chooseCalendars()
                    MENU_HIDE -> chooseHiddenTitles()
                    MENU_THEME -> chooseTheme()
                }
                true
            }
        }.show()
    }

    private fun toggleSync(enable: Boolean) {
        if (enable && !DeviceCalendar.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), REQUEST_CALENDAR)
            return
        }
        DeviceCalendar.setEnabled(this, enable)
        Toast.makeText(this, if (enable) R.string.sync_on else R.string.sync_off, Toast.LENGTH_SHORT).show()
        refresh()
        Widgets.updateAll(this)
        // 켜자마자 어떤 캘린더를 가져올지 고르게 한다.
        if (enable && DeviceCalendar.calendars(this).size > 1) chooseCalendars()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CALENDAR) return
        if (DeviceCalendar.hasPermission(this)) toggleSync(true)
        else Toast.makeText(this, R.string.sync_denied, Toast.LENGTH_LONG).show()
    }

    /** 기념일·공휴일처럼 필요 없는 캘린더를 연동에서 뺄 수 있게 한다. */
    private fun chooseCalendars() {
        val calendars = DeviceCalendar.calendars(this)
        if (calendars.isEmpty()) {
            Toast.makeText(this, R.string.no_calendars, Toast.LENGTH_SHORT).show()
            return
        }
        val hidden = DeviceCalendar.hiddenIds(this)
        val checked = BooleanArray(calendars.size) { calendars[it].id !in hidden }
        AlertDialog.Builder(this)
            .setTitle(R.string.choose_calendars)
            .setMultiChoiceItems(calendars.map { it.label }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                // 목록에 없는(지금은 안 보이는) 캘린더의 기존 선택은 그대로 둔다.
                val listed = calendars.map { it.id }.toSet()
                val nowHidden = calendars.filterIndexed { i, _ -> !checked[i] }.map { it.id }
                DeviceCalendar.setHiddenIds(this, (hidden - listed) + nowHidden)
                refresh()
                Widgets.updateAll(this)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmHideTitle(title: String) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.confirm_hide, title))
            .setPositiveButton(R.string.hide) { _, _ ->
                applyHiddenTitles(DeviceCalendar.hiddenTitles(this) + title)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun applyHiddenTitles(titles: Set<String>) {
        DeviceCalendar.setHiddenTitles(this, titles)
        refresh()
        Widgets.updateAll(this)
    }

    /** 공휴일·기념일처럼 내 일정과 같은 캘린더에 섞여 있는 일정을 제목으로 골라 숨긴다. */
    private fun chooseHiddenTitles() {
        val today = LocalDate.now()
        val found = DeviceCalendar.titles(this, today.minusDays(30), today.plusDays(365))
        val hidden = DeviceCalendar.hiddenTitles(this)
        // 기간 밖에 있어 목록에 안 잡힌 숨김 제목도 다시 켤 수 있게 맨 뒤에 붙인다.
        val titles = found.map { it.title } + (hidden - found.map { it.title }.toSet()).sorted()
        if (titles.isEmpty()) {
            Toast.makeText(this, R.string.no_device_events, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = titles.mapIndexed { i, t -> if (i < found.size) found[i].label else t }
        val checked = BooleanArray(titles.size) { titles[it] in hidden }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.choose_hidden_title)
            .setMultiChoiceItems(labels.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                applyHiddenTitles(titles.filterIndexed { i, _ -> checked[i] }.toSet())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.select_all_day, null)
            .show()
        // "종일 일정 모두 선택"은 창을 닫지 않고 체크만 채운다.
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            for (i in found.indices) {
                if (found[i].allDay) {
                    checked[i] = true
                    dialog.listView.setItemChecked(i, true)
                }
            }
        }
    }

    /** 기기 캘린더 일정은 읽기 전용이라, 누르면 원래 캘린더 앱에서 연다. */
    private fun openInDeviceCalendar(ref: DeviceRef) {
        val view = Intent(Intent.ACTION_VIEW)
            .setData(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, ref.eventId))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ref.begin)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ref.end)
        try {
            startActivity(view)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun chooseTheme() {
        val themes = WidgetTheme.entries
        AlertDialog.Builder(this)
            .setTitle(R.string.widget_theme)
            .setSingleChoiceItems(
                themes.map { it.label }.toTypedArray(),
                themes.indexOf(WidgetTheme.current(this)),
            ) { dialog, which ->
                WidgetTheme.set(this, themes[which])
                Widgets.updateAll(this)
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class EventAdapter : BaseAdapter() {
        var items: List<Event> = emptyList()

        override fun getCount() = items.size
        override fun getItem(position: Int) = items[position]
        override fun getItemId(position: Int) = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView
                ?: LayoutInflater.from(parent.context).inflate(R.layout.item_event, parent, false)
            val e = items[position]
            view.findViewById<View>(R.id.dot).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(e.color)
            }
            view.findViewById<TextView>(R.id.item_title).text = e.title
            val repeat = if (e.repeat != Event.REPEAT_NONE) " · ${Event.REPEAT_LABELS[e.repeat]}" else ""
            val source = if (e.device != null) " · ${getString(R.string.device_calendar)}" else ""
            view.findViewById<TextView>(R.id.item_time).text = e.timeLabel() + repeat + source
            return view
        }
    }

    companion object {
        const val EXTRA_DATE = "date"
        private const val STATE_DATE = "selected"
        private const val REQUEST_CALENDAR = 1
        private const val MENU_SYNC = 1
        private const val MENU_THEME = 2
        private const val MENU_CALENDARS = 3
        private const val MENU_HIDE = 4

        fun intent(context: Context, date: LocalDate): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_DATE, date.toEpochDay())
    }
}
