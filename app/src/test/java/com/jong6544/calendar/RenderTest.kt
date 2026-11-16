package com.jong6544.calendar

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

/** 위젯과 화면을 실제로 그려 build/screenshots 에 PNG로 남긴다. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xhdpi")
class RenderTest {
    private lateinit var context: Context
    private val today = LocalDate.of(2026, 12, 20)
    private val outDir = File("build/screenshots").apply { mkdirs() }

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        val store = EventStore.get(context)
        store.all().forEach { store.delete(it.id) }
        fun add(title: String, day: Int, allDay: Boolean, start: Int, end: Int, color: Int, repeat: Int = 0) =
            store.save(Event(0, title, LocalDate.of(2026, 12, day).toEpochDay(), allDay, start, end, Event.COLORS[color], repeat))
        add("엄마 생일", 20, true, 0, 0, 0, Event.REPEAT_YEARLY)
        add("마케팅 기획 회의", 20, false, 14 * 60 + 30, 15 * 60, 1)
        add("가족 식사", 20, false, 19 * 60, 20 * 60, 2)
        add("치과 예약", 10, false, 10 * 60, 11 * 60, 3)
        add("운동", 11, false, 7 * 60, 8 * 60, 4)
        add("송년회", 22, false, 18 * 60, 21 * 60, 5)
    }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()

    private fun snapshot(view: View, w: Int, h: Int, name: String) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, w, h)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun render(rv: RemoteViews, wDp: Int, hDp: Int, name: String): View {
        val view = rv.apply(context, FrameLayout(context))
        snapshot(view, dp(wDp), dp(hDp), name)
        return view
    }

    private fun options(heightDp: Int) = Bundle().apply {
        putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, heightDp)
    }

    @Test
    fun monthAgendaWidget() {
        val events = EventStore.get(context).all()
        for (theme in WidgetTheme.entries) {
            WidgetTheme.set(context, theme)
            val view = render(
                WidgetRenderer.monthAgenda(context, 1, options(200), events, today),
                340, 200, "widget_month_agenda_${theme.name.lowercase()}",
            )
            assertEquals("2026년 12월", view.findViewById<TextView>(R.id.title).text.toString())
            assertEquals("20", view.findViewById<TextView>(R.id.sel_day).text.toString())
        }
    }

    @Test
    fun monthAgendaSelectionAndNavigation() {
        val widget = MonthAgendaWidget()
        val now = LocalDate.now()
        val id = org.robolectric.Shadows.shadowOf(AppWidgetManager.getInstance(context))
            .createWidget(MonthAgendaWidget::class.java, R.layout.widget_month_agenda)
        fun send(action: String, block: Intent.() -> Unit = {}) = widget.onReceive(
            context,
            Intent(context, MonthAgendaWidget::class.java).setAction(action)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id).apply(block),
        )

        val picked = now.withDayOfMonth(1)
        send(Widgets.ACTION_SELECT) { putExtra(Widgets.EXTRA_DAY, picked.toEpochDay()) }
        assertEquals(picked, WidgetState.load(context, id, now).selected)

        send(Widgets.ACTION_NAV) { putExtra(Widgets.EXTRA_DELTA, 1) }
        val next = YearMonth.from(now).plusMonths(1)
        assertEquals(WidgetState(next, next.atDay(1)), WidgetState.load(context, id, now))

        send(Widgets.ACTION_TODAY)
        assertEquals(WidgetState(YearMonth.from(now), now), WidgetState.load(context, id, now))

        // 날짜가 바뀌면 선택이 오늘로 초기화된다.
        send(Widgets.ACTION_NAV) { putExtra(Widgets.EXTRA_DELTA, 3) }
        val tomorrow = now.plusDays(1)
        assertEquals(WidgetState(YearMonth.from(tomorrow), tomorrow), WidgetState.load(context, id, tomorrow))
    }

    @Test
    fun monthAgendaOverflowAndSixWeekMonth() {
        val store = EventStore.get(context)
        repeat(5) { store.save(Event(0, "추가 일정 $it", LocalDate.of(2026, 8, 1).toEpochDay(), false, 600 + it * 60, 660 + it * 60, Event.COLORS[it])) }
        val aug1 = LocalDate.of(2026, 8, 1)
        WidgetState.save(context, 2, aug1, WidgetState(YearMonth.of(2026, 8), aug1))
        render(WidgetRenderer.monthAgenda(context, 2, options(180), store.all(), aug1), 300, 180, "widget_month_agenda_overflow")
    }

    @Test
    fun agendaAndTodayWidgets() {
        val events = EventStore.get(context).all()
        WidgetTheme.set(context, WidgetTheme.SKY)
        render(WidgetRenderer.agenda(context, options(150), events, today), 340, 150, "widget_agenda")
        WidgetTheme.set(context, WidgetTheme.CREAM)
        render(WidgetRenderer.today(context, events, today), 150, 150, "widget_today")
        render(WidgetRenderer.agenda(context, null, emptyList(), today), 340, 110, "widget_agenda_empty")
    }

    private fun shoot(activity: Activity, name: String) =
        snapshot(activity.window.decorView, dp(411), dp(891), name)

    @Test
    fun activities() {
        val main = Robolectric.buildActivity(
            MainActivity::class.java, MainActivity.intent(context, today),
        ).setup().get()
        assertEquals("2026년 12월", main.findViewById<TextView>(R.id.title).text.toString())
        assertEquals(3, main.findViewById<android.widget.ListView>(R.id.event_list).adapter.count)
        shoot(main, "app_main")

        val id = EventStore.get(context).all().first { it.title == "마케팅 기획 회의" }.id
        val edit = Robolectric.buildActivity(
            EventEditActivity::class.java, EventEditActivity.editIntent(context, id),
        ).setup().get()
        shoot(edit, "app_edit")

        // 제목을 바꿔 저장하면 DB에 반영된다.
        edit.findViewById<TextView>(R.id.input_title).text = "기획 회의"
        edit.findViewById<View>(R.id.btn_save).performClick()
        assertEquals("기획 회의", EventStore.get(context).get(id)?.title)

        // 새 일정 추가
        val create = Robolectric.buildActivity(
            EventEditActivity::class.java, EventEditActivity.newIntent(context, today),
        ).setup().get()
        create.findViewById<TextView>(R.id.input_title).text = "새 일정"
        create.findViewById<View>(R.id.btn_save).performClick()
        assertNotNull(EventStore.get(context).all().firstOrNull { it.title == "새 일정" && it.date == today })
    }
}
