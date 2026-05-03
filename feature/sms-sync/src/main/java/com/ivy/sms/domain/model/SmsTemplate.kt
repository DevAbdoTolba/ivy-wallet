package com.ivy.sms.domain.model

import java.time.Instant

data class SmsTemplate(
    val id: SmsTemplateId,
    val pattern: String,
    val exampleBody: String,
    val wildcardSlots: List<WildcardSlot>,
    val state: TemplateState,
    val senderIdHint: String,
    val firstSeen: Instant,
    val lastSeen: Instant,
    val matchCount: Int,
    /** User-supplied label used as the resulting transaction's title.
     *  Null until the user enters one in the mapping screen. */
    val name: String? = null,
)

data class WildcardSlot(
    val id: WildcardId,
    val positionInPattern: Int,
    val contextSnippet: String,
    val exampleValue: String,
    val role: WildcardRole,
)

/**
 * One of eight roles a wildcard can be bound to (plus an initial Unmapped state).
 *
 * Amount roles (Income / Expense / Transfer) double as the template's transaction kind —
 * exactly one per template. CurrentTotal / TransactionFee / Date may appear at most once
 * each. Merchant and Ignored may repeat across multiple wildcards.
 */
sealed interface WildcardRole {
    data object Unmapped : WildcardRole
    data object Income : WildcardRole
    data object Expense : WildcardRole
    data object Transfer : WildcardRole
    data object CurrentTotal : WildcardRole
    data object TransactionFee : WildcardRole
    /** Full date+time, e.g. "2026-04-28 10:30" or "28/04/2026 10:30:45". */
    data object DateFull : WildcardRole
    /** Date only, e.g. "2026-04-28" or "28/04/2026". Time defaults to msg timestamp's. */
    data object DateOnly : WildcardRole
    /** Time only, e.g. "10:30" or "10:30:45". Date defaults to msg timestamp's. */
    data object TimeOnly : WildcardRole
    data object Merchant : WildcardRole
    data object Ignored : WildcardRole
}

fun WildcardRole.isAmountRole(): Boolean =
    this is WildcardRole.Income || this is WildcardRole.Expense || this is WildcardRole.Transfer

fun WildcardRole.isDateRole(): Boolean =
    this is WildcardRole.DateFull || this is WildcardRole.DateOnly || this is WildcardRole.TimeOnly

/**
 * Roles that can appear at most once per template. Multiple wildcards may use Merchant
 * (concatenated into the description) and Ignored (skipped).
 *
 * Note: the three date variants share a single "date slot" — at most one of
 * DateFull/DateOnly/TimeOnly per template. Enforcement is in MapTemplateUseCase via
 * isDateRole().
 */
fun WildcardRole.isUnique(): Boolean = when (this) {
    WildcardRole.Income,
    WildcardRole.Expense,
    WildcardRole.Transfer,
    WildcardRole.CurrentTotal,
    WildcardRole.TransactionFee,
    WildcardRole.DateFull,
    WildcardRole.DateOnly,
    WildcardRole.TimeOnly -> true
    WildcardRole.Merchant,
    WildcardRole.Ignored,
    WildcardRole.Unmapped -> false
}

enum class TemplateState { UNMAPPED, ACTIVE, BLACKLISTED, PENDING_REVIEW }
