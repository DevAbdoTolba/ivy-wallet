package com.ivy.sms.ui.templates

import androidx.compose.runtime.Immutable
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf

@Immutable
data class WildcardChip(
    val id: WildcardId,
    val positionInPattern: Int,
    val exampleValue: String,
    val role: WildcardRole,
)

@Immutable
data class TemplateMappingViewState(
    val templateId: SmsTemplateId? = null,
    val pattern: String = "",
    val exampleBody: String = "",
    val name: String = "",
    val wildcards: ImmutableList<WildcardChip> = persistentListOf(),
    /**
     * Single source of truth for the user's role picks, keyed by [WildcardId].
     * The screen merges this with `fetchedTemplate.wildcardSlots` so the
     * visible chip role always reflects the latest pick — even when the VM's
     * `wildcards` list is stale because of a screen / VM-store race that
     * was ate the user's "I picked Expense, save is still grey" report.
     */
    val rolesByWildcardId: ImmutableMap<WildcardId, WildcardRole> = persistentMapOf(),
    val activeWildcard: WildcardId? = null,
    val saving: Boolean = false,
    /**
     * Live progress while reprocess is draining the pending queue. Null
     * before save and after the queue is fully consumed. Wallet-level rule:
     * "always progress bar with numbers".
     */
    val reprocess: ReprocessProgress? = null,
    val error: String? = null,
    val convertedFromQueue: Int? = null,
    /** Pending items the reprocess COULDN'T route — they stay in the queue.
     *  When > 0, the screen shows "Partially mapped" feedback so the user
     *  doesn't see a misleading "Needs roles assigned" badge later. */
    val failedAlignment: Int? = null,
    /** Total pending items the reprocess considered for this template. */
    val totalPending: Int? = null,
)

@Immutable
data class ReprocessProgress(
    val processed: Int,
    val total: Int,
    val converted: Int,
)

sealed interface TemplateMappingEvent {
    data class WildcardTapped(val id: WildcardId) : TemplateMappingEvent
    data class WildcardRoleChosen(
        val id: WildcardId,
        val role: WildcardRole,
    ) : TemplateMappingEvent
    /**
     * User tapped a literal token in the rendered example. The VM speculatively
     * inserts a new wildcard slot at [positionInPattern] (with [token] as the
     * exampleValue and role = Unmapped) and opens the role picker. If the
     * user picks a role, the slot is committed; if they dismiss without
     * picking, [DismissBottomSheet] reverts the speculative insert.
     */
    data class LiteralTapped(
        val positionInPattern: Int,
        val token: String,
    ) : TemplateMappingEvent
    /**
     * User chose "Make this part literal again" inside the role picker. The
     * VM removes the slot and restores the original literal token in the
     * pattern at that position.
     */
    data class WildcardClearedToLiteral(val id: WildcardId) : TemplateMappingEvent
    data object DismissBottomSheet : TemplateMappingEvent
    data class NameChanged(val value: String) : TemplateMappingEvent
    /**
     * The screen passes [explicitTemplateId] from its `produceState`-fetched
     * template so save() can proceed even when the VM's own templateId
     * field is null because of a screen↔VM seed race. The user reported
     * "Save lights up but clicking does nothing" because save() was
     * silently `return`'ing on `state.templateId ?: return`.
     */
    data class Save(val explicitTemplateId: SmsTemplateId? = null) : TemplateMappingEvent
    /**
     * "Ignore this template forever" button — flips the template to
     * BLACKLISTED so future SMS that align to this pattern get suppressed
     * instead of routing to pending review. The screen also fires its own
     * onIgnoreForever lambda for nav.back so the user immediately leaves
     * the screen; the VM does the DB work asynchronously.
     */
    data class IgnoreForever(val explicitTemplateId: SmsTemplateId? = null) : TemplateMappingEvent
}
