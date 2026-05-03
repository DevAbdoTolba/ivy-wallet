package com.ivy.sms.ui.templates

import androidx.compose.runtime.Immutable
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

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
    val activeWildcard: WildcardId? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val convertedFromQueue: Int? = null,
)

sealed interface TemplateMappingEvent {
    data class WildcardTapped(val id: WildcardId) : TemplateMappingEvent
    data class WildcardRoleChosen(
        val id: WildcardId,
        val role: WildcardRole,
    ) : TemplateMappingEvent
    data object DismissBottomSheet : TemplateMappingEvent
    data class NameChanged(val value: String) : TemplateMappingEvent
    data object Save : TemplateMappingEvent
}
