package com.ivy.sms.ui.templates

import androidx.compose.runtime.Immutable
import com.ivy.sms.domain.model.ScanProgress
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class TemplateRowViewState(
    val id: SmsTemplateId,
    val preview: String,
    val state: TemplateState,
    val matchCount: Int,
)

@Immutable
data class TemplateListViewState(
    val templates: ImmutableList<TemplateRowViewState> = persistentListOf(),
    val scanProgress: ScanProgress? = null,
    val pendingReviewCount: Int = 0,
    val error: String? = null,
)

sealed interface TemplateListEvent {
    data class TemplateClicked(val id: SmsTemplateId) : TemplateListEvent
    data object ScanFurtherBack : TemplateListEvent
    data object OpenPendingReview : TemplateListEvent
    data class ToggleBlacklist(val id: SmsTemplateId) : TemplateListEvent
}
