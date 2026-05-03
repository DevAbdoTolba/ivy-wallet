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
    val error: String? = null,
)

sealed interface PendingReviewEvent {
    data class ToggleExpand(val id: String) : PendingReviewEvent
    data class MapTemplate(val templateId: SmsTemplateId) : PendingReviewEvent
    data class Dismiss(val itemId: PendingReviewItemId) : PendingReviewEvent
    /** Same effect as Blacklist — UI surface labels it "Ignore template forever". */
    data class IgnoreForever(val templateId: SmsTemplateId) : PendingReviewEvent
}
