package com.ivy.sms.domain.parser

import io.kotest.matchers.shouldNotBe
import org.junit.Test

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
}
