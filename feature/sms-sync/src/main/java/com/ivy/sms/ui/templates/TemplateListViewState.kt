package com.ivy.sms.ui.templates

import androidx.compose.runtime.Immutable
import com.ivy.sms.domain.model.ScanProgress
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.model.WildcardRole
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Top-level accordion section the user can expand. Names mirror the user's
 *  mental model of a wallets list: "show me all expenses, all income, etc." */
enum class TemplateGroupKey { Income, Expense, Transfer, Unmapped, Ignored }

@Immutable
data class TemplateRowViewState(
    val id: SmsTemplateId,
    val name: String? = null,
    val pattern: String,
    val exampleBody: String,
    val wildcardRolesByPosition: Map<Int, WildcardRole>,
    val state: TemplateState,
    val matchCount: Int,
    /** Bodies of inbox messages this template's pattern can extract from. Empty
     *  until the user expands this row — fetched lazily via FindMatchingMessages. */
    val matchingMessages: ImmutableList<String> = persistentListOf(),
    val matchingMessagesLoading: Boolean = false,
)

@Immutable
data class TemplateListViewState(
    val templates: ImmutableList<TemplateRowViewState> = persistentListOf(),
    val scanProgress: ScanProgress? = null,
    val pendingReviewCount: Int = 0,
    /** Which group section is currently expanded (only one at a time, like a
     *  classic accordion). Null = all collapsed. */
    val expandedGroup: TemplateGroupKey? = null,
    /** Templates the user has explicitly opened to inspect their matching messages. */
    val expandedTemplateIds: Set<SmsTemplateId> = emptySet(),
    val error: String? = null,
)

sealed interface TemplateListEvent {
    data class TemplateClicked(val id: SmsTemplateId) : TemplateListEvent
    data object ScanFurtherBack : TemplateListEvent
    data object OpenPendingReview : TemplateListEvent
    data class ToggleBlacklist(val id: SmsTemplateId) : TemplateListEvent
    data class ToggleGroup(val group: TemplateGroupKey) : TemplateListEvent
    data class ToggleTemplateExpanded(val id: SmsTemplateId) : TemplateListEvent
}
