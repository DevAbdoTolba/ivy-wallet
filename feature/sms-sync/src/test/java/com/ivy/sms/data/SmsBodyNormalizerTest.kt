package com.ivy.sms.data

import io.kotest.matchers.shouldBe
import org.junit.Test

class SmsBodyNormalizerTest {

    @Test
    fun `nfkc collapses fullwidth digits to ascii`() {
        SmsBodyNormalizer.normalize("Amount １２３") shouldBe "Amount 123"
    }

    @Test
    fun `strips left-to-right and right-to-left marks around amounts`() {
        val withMarks = "Total‎ 1,500‏ EGP"
        SmsBodyNormalizer.normalize(withMarks) shouldBe "Total 1,500 EGP"
    }

    @Test
    fun `strips bidi isolate brackets U+2066 to U+2069`() {
        val withIsolates = "Total ⁦1,500⁩ EGP"
        SmsBodyNormalizer.normalize(withIsolates) shouldBe "Total 1,500 EGP"
    }

    @Test
    fun `strips zero-width joiners and BOM`() {
        val withZW = "﻿caf‍e‌ bill"
        SmsBodyNormalizer.normalize(withZW) shouldBe "cafe bill"
    }

    @Test
    fun `arabic-indic digits become ascii in mixed body`() {
        SmsBodyNormalizer.normalize("شراء ٥٠٢.١٦ جنيه") shouldBe
            "شراء 502.16 جنيه"
    }

    @Test
    fun `eastern arabic-indic digits become ascii`() {
        SmsBodyNormalizer.normalize("Total ۵۰۲ EGP") shouldBe "Total 502 EGP"
    }

    @Test
    fun `whitespace collapses and the body is trimmed`() {
        SmsBodyNormalizer.normalize("  Receive   100   EGP\t\n now  ") shouldBe
            "Receive 100 EGP now"
    }

    @Test
    fun `decimal point in amount is preserved`() {
        SmsBodyNormalizer.normalize("Total 1,500.75 EGP") shouldBe "Total 1,500.75 EGP"
    }

    @Test
    fun `time colon and date slash are preserved`() {
        SmsBodyNormalizer.normalize("at 14:30 on 07/05/2026") shouldBe
            "at 14:30 on 07/05/2026"
    }

    @Test
    fun `arabic harakat are kept — we do not strip diacritics`() {
        // Bank SMS rarely carry harakat, but if they do they don't change
        // routing — and stripping diacritics would change visible glyphs the
        // user might rely on, e.g., to distinguish brand names. Keep them.
        val withHaraka = "تَم استلام"
        SmsBodyNormalizer.normalize(withHaraka) shouldBe "تَم استلام"
    }

    @Test
    fun `blank input returns empty string`() {
        SmsBodyNormalizer.normalize("   \n\t  ") shouldBe ""
    }

    @Test
    fun `normalize is idempotent`() {
        val input = "  Total‎ ١٢٣٤  EGP  "
        val once = SmsBodyNormalizer.normalize(input)
        val twice = SmsBodyNormalizer.normalize(once)
        twice shouldBe once
        once shouldBe "Total 1234 EGP"
    }
}
