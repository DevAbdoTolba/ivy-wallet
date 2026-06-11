package com.ivy.sms.data

import java.text.Normalizer

/**
 * Single normalize chokepoint for SMS bodies. Run at ingest in
 * [SmsMessageMapper] so every downstream consumer (DrainParser tokenizer,
 * AmountParser, extractWildcardValues, dedup hashing) reads the same
 * canonical text.
 *
 * Phases:
 *   1. NFKC — collapses Unicode equivalence (full-width digits, presentation
 *      forms, ligatures) so look-alikes string-equal predictably.
 *   2. Strip invisible control marks: bidi embeds/isolates (U+200E, U+200F,
 *      U+202A-E, U+2066-9), zero-width joiners (U+200B-D), BOM (U+FEFF).
 *      Bank SMS sprinkle these around amounts and Arabic runs; they make
 *      identical-looking strings unequal and inflate the wildcard count.
 *   3. Arabic-Indic (U+0660-9) and Eastern Arabic-Indic (U+06F0-9) digits →
 *      ASCII 0-9. The amount regex is ASCII-only; without this "٥٠٢.١٦"
 *      silently produces AMOUNT_NOT_PARSEABLE.
 *   4. Whitespace collapse + trim. Java's `\s` only matches ASCII spacing,
 *      so Unicode spacing — non-breaking space (U+00A0), the narrow no-break
 *      space (U+202F) banks love to put before "EGP"/Arabic words, ogham
 *      space, the U+2000-200A run, ideographic space — is added explicitly.
 *      Without this "387.44. تابع" stayed a SINGLE token because the gap was
 *      a U+00A0, not an ASCII space, so the tokenizer never split it.
 *
 * Deliberately NOT done (would corrupt real transactional content):
 *   - Strip Arabic harakat / kashida (rare in SMS, expensive no-op).
 *   - Strip punctuation (would destroy 502.16 / 1,500 / 14:30 / 07/05/2026).
 *   - Strip stop words (token positions matter for alignment).
 */
object SmsBodyNormalizer {

    private val invisibleMarks = Regex(
        "[\\u200B-\\u200D\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]",
    )
    // ASCII \s PLUS the Unicode space separators that SMS senders actually
    // use: NBSP, ogham, the en/em-quad..hair-space run, line/para separators,
    // narrow-no-break, medium-math, ideographic space.
    private val whitespaceRun = Regex(
        "[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+",
    )

    fun normalize(body: String): String {
        if (body.isBlank()) return ""
        val nfkc = Normalizer.normalize(body, Normalizer.Form.NFKC)
        val noMarks = invisibleMarks.replace(nfkc, "")
        val asciiDigits = arabicDigitsToAscii(noMarks)
        return whitespaceRun.replace(asciiDigits, " ").trim()
    }

    private fun arabicDigitsToAscii(text: String): String {
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
}
