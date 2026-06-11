package com.ivy.sms.domain.parser

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class DateTimeParserTest {

    @Test
    fun parses_iso() {
        DateTimeParser.parseDateTime("2026-04-27").getOrNull() shouldNotBe null
    }

    @Test
    fun parses_dmy_slashes() {
        DateTimeParser.parseDateTime("27/04/2026").getOrNull() shouldNotBe null
    }

    @Test
    fun parses_dmy_short() {
        DateTimeParser.parseDateTime("27/04/26").getOrNull() shouldNotBe null
    }

    @Test
    fun parses_dateTime_iso() {
        DateTimeParser.parseDateTime("2026-04-27 14:30").getOrNull() shouldNotBe null
    }

    @Test
    fun fails_onGarbage() {
        DateTimeParser.parseDateTime("not a date").isLeft() shouldNotBe false
    }

    @Test
    fun parsesLocalTime_hourMinute() {
        DateTimeParser.parseLocalTime("14:30").getOrNull() shouldBe LocalTime.of(14, 30)
    }

    @Test
    fun parsesLocalTime_withSeconds() {
        DateTimeParser.parseLocalTime("14:30:45").getOrNull() shouldBe LocalTime.of(14, 30, 45)
    }

    @Test
    fun parsesLocalTime_amPm() {
        DateTimeParser.parseLocalTime("02:30 PM").getOrNull() shouldBe LocalTime.of(14, 30)
    }

    @Test
    fun parsesLocalTime_failsOnGarbage() {
        DateTimeParser.parseLocalTime("not a time").isLeft() shouldBe true
    }

    @Test
    fun parsesLocalDate_dmySlashes() {
        DateTimeParser.parseLocalDate("27/04/2026").getOrNull() shouldBe
            LocalDate.of(2026, 4, 27)
    }

    @Test
    fun parsesLocalDateTime_rejectsDateOnlyText() {
        DateTimeParser.parseLocalDateTime("2026-04-27").isLeft() shouldBe true
    }
}
