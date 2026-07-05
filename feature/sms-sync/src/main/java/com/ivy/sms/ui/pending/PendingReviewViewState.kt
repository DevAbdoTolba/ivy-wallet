package com.ivy.sms.ui.pending

import androidx.compose.runtime.Immutable
import com.ivy.data.model.AccountId
import com.ivy.sms.domain.model.PendingReviewItemId
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.ui.templates.WalletOption
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class PendingItemRowViewState(
    val id: String,
    val itemId: PendingReviewItemId,
    val templateId: SmsTemplateId,
    val senderId: String,
    val body: String,
    val templatePattern: String,
    val wildcardRolesByPosition: Map<Int, WildcardRole>,
    val timestamp: Long,
    val reason: String,
    /** True when the item's template is already ACTIVE — the stored
     *  quarantine reason is then stale ("Needs roles assigned" after the
     *  user mapped the roles) and the row renders an honest
     *  "Partially mapped — couldn't align" label instead. */
    val templateActive: Boolean = false,
    val expanded: Boolean = false,
    /** TRANSFER_NEEDS_DESTINATION row: renders the destination picker block
     *  (From / To / remember / Create transfer) instead of relying on Map. */
    val needsDestination: Boolean = false,
    /** Source wallet resolved from the sender link (the fixed "From"). */
    val fromAccountId: AccountId? = null,
    val fromWalletName: String? = null,
    /** The user's in-progress destination pick for this row (not persisted). */
    val chosenDestinationId: AccountId? = null,
    val chosenDestinationName: String? = null,
    /** "Remember for this template" checkbox state. */
    val rememberDestination: Boolean = false,
    /** True while this row's Create-transfer resolve is in flight. */
    val resolving: Boolean = false,
)

@Immutable
data class PendingReviewViewState(
    val items: ImmutableList<PendingItemRowViewState> = persistentListOf(),
    /** Cumulative messages reviewed across all sessions — drives the "you've
     *  done X / Y" encouragement counter so the user keeps a sense of progress
     *  even after closing and reopening the screen. */
    val reviewedTotal: Int = 0,
    val templatesMappedTotal: Int = 0,
    /** Lifetime count of pending items ever discovered. Drives the hero's
     *  denominator so it never shrinks when items leave the queue via
     *  blacklist/clear (which don't increment [reviewedTotal]). */
    val discoveredTotal: Int = 0,
    /** True when this view is scoped to a single wallet (the per-wallet
     *  Review button); false on the global "Review all" entry. Lets the
     *  screen tweak its toolbar title and hero copy to make scope explicit. */
    val scopedToWallet: Boolean = false,
    /** Live progress while an inbox scan runs — surfaces the first sync
     *  right where its results land. Null when idle. */
    val scanProgress: com.ivy.sms.domain.model.ScanProgress? = null,
    /** Template whose "Ignore" is buffered awaiting its undo window. Its
     *  items are hidden from [items] but nothing is persisted yet. */
    val pendingIgnoreTemplateId: SmsTemplateId? = null,
    /** How many queued messages the buffered ignore is hiding. */
    val pendingIgnoreHiddenCount: Int = 0,
    /** All wallets, for the needs-destination rows' "To" picker. */
    val walletOptions: ImmutableList<WalletOption> = persistentListOf(),
    val error: String? = null,
)

sealed interface PendingReviewEvent {
    data class ToggleExpand(val id: String) : PendingReviewEvent
    data class MapTemplate(val templateId: SmsTemplateId) : PendingReviewEvent
    data class Dismiss(val itemId: PendingReviewItemId) : PendingReviewEvent
    /** Same effect as Blacklist — UI surface labels it "Ignore template forever".
     *  Buffered for [IGNORE_UNDO_WINDOW_MILLIS] before committing. */
    data class IgnoreForever(val templateId: SmsTemplateId) : PendingReviewEvent
    /** Cancels a buffered [IgnoreForever] within its undo window. */
    data object UndoIgnore : PendingReviewEvent

    /** Destination pick on a needs-destination row (null clears the pick). */
    data class ChooseDestination(val id: String, val destinationId: AccountId?) : PendingReviewEvent

    /** Toggles "Remember for this template" on a needs-destination row. */
    data class ToggleRememberDestination(val id: String) : PendingReviewEvent

    /** Commits a needs-destination row: creates the transfer with the picked
     *  destination (and persists it on the template when remember is on). */
    data class CreateTransfer(val id: String, val itemId: PendingReviewItemId) : PendingReviewEvent
}
