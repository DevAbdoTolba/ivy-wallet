package com.ivy.sms.domain.parser

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object DateTimeParser {

    private val datePatterns = listOf(
        "yyyy-MM-dd",
        "dd/MM/yyyy",
        "MM/dd/yyyy",
        "dd/MM/yy",
        "dd-MMM-yyyy",
        "dd MMM yyyy",
    )

    private val dateTimePatterns = listOf(
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd HH:mm:ss",
        "dd/MM/yyyy HH:mm",
        "MM/dd/yyyy HH:mm",
        "dd/MM/yy HH:mm",
    )

    // A TimeOnly slot captures the time-of-day as its own token ("14:30",
    // "02:30 PM") — without these patterns the TimeOnly role could never
    // parse and silently fell back to the SMS timestamp.
    private val timePatterns = listOf(
        "HH:mm",
        "HH:mm:ss",
        "hh:mm a",
    )

    fun parseDateTime(text: String, zone: ZoneId = ZoneId.systemDefault()): Either<String, Instant> {
        parseLocalDateTime(text).getOrNull()?.let {
            return it.atZone(zone).toInstant().right()
        }
        parseLocalDate(text).getOrNull()?.let {
            return it.atStartOfDay(zone).toInstant().right()
        }
        return "DATE_PARSE_ERROR:no pattern matched '$text'".left()
    }

    /** Date+time patterns only — fails on date-only or time-only text. */
    fun parseLocalDateTime(text: String): Either<String, LocalDateTime> {
        for (p in dateTimePatterns) {
            try {
                val fmt = DateTimeFormatter.ofPattern(p, Locale.ENGLISH)
                return LocalDateTime.parse(text.trim(), fmt).right()
            } catch (_: Exception) {
                // try next
            }
        }
        return "DATE_PARSE_ERROR:no date-time pattern matched '$text'".left()
    }

    fun parseLocalDate(text: String): Either<String, LocalDate> {
        for (p in datePatterns) {
            try {
                val fmt = DateTimeFormatter.ofPattern(p, Locale.ENGLISH)
                return LocalDate.parse(text.trim(), fmt).right()
            } catch (_: Exception) {
                // try next
            }
        }
        return "DATE_PARSE_ERROR:no date pattern matched '$text'".left()
    }

    fun parseLocalTime(text: String): Either<String, LocalTime> {
        for (p in timePatterns) {
            try {
                val fmt = DateTimeFormatter.ofPattern(p, Locale.ENGLISH)
                return LocalTime.parse(text.trim(), fmt).right()
            } catch (_: Exception) {
                // try next
            }
        }
        return "TIME_PARSE_ERROR:no time pattern matched '$text'".left()
    }
}
