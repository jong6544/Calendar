package com.jong6544.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** 하루 단위 일정. 시간은 자정 기준 분(0..1439)으로 저장한다. */
data class Event(
    val id: Long = 0,
    val title: String,
    val epochDay: Long,
    val allDay: Boolean,
    val startMin: Int,
    val endMin: Int,
    val color: Int,
    val repeat: Int = REPEAT_NONE,
    /** 기기 캘린더에서 읽어 온 일정이면 원본 위치. 앱에서 만든 일정은 null. */
    val device: DeviceRef? = null,
) {
    val date: LocalDate get() = LocalDate.ofEpochDay(epochDay)

    /** 반복 규칙을 고려해 이 일정이 [d]에 해당하는지. */
    fun occursOn(d: LocalDate): Boolean {
        val start = date
        if (d.isBefore(start)) return false
        return when (repeat) {
            REPEAT_WEEKLY -> d.dayOfWeek == start.dayOfWeek
            REPEAT_MONTHLY -> d.dayOfMonth == start.dayOfMonth
            REPEAT_YEARLY -> d.monthValue == start.monthValue && d.dayOfMonth == start.dayOfMonth
            else -> d == start
        }
    }

    fun timeLabel(): String =
        if (allDay) "종일" else "${formatTime(startMin)} ~ ${formatTime(endMin)}"

    companion object {
        const val REPEAT_NONE = 0
        const val REPEAT_WEEKLY = 1
        const val REPEAT_MONTHLY = 2
        const val REPEAT_YEARLY = 3

        val REPEAT_LABELS = arrayOf("반복 안 함", "매주", "매월", "매년")

        val COLORS = intArrayOf(
            0xFF5B9BF0.toInt(), // 파랑
            0xFFF2C037.toInt(), // 노랑
            0xFFE8476A.toInt(), // 빨강
            0xFF4CAF7D.toInt(), // 초록
            0xFF9B6BDF.toInt(), // 보라
            0xFFF28C28.toInt(), // 주황
        )

        fun formatTime(min: Int): String = "%02d:%02d".format(min / 60, min % 60)
    }
}

/** 기기 캘린더 일정 한 회차: 일정 id와 그 회차의 시작/종료 시각(밀리초). */
data class DeviceRef(val eventId: Long, val begin: Long, val end: Long)

object Dates {
    val WEEKDAYS = arrayOf("일", "월", "화", "수", "목", "금", "토")

    /** 일요일=0 … 토요일=6 */
    fun column(d: LocalDate): Int = d.dayOfWeek.value % 7

    fun weekdayShort(d: LocalDate): String = WEEKDAYS[column(d)]

    fun weekdayLong(d: LocalDate): String = weekdayShort(d) + "요일"

    /** 달력에서 해당 월이 차지하는 주(행) 수. */
    fun weekRows(month: YearMonth): Int =
        (column(month.atDay(1)) + month.lengthOfMonth() + 6) / 7

    fun isSunday(d: LocalDate) = d.dayOfWeek == DayOfWeek.SUNDAY
    fun isSaturday(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY
}
