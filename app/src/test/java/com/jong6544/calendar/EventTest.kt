package com.jong6544.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

class EventTest {
    private fun event(date: LocalDate, repeat: Int = Event.REPEAT_NONE, allDay: Boolean = false, start: Int = 600, title: String = "t") =
        Event(1, title, date.toEpochDay(), allDay, start, start + 60, 0, repeat)

    @Test
    fun singleEventOnlyOnItsDay() {
        val e = event(LocalDate.of(2026, 12, 20))
        assertTrue(e.occursOn(LocalDate.of(2026, 12, 20)))
        assertFalse(e.occursOn(LocalDate.of(2026, 12, 21)))
        assertFalse(e.occursOn(LocalDate.of(2027, 12, 20)))
    }

    @Test
    fun repeatsNeverBeforeStart() {
        val start = LocalDate.of(2026, 12, 20)
        for (r in 1..3) assertFalse(event(start, r).occursOn(start.minusYears(1)))
    }

    @Test
    fun weeklyMonthlyYearly() {
        val start = LocalDate.of(2026, 12, 20) // 일요일
        assertTrue(event(start, Event.REPEAT_WEEKLY).occursOn(LocalDate.of(2026, 12, 27)))
        assertFalse(event(start, Event.REPEAT_WEEKLY).occursOn(LocalDate.of(2026, 12, 26)))
        assertTrue(event(start, Event.REPEAT_MONTHLY).occursOn(LocalDate.of(2027, 1, 20)))
        assertFalse(event(start, Event.REPEAT_MONTHLY).occursOn(LocalDate.of(2027, 1, 21)))
        assertTrue(event(start, Event.REPEAT_YEARLY).occursOn(LocalDate.of(2030, 12, 20)))
        assertFalse(event(start, Event.REPEAT_YEARLY).occursOn(LocalDate.of(2027, 11, 20)))
    }

    @Test
    fun allDayFirstThenByStartTime() {
        val d = LocalDate.of(2026, 12, 20)
        val sorted = Event.on(
            listOf(event(d, start = 900, title = "late"), event(d, start = 540, title = "early"), event(d, allDay = true, title = "all")),
            d,
        )
        assertEquals(listOf("all", "early", "late"), sorted.map { it.title })
    }

    @Test
    fun weekRowsAndColumns() {
        assertEquals(5, Dates.weekRows(YearMonth.of(2026, 12))) // 1일이 화요일
        assertEquals(6, Dates.weekRows(YearMonth.of(2026, 8)))  // 1일이 토요일, 31일
        assertEquals(4, Dates.weekRows(YearMonth.of(2026, 2)))  // 1일이 일요일, 28일
        assertEquals(0, Dates.column(LocalDate.of(2026, 12, 20)))
        assertEquals("일요일", Dates.weekdayLong(LocalDate.of(2026, 12, 20)))
    }

    @Test
    fun upcomingIsChronologicalAndLimited() {
        val today = LocalDate.of(2026, 12, 20)
        val events = listOf(
            event(today.plusDays(3), title = "c"),
            event(today, title = "a"),
            event(today.minusDays(1), title = "past"),
            event(today.plusDays(1), title = "b"),
        )
        assertEquals(listOf("a", "b"), WidgetRenderer.upcoming(events, today, 2).map { it.second.title })
        assertEquals(listOf("a", "b", "c"), WidgetRenderer.upcoming(events, today, 9).map { it.second.title })
    }

    @Test
    fun timeLabels() {
        assertEquals("14:30 ~ 15:00", Event(0, "x", 0, false, 870, 900, 0).timeLabel())
        assertEquals("종일", Event(0, "x", 0, true, 0, 0, 0).timeLabel())
    }
}
