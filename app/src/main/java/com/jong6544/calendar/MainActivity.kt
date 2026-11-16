package com.jong6544.calendar

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ListView
import android.widget.TextView
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
    private var events: List<Event> = emptyList()

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
            startActivity(EventEditActivity.editIntent(this, adapter.items[position].id))
        }

        monthView.eventColors = { d -> Event.on(events, d).map { it.color } }
        monthView.onDateSelected = { select(it) }
        monthView.onMonthSwipe = { moveMonth(it) }

        findViewById<View>(R.id.btn_prev).setOnClickListener { moveMonth(-1) }
        findViewById<View>(R.id.btn_next).setOnClickListener { moveMonth(1) }
        findViewById<View>(R.id.btn_today).setOnClickListener { select(LocalDate.now()) }
        findViewById<View>(R.id.btn_theme).setOnClickListener { chooseTheme() }
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
        events = EventStore.get(this).all()
        titleView.text = "${month.year}년 ${month.monthValue}월"
        monthView.month = month
        monthView.selected = selected
        dayLabel.text = "${selected.monthValue}월 ${selected.dayOfMonth}일 ${Dates.weekdayLong(selected)}"
        adapter.items = Event.on(events, selected)
        adapter.notifyDataSetChanged()
        emptyView.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
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
            view.findViewById<TextView>(R.id.item_time).text = e.timeLabel() + repeat
            return view
        }
    }

    companion object {
        const val EXTRA_DATE = "date"
        private const val STATE_DATE = "selected"

        fun intent(context: Context, date: LocalDate): Intent =
            Intent(context, MainActivity::class.java).putExtra(EXTRA_DATE, date.toEpochDay())
    }
}
