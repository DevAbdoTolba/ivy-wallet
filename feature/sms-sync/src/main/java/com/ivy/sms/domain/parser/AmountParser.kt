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
     * (U+06F0-U+06F9) digits to Latin 0-9 so SMS like "٨٠ج" or "٧٫٥ ر.س" parse
     * the same as their Latin-digit counterparts. Non-digit characters pass
     * through unchanged.
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
                if (afterDot == 3) {
                    listOf(cleaned.replace(".", ""), cleaned)
                } else {
                    listOf(cleaned)
                }
            }
            else -> listOf(cleaned)
        }
    }
}
