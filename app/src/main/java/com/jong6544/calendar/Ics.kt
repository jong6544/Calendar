package com.jong6544.calendar

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 캘린더 파일(.ics)을 읽어 앱 일정으로 바꾼다. */
object Ics {
    /** [simplified]: 앱이 지원하지 않는 반복 규칙이라 첫 회만 가져온 일정 수. */
    data class Result(val name: String?, val events: List<Event>, val simplified: Int)

    private const val MAX_DAYS = 60
    private val dateFormat = DateTimeFormatter.BASIC_ISO_DATE
    private val dateTimeFormat = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    private class Prop(val name: String, val params: Map<String, String>, val value: String)

    fun parse(text: String, zone: ZoneId, color: Int): Result {
        val events = ArrayList<Event>()
        var calendarName: String? = null
        var simplified = 0
        var current: HashMap<String, Prop>? = null
        var nested = 0

        for (line in unfold(text)) {
            val prop = parseLine(line) ?: continue
            when {
                prop.name == "BEGIN" && prop.value.equals("VEVENT", true) -> current = HashMap()
                prop.name == "END" && prop.value.equals("VEVENT", true) -> {
                    current?.let { fields -> if (toEvents(fields, zone, color, events)) simplified++ }
                    current = null
                    nested = 0
                }
                // 일정 안의 알림(VALARM) 등은 건너뛴다.
                current != null && prop.name == "BEGIN" -> nested++
                current != null && prop.name == "END" -> if (nested > 0) nested--
                current != null && nested == 0 -> current.putIfAbsent(prop.name, prop)
                prop.name == "X-WR-CALNAME" -> calendarName = unescape(prop.value).trim().ifEmpty { null }
            }
        }
        return Result(calendarName, events, simplified)
    }

    /** 긴 줄을 다음 줄 첫 칸 공백으로 이어 쓴 것을 한 줄로 합친다. */
    private fun unfold(text: String): List<String> {
        val out = ArrayList<StringBuilder>()
        for (raw in text.removePrefix("﻿").split("\r\n", "\n", "\r")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) {
                out.last().append(raw, 1, raw.length)
            } else if (raw.isNotEmpty()) {
                out += StringBuilder(raw)
            }
        }
        return out.map { it.toString() }
    }

    private fun parseLine(line: String): Prop? {
        // 값과 이름을 가르는 첫 ':' (따옴표 안의 것은 제외)
        var quoted = false
        var colon = -1
        for (i in line.indices) {
            val ch = line[i]
            if (ch == '"') quoted = !quoted
            else if (ch == ':' && !quoted) { colon = i; break }
        }
        if (colon <= 0) return null
        val head = line.substring(0, colon).split(';')
        val params = HashMap<String, String>()
        for (p in head.drop(1)) {
            val eq = p.indexOf('=')
            if (eq > 0) params[p.substring(0, eq).uppercase()] = p.substring(eq + 1).trim('"')
        }
        return Prop(head[0].uppercase(), params, line.substring(colon + 1))
    }

    private fun unescape(value: String): String {
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                when (val next = value[i + 1]) {
                    'n', 'N' -> sb.append(' ')
                    else -> sb.append(next)
                }
                i += 2
            } else {
                sb.append(ch)
                i++
            }
        }
        return sb.toString()
    }

    /** 날짜만 있으면 종일(LocalDate), 시간이 있으면 기기 시간대의 LocalDateTime. */
    private fun parseTime(prop: Prop, zone: ZoneId): Any? = try {
        val v = prop.value.trim()
        if (prop.params["VALUE"].equals("DATE", true) || v.length == 8) {
            LocalDate.parse(v.take(8), dateFormat)
        } else {
            val local = LocalDateTime.parse(v.take(15), dateTimeFormat)
            val source = when {
                v.endsWith("Z", true) -> ZoneOffset.UTC
                prop.params["TZID"] != null -> try { ZoneId.of(prop.params["TZID"]) } catch (e: Exception) { zone }
                else -> zone
            }
            local.atZone(source).withZoneSameInstant(zone).toLocalDateTime()
        }
    } catch (e: Exception) {
        null
    }

    /** 일정 하나를 [out]에 더한다. 반복 규칙을 단순화했으면 true. */
    private fun toEvents(fields: Map<String, Prop>, zone: ZoneId, color: Int, out: MutableList<Event>): Boolean {
        if (fields["STATUS"]?.value.equals("CANCELLED", true)) return false
        val start = fields["DTSTART"]?.let { parseTime(it, zone) } ?: return false
        val end = fields["DTEND"]?.let { parseTime(it, zone) }
        val title = unescape(fields["SUMMARY"]?.value.orEmpty()).trim().ifEmpty { "(제목 없음)" }
        val uid = fields["UID"]?.value?.trim()?.ifEmpty { null } ?: "auto:$title@${fields["DTSTART"]?.value}"

        var repeat = Event.REPEAT_NONE
        var simplified = false
        fields["RRULE"]?.value?.let { rule ->
            val parts = rule.uppercase().split(';').mapNotNull {
                val eq = it.indexOf('=')
                if (eq > 0) it.substring(0, eq) to it.substring(eq + 1) else null
            }.toMap()
            val simple = (parts["INTERVAL"] ?: "1") == "1" &&
                parts.keys.none { it == "COUNT" || it == "UNTIL" || it == "BYSETPOS" } &&
                !(parts["BYDAY"] ?: "").contains(',')
            repeat = when {
                !simple -> Event.REPEAT_NONE
                parts["FREQ"] == "WEEKLY" -> Event.REPEAT_WEEKLY
                parts["FREQ"] == "MONTHLY" && parts["BYDAY"] == null -> Event.REPEAT_MONTHLY
                parts["FREQ"] == "YEARLY" -> Event.REPEAT_YEARLY
                else -> Event.REPEAT_NONE
            }
            simplified = repeat == Event.REPEAT_NONE
        }

        if (start is LocalDate) {
            // 종일 일정의 종료일은 마지막 날의 다음 날이다. 여러 날이면 하루씩 나눠 담는다.
            val endExclusive = (end as? LocalDate)?.takeIf { it.isAfter(start) } ?: start.plusDays(1)
            val days = (endExclusive.toEpochDay() - start.toEpochDay()).toInt().coerceIn(1, MAX_DAYS)
            for (i in 0 until days) {
                out += Event(
                    title = title, epochDay = start.toEpochDay() + i, allDay = true,
                    startMin = 0, endMin = 0, color = color,
                    repeat = if (days == 1) repeat else Event.REPEAT_NONE,
                    uid = if (i == 0) uid else "$uid#$i",
                )
            }
            return simplified || (days > 1 && repeat != Event.REPEAT_NONE)
        }

        start as LocalDateTime
        val startMin = start.hour * 60 + start.minute
        val endMin = when (end) {
            is LocalDateTime -> when {
                end.toLocalDate().isAfter(start.toLocalDate()) -> 23 * 60 + 59
                else -> (end.hour * 60 + end.minute).coerceAtLeast(startMin)
            }
            else -> (startMin + 60).coerceAtMost(23 * 60 + 59)
        }
        out += Event(
            title = title, epochDay = start.toLocalDate().toEpochDay(), allDay = false,
            startMin = startMin, endMin = endMin, color = color, repeat = repeat, uid = uid,
        )
        return simplified
    }
}
