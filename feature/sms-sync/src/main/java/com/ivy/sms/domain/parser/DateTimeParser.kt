package com.ivy.sms.domain.parser

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
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

    fun parseDateTime(text: String, zone: ZoneId = ZoneId.systemDefault()): Either<String, Instant> {
        for (p in dateTimePatterns) {
            try {
                val fmt = DateTimeFormatter.ofPattern(p, Locale.ENGLISH)
                return LocalDateTime.parse(text.trim(), fmt).atZone(zone).toInstant().right()
            } catch (_: Exception) {
                // try next
            }
        }
        for (p in datePatterns) {
            try {
                val fmt = DateTimeFormatter.ofPattern(p, Locale.ENGLISH)
                return LocalDate.parse(text.trim(), fmt).atStartOfDay(zone).toInstant().right()
            } catch (_: Exception) {
                // try next
            }
        }
        return "DATE_PARSE_ERROR:no pattern matched '$text'".left()
    }
}
