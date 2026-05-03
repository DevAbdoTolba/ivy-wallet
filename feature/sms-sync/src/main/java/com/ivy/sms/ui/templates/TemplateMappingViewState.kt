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
}
