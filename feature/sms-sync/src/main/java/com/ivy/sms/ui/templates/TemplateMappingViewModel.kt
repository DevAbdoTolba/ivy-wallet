package com.ivy.sms.ui.templates

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardMapping
import com.ivy.sms.domain.usecase.MapTemplateUseCase
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import javax.inject.Inject

@Stable
@HiltViewModel
class TemplateMappingViewModel @Inject constructor(
    private val templateRepo: SmsTemplateRepository,
    private val mapTemplate: MapTemplateUseCase,
) : ComposeViewModel<TemplateMappingViewState, TemplateMappingEvent>() {

    private var state by mutableStateOf(TemplateMappingViewState())
    private val pendingMappings = mutableMapOf<WildcardId, WildcardMapping>()

    @Composable
    override fun uiState(): TemplateMappingViewState = state

    fun load(templateId: SmsTemplateId) {
        viewModelScope.launch {
            val template = templateRepo.findById(templateId).getOrNull() ?: return@launch
            applyTemplate(template)
        }
    }

    private fun applyTemplate(template: SmsTemplate) {
        pendingMappings.clear()
        template.wildcardSlots.forEach { pendingMappings[it.id] = it.mapping }
        state = state.copy(
            templateId = template.id,
            pattern = template.pattern,
            wildcards = template.wildcardSlots.map {
                WildcardChip(it.id, it.positionInPattern, it.mapping)
            }.toImmutableList(),
            classification = template.classification,
            activeWildcard = null,
            error = null,
        )
    }

    override fun onEvent(event: TemplateMappingEvent) {
        when (event) {
            is TemplateMappingEvent.WildcardTapped -> {
                state = state.copy(activeWildcard = event.id)
            }
            is TemplateMappingEvent.WildcardMappingChosen -> {
                pendingMappings[event.id] = event.mapping
                state = state.copy(
                    wildcards = state.wildcards.map {
                        if (it.id == event.id) it.copy(mapping = event.mapping) else it
                    }.toImmutableList(),
                    activeWildcard = null,
                )
            }
            TemplateMappingEvent.DismissBottomSheet -> {
                state = state.copy(activeWildcard = null)
            }
            is TemplateMappingEvent.ClassificationChosen -> {
                state = state.copy(classification = event.classification)
            }
            TemplateMappingEvent.Save -> save()
        }
    }

    private fun save() {
        val templateId = state.templateId ?: return
        val classification = state.classification ?: run {
            state = state.copy(error = "Pick a classification")
            return
        }
        val hasAmount = state.wildcards.any { it.mapping == WildcardMapping.Amount }
        if (!hasAmount) {
            state = state.copy(error = "Map at least one wildcard to Amount")
            return
        }

        state = state.copy(saving = true, error = null)
        viewModelScope.launch {
            mapTemplate(templateId, pendingMappings.toMap(), classification).fold(
                { state = state.copy(saving = false, error = it) },
                { state = state.copy(saving = false, convertedFromQueue = it.convertedFromQueue) },
            )
        }
    }
}
