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
    /**
     * Override for the row-shown count. Drain's `matchCount` only counts
     * messages it actually clustered into the template during a scan, but
     * the user expects the count to match what they see when they expand —
     * which uses Jaccard similarity over the inbox and almost always finds
     * additional messages Drain didn't include. Once findMatching has run
     * for a template, its accurate count lands here and the row UI uses it.
     */
    val displayCountByTemplate: Map<SmsTemplateId, Int> = emptyMap(),
    /** Template the user tapped "Show messages" on — drives the modal popup. */
    val matchingModalTemplate: SmsTemplateId? = null,
    /** How many messages of [matchingModalTemplate] are currently visible in
     *  the modal. Bumped by 10 each time the user taps "Load 10 more". */
    val matchingModalLimit: Int = MATCHING_MODAL_PAGE_SIZE,
    val error: String? = null,
)

const val MATCHING_MODAL_PAGE_SIZE = 10

sealed interface TemplateListEvent {
    data class TemplateClicked(val id: SmsTemplateId) : TemplateListEvent

    /** Extend the scan window back to [lowerBoundEpochMillis] (0 = all time)
     *  and rescan the gap. */
    data class ScanFurtherBack(val lowerBoundEpochMillis: Long) : TemplateListEvent
    data object OpenPendingReview : TemplateListEvent
    data class ToggleBlacklist(val id: SmsTemplateId) : TemplateListEvent
    data class ToggleGroup(val group: TemplateGroupKey) : TemplateListEvent
    data class OpenMatching(val id: SmsTemplateId) : TemplateListEvent
    data object CloseMatching : TemplateListEvent
    data object LoadMoreMatching : TemplateListEvent
    /**
     * Emitted by [TemplateRow] the first time it composes for a given id,
     * letting the VM kick off a background findMatching so the row can show
     * the accurate count without the user needing to tap.
     */
    data class PreloadMatching(val id: SmsTemplateId) : TemplateListEvent
}
