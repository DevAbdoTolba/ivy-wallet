package com.ivy.sms.ui

import androidx.compose.ui.unit.LayoutDirection

/**
 * Decides the layout direction for a single SMS body based on the first non-whitespace
 * character. Per the 2026-04-28 spec clarification, mixed-script bodies follow the
 * first character (acceptable for single-user scope).
 *
 * Arabic blocks covered:
 *   - Arabic (U+0600..U+06FF) — incl. Arabic-Indic digits ٠–٩
 *   - Arabic Supplement (U+0750..U+077F)
 *   - Arabic Presentation Forms-A (U+FB50..U+FDFF)
 *   - Arabic Presentation Forms-B (U+FE70..U+FEFF)
 */
fun directionFor(body: String): LayoutDirection {
    val firstChar = body.firstOrNull { !it.isWhitespace() } ?: return LayoutDirection.Ltr
    val cp = firstChar.code
    val isArabic = cp in 0x0600..0x06FF ||
        cp in 0x0750..0x077F ||
        cp in 0xFB50..0xFDFF ||
        cp in 0xFE70..0xFEFF
    return if (isArabic) LayoutDirection.Rtl else LayoutDirection.Ltr
}
