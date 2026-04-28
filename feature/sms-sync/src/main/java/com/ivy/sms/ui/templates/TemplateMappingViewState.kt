package com.ivy.sms.ui.templates

import androidx.compose.runtime.Immutable
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TransactionClassification
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardMapping
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class WildcardChip(
    val id: WildcardId,
    val positionInPattern: Int,
    val mapping: WildcardMapping,
)

@Immutable
data class TemplateMappingViewState(
    val templateId: SmsTemplateId? = null,
    val pattern: String = "",
    val wildcards: ImmutableList<WildcardChip> = persistentListOf(),
    val classification: TransactionClassification? = null,
    val activeWildcard: WildcardId? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val convertedFromQueue: Int? = null,
)

sealed interface TemplateMappingEvent {
    data class WildcardTapped(val id: WildcardId) : TemplateMappingEvent
    data class WildcardMappingChosen(
        val id: WildcardId,
        val mapping: WildcardMapping,
    ) : TemplateMappingEvent
    data object DismissBottomSheet : TemplateMappingEvent
    data class ClassificationChosen(val classification: TransactionClassification) : TemplateMappingEvent
    data object Save : TemplateMappingEvent
}
