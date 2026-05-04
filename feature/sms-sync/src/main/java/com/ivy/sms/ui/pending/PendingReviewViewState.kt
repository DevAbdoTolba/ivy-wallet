package com.ivy.sms.ui.pending

import androidx.compose.runtime.Immutable
import com.ivy.sms.domain.model.PendingReviewItemId
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.WildcardRole
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
    val expanded: Boolean = false,
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
    val error: String? = null,
)

sealed interface PendingReviewEvent {
    data class ToggleExpand(val id: String) : PendingReviewEvent
    data class MapTemplate(val templateId: SmsTemplateId) : PendingReviewEvent
    data class Dismiss(val itemId: PendingReviewItemId) : PendingReviewEvent
    /** Same effect as Blacklist — UI surface labels it "Ignore template forever". */
    data class IgnoreForever(val templateId: SmsTemplateId) : PendingReviewEvent
}
