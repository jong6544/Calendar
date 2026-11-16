package com.jong6544.calendar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs
import kotlin.math.min

/** 앱 화면의 월간 달력. 날짜를 누르면 선택, 좌우로 넘기면 월 이동. */
class MonthView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var month: YearMonth = YearMonth.now()
        set(value) { field = value; invalidate() }
    var selected: LocalDate = LocalDate.now()
        set(value) { field = value; invalidate() }

    /** 날짜별 일정 색(점으로 표시). */
    var eventColors: (LocalDate) -> List<Int> = { emptyList() }
    var onDateSelected: ((LocalDate) -> Unit)? = null
    /** +1: 다음 달, -1: 이전 달 */
    var onMonthSwipe: ((Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val headerHeight = 30 * density
    private val accent = 0xFFF28C28.toInt()
    private val textColor = 0xFF2B2B2B.toInt()
    private val sunColor = 0xFFE5484D.toInt()
    private val satColor = 0xFF3B82F6.toInt()

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 15 * resources.displayMetrics.scaledDensity
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 12 * resources.displayMetrics.scaledDensity
    }
    private val shapePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            dateAt(e.x, e.y)?.let {
                selected = it
                onDateSelected?.invoke(it)
            }
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (e1 == null) return false
            val dx = e2.x - e1.x
            if (abs(dx) > 60 * density && abs(dx) > abs(e2.y - e1.y) * 1.5f) {
                onMonthSwipe?.invoke(if (dx < 0) 1 else -1)
                return true
            }
            return false
        }
    })

    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    private fun dateAt(x: Float, y: Float): LocalDate? {
        if (y < headerHeight || width == 0) return null
        val rows = Dates.weekRows(month)
        val cellW = width / 7f
        val cellH = (height - headerHeight) / rows
        val col = (x / cellW).toInt().coerceIn(0, 6)
        val row = ((y - headerHeight) / cellH).toInt().coerceIn(0, rows - 1)
        val day = row * 7 + col - Dates.column(month.atDay(1)) + 1
        return if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
    }

    override fun onDraw(canvas: Canvas) {
        val cellW = width / 7f
        val rows = Dates.weekRows(month)
        val cellH = (height - headerHeight) / rows
        val today = LocalDate.now()

        for (c in 0..6) {
            headerPaint.color = when (c) { 0 -> sunColor; 6 -> satColor; else -> 0xFF8A8A8A.toInt() }
            canvas.drawText(Dates.WEEKDAYS[c], cellW * (c + 0.5f), headerHeight * 0.65f, headerPaint)
        }

        val offset = Dates.column(month.atDay(1))
        val radius = min(min(cellW, cellH) * 0.34f, 17 * density)
        val dotR = 2.5f * density
        for (day in 1..month.lengthOfMonth()) {
            val date = month.atDay(day)
            val idx = offset + day - 1
            val cx = cellW * (idx % 7 + 0.5f)
            val top = headerHeight + cellH * (idx / 7)
            val cy = top + min(cellH * 0.4f, radius + 4 * density)

            val isToday = date == today
            if (isToday) {
                shapePaint.style = Paint.Style.FILL
                shapePaint.color = accent
                canvas.drawCircle(cx, cy, radius, shapePaint)
            }
            if (date == selected && !isToday) {
                shapePaint.style = Paint.Style.STROKE
                shapePaint.strokeWidth = 1.5f * density
                shapePaint.color = accent
                canvas.drawCircle(cx, cy, radius, shapePaint)
            }

            textPaint.typeface = if (isToday) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textPaint.color = when {
                isToday -> 0xFFFFFFFF.toInt()
                Dates.isSunday(date) -> sunColor
                Dates.isSaturday(date) -> satColor
                else -> textColor
            }
            canvas.drawText(day.toString(), cx, cy - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)

            val colors = eventColors(date).take(4)
            if (colors.isNotEmpty()) {
                shapePaint.style = Paint.Style.FILL
                val gap = dotR * 3
                var x = cx - gap * (colors.size - 1) / 2
                val y = cy + radius + dotR + 3 * density
                for (color in colors) {
                    shapePaint.color = color
                    canvas.drawCircle(x, y, dotR, shapePaint)
                    x += gap
                }
            }
        }
    }
}
