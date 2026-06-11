package com.ivy.sms.domain.parser

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import java.math.BigDecimal

object AmountParser {
    // Two alternatives:
    //   1. Thousands-separated number: \d{1,3}(?:[,. \s]\d{3})+ — requires
    //      at least one separator+3-digit group, so "1,500" / "1 000 000" hit this.
    //   2. Plain unseparated number: \d+ — matches "8500", "100", "13220".
    // Decimal tail (`[.,]\d{1,4}`) is optional and applies to either alternative.
    //
    // The previous regex used `\d{1,3}(...)*` with the * making the thousands group
    // optional, which silently dropped the trailing digit on plain 4+ digit
    // numbers: "8500" matched only the first 3 digits ("850") and then exited.
    // The user reported transferring 8500 EGP and the wallet showing 850.
    private val numberToken = Regex("[+-]?(?:\\d{1,3}(?:[,.\\u00A0\\s]\\d{3})+|\\d+)(?:[.,]\\d{1,4})?")

    fun parseAmount(text: String): Either<String, BigDecimal> {
        val normalized = normalizeArabicDigits(text)
        val match = numberToken.find(normalized)?.value
            ?: return "AMOUNT_PARSE_ERROR:no number in '$text'".left()
        return tryNormalize(match)
            ?: "AMOUNT_PARSE_ERROR:cannot interpret '$match'".left()
    }

    /**
     * Convert Arabic-Indic (U+0660-U+0669) and Eastern Arabic-Indic / Persian
     * (U+06F0-U+06F9) digits to Latin 0-9, the Arabic decimal separator
     * U+066B (٫) to '.' and the Arabic thousands separator U+066C (٬) to ','
     * so SMS like "٨٠ج" or "٧٫٥ ر.س" parse the same as their Latin
     * counterparts. Without the separator mapping "٧٫٥" collapsed to "7٫5"
     * and the ASCII-only regex matched the bare "7" — a silently wrong
     * amount. Other characters pass through unchanged.
     */
    private fun normalizeArabicDigits(text: String): String {
        if (text.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (ch in text) {
            val code = ch.code
            sb.append(
                when {
                    code in 0x0660..0x0669 -> ('0' + (code - 0x0660))
                    code in 0x06F0..0x06F9 -> ('0' + (code - 0x06F0))
                    code == 0x066B -> '.'
                    code == 0x066C -> ','
                    else -> ch
                },
            )
        }
        return sb.toString()
    }

    private fun tryNormalize(raw: String): Either<String, BigDecimal>? {
        val candidates = generateCandidates(raw)
        for (c in candidates) {
            try {
                return BigDecimal(c).right()
            } catch (_: NumberFormatException) {
                continue
            }
        }
        return null
    }

    private fun generateCandidates(raw: String): List<String> {
        val cleaned = raw.replace(" ", "").replace(" ", "")
        val lastDot = cleaned.lastIndexOf('.')
        val lastComma = cleaned.lastIndexOf(',')
        return when {
            lastDot >= 0 && lastComma >= 0 -> if (lastDot > lastComma) {
                listOf(cleaned.replace(",", ""))
            } else {
                listOf(cleaned.replace(".", "").replace(',', '.'))
            }
            lastComma >= 0 -> {
                val afterComma = cleaned.length - lastComma - 1
                if (afterComma == 3 && !cleaned.contains('.')) {
                    listOf(cleaned.replace(",", ""))
                } else {
                    listOf(cleaned.replace(',', '.'), cleaned.replace(",", ""))
                }
            }
            lastDot >= 0 -> {
                val afterDot = cleaned.length - lastDot - 1
                val dotCount = cleaned.count { it == '.' }
                when {
                    // Multi-group dotted thousands: "1.234.567" → 1234567.
                    dotCount > 1 && afterDot == 3 -> listOf(cleaned.replace(".", ""))
                    // Multiple dots with a non-3-digit tail: dotted thousands
                    // groups plus a decimal tail, "1.234.56" → 1234.56.
                    dotCount > 1 -> listOf(
                        cleaned.substring(0, lastDot).replace(".", "") +
                            "." + cleaned.substring(lastDot + 1),
                    )
                    // A LONE dot with a 3-digit tail is genuinely ambiguous
                    // ("1.500" is 1500 in many locales), but Egyptian banks
                    // print 3-decimal balances ("500.000 EGP" meaning 500) —
                    // prefer the DECIMAL reading: the silent 1000x inflation
                    // is the dangerous error direction.
                    else -> listOf(cleaned)
                }
            }
            else -> listOf(cleaned)
        }
    }
}
