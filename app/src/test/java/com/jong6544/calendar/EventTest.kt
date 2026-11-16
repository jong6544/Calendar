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
        val sorted = Agenda(
            listOf(event(d, start = 900, title = "late"), event(d, start = 540, title = "early"), event(d, allDay = true, title = "all")),
        ).on(d)
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
        assertEquals(listOf("a", "b"), WidgetRenderer.upcoming(Agenda(events), today, 2).map { it.second.title })
        assertEquals(listOf("a", "b", "c"), WidgetRenderer.upcoming(Agenda(events), today, 9).map { it.second.title })
    }

    private val seoul = java.time.ZoneId.of("Asia/Seoul")
    private fun millis(y: Int, m: Int, d: Int, h: Int, min: Int, zone: java.time.ZoneId = seoul) =
        java.time.ZonedDateTime.of(y, m, d, h, min, 0, 0, zone).toInstant().toEpochMilli()

    @Test
    fun deviceTimedEvent() {
        val item = DeviceItem.of(5, "회의", millis(2026, 12, 20, 14, 30), millis(2026, 12, 20, 15, 0), false, 0x123456, seoul)
        assertEquals(LocalDate.of(2026, 12, 20), item.start)
        assertEquals(item.start, item.end)
        val e = Agenda(emptyList(), listOf(item)).on(item.start).single()
        assertEquals("14:30 ~ 15:00", e.timeLabel())
        assertEquals(0xFF123456.toInt(), e.color)
        assertEquals(5L, e.device?.eventId)
        assertTrue(Agenda(emptyList(), listOf(item)).on(item.start.plusDays(1)).isEmpty())
    }

    @Test
    fun deviceAllDayEventIsStoredInUtcAndEndsExclusive() {
        val utc = java.time.ZoneOffset.UTC
        // 12/20 하루짜리 종일 일정: UTC 12/20 0시 ~ 12/21 0시
        val one = DeviceItem.of(1, "생일", millis(2026, 12, 20, 0, 0, utc), millis(2026, 12, 21, 0, 0, utc), true, 0, seoul)
        assertEquals(LocalDate.of(2026, 12, 20), one.start)
        assertEquals(LocalDate.of(2026, 12, 20), one.end)
        // 12/24~12/26 사흘
        val trip = DeviceItem.of(2, "여행", millis(2026, 12, 24, 0, 0, utc), millis(2026, 12, 27, 0, 0, utc), true, 0, seoul)
        val agenda = Agenda(emptyList(), listOf(one, trip))
        assertFalse(agenda.has(LocalDate.of(2026, 12, 23)))
        for (day in 24..26) assertEquals("종일", agenda.on(LocalDate.of(2026, 12, day)).single().timeLabel())
        assertFalse(agenda.has(LocalDate.of(2026, 12, 27)))
        assertFalse(agenda.has(LocalDate.of(2026, 12, 21)))
    }

    @Test
    fun deviceEventAcrossMidnight() {
        // 12/20 22:00 ~ 12/22 09:00
        val item = DeviceItem.of(3, "출장", millis(2026, 12, 20, 22, 0), millis(2026, 12, 22, 9, 0), false, 0, seoul)
        val agenda = Agenda(emptyList(), listOf(item))
        assertEquals("22:00 ~ 24:00", agenda.on(LocalDate.of(2026, 12, 20)).single().timeLabel())
        assertEquals("종일", agenda.on(LocalDate.of(2026, 12, 21)).single().timeLabel())
        assertEquals("00:00 ~ 09:00", agenda.on(LocalDate.of(2026, 12, 22)).single().timeLabel())
        // 자정에 정확히 끝나면 다음 날에는 나오지 않는다.
        val party = DeviceItem.of(4, "파티", millis(2026, 12, 20, 20, 0), millis(2026, 12, 21, 0, 0), false, 0, seoul)
        assertEquals("20:00 ~ 24:00", Agenda(emptyList(), listOf(party)).on(LocalDate.of(2026, 12, 20)).single().timeLabel())
        assertFalse(Agenda(emptyList(), listOf(party)).has(LocalDate.of(2026, 12, 21)))
    }

    @Test
    fun timeLabels() {
        assertEquals("14:30 ~ 15:00", Event(0, "x", 0, false, 870, 900, 0).timeLabel())
        assertEquals("종일", Event(0, "x", 0, true, 0, 0, 0).timeLabel())
    }
}
