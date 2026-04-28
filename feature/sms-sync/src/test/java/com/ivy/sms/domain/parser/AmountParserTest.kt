package com.ivy.sms.domain.parser

import io.kotest.matchers.shouldBe
import org.junit.Test
import java.math.BigDecimal

class AmountParserTest {

    @Test
    fun parses_dotDecimal() {
        AmountParser.parseAmount("USD 12.34").getOrNull() shouldBe BigDecimal("12.34")
    }

    @Test
    fun parses_thousandsCommaWithDotDecimal() {
        AmountParser.parseAmount("$12,345.67 spent").getOrNull() shouldBe BigDecimal("12345.67")
    }

    @Test
    fun parses_europeanCommaDecimalWithDotThousands() {
        AmountParser.parseAmount("12.345,67 EUR").getOrNull() shouldBe BigDecimal("12345.67")
    }

    @Test
    fun parses_plainCommaDecimal() {
        AmountParser.parseAmount("12,34 EUR").getOrNull() shouldBe BigDecimal("12.34")
    }

    @Test
    fun parses_plainInteger() {
        AmountParser.parseAmount("amount=12").getOrNull() shouldBe BigDecimal("12")
    }

    @Test
    fun fails_onNoDigits() {
        AmountParser.parseAmount("nothing here").isLeft() shouldBe true
    }
}
