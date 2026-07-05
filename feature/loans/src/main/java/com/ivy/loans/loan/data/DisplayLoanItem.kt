package com.ivy.loans.loan.data

import com.ivy.data.model.LoanItem
import java.time.Instant
import java.util.UUID

/** One payment toward an entry, for the expandable history (US6). */
data class EntryPaymentRow(
    val recordId: UUID,
    val amount: Double,
    val accountName: String?,
    val dateTime: Instant,
    /** Pre-formatted by the ViewModel (composable stays dumb). */
    val dateLabel: String,
    /** Jump-link target; null for legacy synthetic records ("no transaction"). */
    val transactionId: UUID? = null,
)

/**
 * Checklist entry + its DERIVED ledger state (design D1:C). paid/settled come
 * from the entry's loan records — `LoanItem.isSettled` is only the legacy flag
 * and is never trusted for display anymore.
 */
data class DisplayLoanItem(
    val loanItem: LoanItem,
    /** Sum of the entry's non-interest DECREASE records. */
    val paidAmount: Double = 0.0,
    /** Payment history, newest first. */
    val payments: List<EntryPaymentRow> = emptyList(),
) {
    val remaining: Double get() = (loanItem.amount - paidAmount).coerceAtLeast(0.0)

    /** Settled = fully covered by payments (currency-minor-unit tolerance). */
    val settledDerived: Boolean get() = paidAmount >= loanItem.amount - SETTLE_EPSILON

    val partiallyPaid: Boolean get() = !settledDerived && paidAmount > SETTLE_EPSILON

    /** Settled purely by pre-ledger history: every payment is a synthetic
     *  migration record with no transaction behind it → renders the
     *  "settled — no transaction" badge (D6). */
    val legacySettled: Boolean
        get() = settledDerived && payments.isNotEmpty() && payments.all { it.transactionId == null }

    companion object {
        const val SETTLE_EPSILON = 0.005
    }
}
