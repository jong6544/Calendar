package com.jong6544.calendar

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.time.LocalDate
import java.time.LocalTime

class EventEditActivity : Activity() {

    private var eventId = 0L
    private var date: LocalDate = LocalDate.now()
    private var startMin = 9 * 60
    private var endMin = 10 * 60
    private var color = Event.COLORS[0]

    private lateinit var titleInput: EditText
    private lateinit var dateView: TextView
    private lateinit var allDaySwitch: Switch
    private lateinit var timeRow: View
    private lateinit var startView: TextView
    private lateinit var endView: TextView
    private lateinit var repeatSpinner: Spinner
    private lateinit var colorRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_event_edit)

        titleInput = findViewById(R.id.input_title)
        dateView = findViewById(R.id.field_date)
        allDaySwitch = findViewById(R.id.switch_all_day)
        timeRow = findViewById(R.id.time_row)
        startView = findViewById(R.id.field_start)
        endView = findViewById(R.id.field_end)
        repeatSpinner = findViewById(R.id.spinner_repeat)
        colorRow = findViewById(R.id.color_row)

        repeatSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, Event.REPEAT_LABELS
        )

        // 기본 시작 시간: 다음 정시
        val nextHour = (LocalTime.now().hour + 1).coerceAtMost(22)
        startMin = nextHour * 60
        endMin = startMin + 60

        eventId = intent.getLongExtra(EXTRA_ID, 0L)
        val existing = if (eventId != 0L) EventStore.get(this).get(eventId) else null
        if (existing != null) {
            titleInput.setText(existing.title)
            date = existing.date
            allDaySwitch.isChecked = existing.allDay
            if (!existing.allDay) {
                startMin = existing.startMin
                endMin = existing.endMin
            }
            color = existing.color
            repeatSpinner.setSelection(existing.repeat.coerceIn(0, Event.REPEAT_LABELS.lastIndex))
        } else {
            eventId = 0L
            val day = intent.getLongExtra(EXTRA_DATE, Long.MIN_VALUE)
            if (day != Long.MIN_VALUE) date = LocalDate.ofEpochDay(day)
        }

        if (savedInstanceState != null) {
            date = LocalDate.ofEpochDay(savedInstanceState.getLong("date"))
            startMin = savedInstanceState.getInt("start")
            endMin = savedInstanceState.getInt("end")
            color = savedInstanceState.getInt("color")
        }

        findViewById<TextView>(R.id.screen_title).setText(
            if (eventId == 0L) R.string.new_event else R.string.edit_event
        )
        findViewById<View>(R.id.btn_delete).visibility = if (eventId == 0L) View.GONE else View.VISIBLE

        dateView.setOnClickListener {
            DatePickerDialog(this, { _, y, m, d ->
                date = LocalDate.of(y, m + 1, d)
                render()
            }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }
        startView.setOnClickListener {
            pickTime(startMin) {
                val length = (endMin - startMin).coerceAtLeast(0)
                startMin = it
                endMin = (it + length).coerceAtMost(23 * 60 + 59)
                render()
            }
        }
        endView.setOnClickListener {
            pickTime(endMin) {
                endMin = it
                if (endMin < startMin) startMin = endMin
                render()
            }
        }
        allDaySwitch.setOnCheckedChangeListener { _, _ -> render() }

        findViewById<View>(R.id.btn_cancel).setOnClickListener { finish() }
        findViewById<View>(R.id.btn_save).setOnClickListener { save() }
        findViewById<View>(R.id.btn_delete).setOnClickListener { confirmDelete() }

        buildColorRow()
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong("date", date.toEpochDay())
        outState.putInt("start", startMin)
        outState.putInt("end", endMin)
        outState.putInt("color", color)
    }

    private fun pickTime(initial: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(this, { _, h, m -> onPicked(h * 60 + m) }, initial / 60, initial % 60, true).show()
    }

    private fun buildColorRow() {
        val size = (36 * resources.displayMetrics.density).toInt()
        val margin = (6 * resources.displayMetrics.density).toInt()
        for (c in Event.COLORS) {
            val swatch = View(this)
            swatch.layoutParams = LinearLayout.LayoutParams(size, size).apply {
                setMargins(0, 0, margin * 2, 0)
            }
            swatch.tag = c
            swatch.setOnClickListener {
                color = c
                render()
            }
            colorRow.addView(swatch)
        }
    }

    private fun render() {
        dateView.text =
            "${date.year}년 ${date.monthValue}월 ${date.dayOfMonth}일 (${Dates.weekdayShort(date)})"
        timeRow.visibility = if (allDaySwitch.isChecked) View.GONE else View.VISIBLE
        startView.text = Event.formatTime(startMin)
        endView.text = Event.formatTime(endMin)

        val stroke = (3 * resources.displayMetrics.density).toInt()
        for (i in 0 until colorRow.childCount) {
            val swatch = colorRow.getChildAt(i)
            val c = swatch.tag as Int
            val isPicked = c == color
            swatch.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                if (isPicked) setStroke(stroke, 0xFF2B2B2B.toInt())
            }
        }
    }

    private fun save() {
        val title = titleInput.text.toString().trim()
        if (title.isEmpty()) {
            Toast.makeText(this, R.string.title_required, Toast.LENGTH_SHORT).show()
            titleInput.requestFocus()
            return
        }
        val allDay = allDaySwitch.isChecked
        EventStore.get(this).save(
            Event(
                id = eventId,
                title = title,
                epochDay = date.toEpochDay(),
                allDay = allDay,
                startMin = if (allDay) 0 else startMin,
                endMin = if (allDay) 0 else endMin,
                color = color,
                repeat = repeatSpinner.selectedItemPosition,
            )
        )
        Widgets.updateAll(this)
        finish()
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setMessage(R.string.confirm_delete)
            .setPositiveButton(R.string.delete) { _, _ ->
                EventStore.get(this).delete(eventId)
                Widgets.updateAll(this)
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    companion object {
        const val EXTRA_ID = "id"
        const val EXTRA_DATE = "date"

        fun newIntent(context: Context, date: LocalDate): Intent =
            Intent(context, EventEditActivity::class.java).putExtra(EXTRA_DATE, date.toEpochDay())

        fun editIntent(context: Context, id: Long): Intent =
            Intent(context, EventEditActivity::class.java).putExtra(EXTRA_ID, id)
    }
}
