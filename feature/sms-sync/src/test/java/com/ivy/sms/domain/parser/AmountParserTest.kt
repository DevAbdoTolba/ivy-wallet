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
    fun parses_loneDotWithThreeDecimals_asDecimal() {
        // Egyptian banks print 3-decimal balances: "500.000 EGP" is 500, not 500000.
        AmountParser.parseAmount("500.000 EGP").getOrNull() shouldBe BigDecimal("500.000")
    }

    @Test
    fun parses_loneDotThousandsLookalike_asDecimal() {
        AmountParser.parseAmount("1.500").getOrNull() shouldBe BigDecimal("1.500")
    }

    @Test
    fun parses_commaThousandsWithDotDecimal() {
        AmountParser.parseAmount("1,500.25").getOrNull() shouldBe BigDecimal("1500.25")
    }

    @Test
    fun parses_multiGroupDottedThousands() {
        AmountParser.parseAmount("1.234.567").getOrNull() shouldBe BigDecimal("1234567")
    }

    @Test
    fun parses_arabicDecimalSeparator() {
        AmountParser.parseAmount("٧٫٥").getOrNull() shouldBe BigDecimal("7.5")
    }

    @Test
    fun parses_arabicThousandsSeparator() {
        AmountParser.parseAmount("١٬٥٠٠").getOrNull() shouldBe BigDecimal("1500")
    }

    @Test
    fun fails_onNoDigits() {
        AmountParser.parseAmount("nothing here").isLeft() shouldBe true
    }
}
