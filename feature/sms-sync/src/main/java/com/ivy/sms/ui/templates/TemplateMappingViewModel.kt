package com.ivy.sms.ui.templates

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.domain.model.isAmountRole
import com.ivy.sms.domain.model.isDateRole
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
    private val pendingRoles = mutableMapOf<WildcardId, WildcardRole>()

    @Composable
    override fun uiState(): TemplateMappingViewState = state

    fun load(templateId: SmsTemplateId) {
        // Raw-thread pattern. viewModelScope.launch was silently no-op'ing on
        // the user's device (same Compose/lifecycle quirk that bit
        // SenderPickerViewModel earlier), leaving the screen stuck on the
        // empty default state and the user staring at a blank "Loading…" box.
        Thread {
            try {
                kotlinx.coroutines.runBlocking {
                    val template = templateRepo.findById(templateId).getOrNull()
                        ?: return@runBlocking
                    applyTemplate(template)
                }
            } catch (t: Throwable) {
                timber.log.Timber.e(t, "TemplateMapping load() crashed")
                state = state.copy(error = "load crashed: ${t.message}")
            }
        }.start()
    }

    private fun applyTemplate(template: SmsTemplate) {
        pendingRoles.clear()
        template.wildcardSlots.forEach { pendingRoles[it.id] = it.role }
        state = state.copy(
            templateId = template.id,
            pattern = template.pattern,
            exampleBody = template.exampleBody,
            name = template.name.orEmpty(),
            wildcards = template.wildcardSlots.map {
                WildcardChip(it.id, it.positionInPattern, it.exampleValue, it.role)
            }.toImmutableList(),
            activeWildcard = null,
            error = null,
        )
    }

    override fun onEvent(event: TemplateMappingEvent) {
        when (event) {
            is TemplateMappingEvent.WildcardTapped -> {
                state = state.copy(activeWildcard = event.id)
            }
            is TemplateMappingEvent.WildcardRoleChosen -> {
                val nextRoles = applyRolePick(state.wildcards, event.id, event.role)
                pendingRoles[event.id] = event.role
                state = state.copy(
                    wildcards = nextRoles,
                    activeWildcard = null,
                )
            }
            TemplateMappingEvent.DismissBottomSheet -> {
                state = state.copy(activeWildcard = null)
            }
            is TemplateMappingEvent.NameChanged -> {
                state = state.copy(name = event.value)
            }
            TemplateMappingEvent.Save -> save()
        }
    }

    /**
     * Picking an amount role (Income/Expense/Transfer) on one chip clears any other chip
     * that already holds an amount role — there can only be one per template, and the
     * keypad-style UX is "pick once, replace previous". Same rule for the unique roles
     * (CurrentTotal / TransactionFee / Date).
     */
    private fun applyRolePick(
        current: List<WildcardChip>,
        chosenId: WildcardId,
        chosenRole: WildcardRole,
    ): kotlinx.collections.immutable.ImmutableList<WildcardChip> {
        return current.map { chip ->
            when {
                chip.id == chosenId -> chip.copy(role = chosenRole)
                chosenRole.isAmountRole() && chip.role.isAmountRole() ->
                    chip.copy(role = WildcardRole.Unmapped)
                chosenRole.isDateRole() && chip.role.isDateRole() ->
                    chip.copy(role = WildcardRole.Unmapped)
                isUniqueNonAmount(chosenRole) && chip.role == chosenRole ->
                    chip.copy(role = WildcardRole.Unmapped)
                else -> chip
            }
        }.also {
            // Mirror the cleanup in pendingRoles so save sees the right set.
            for (chip in it) pendingRoles[chip.id] = chip.role
        }.toImmutableList()
    }

    private fun isUniqueNonAmount(role: WildcardRole): Boolean = when (role) {
        WildcardRole.CurrentTotal, WildcardRole.TransactionFee,
        WildcardRole.DateFull, WildcardRole.DateOnly, WildcardRole.TimeOnly -> true
        else -> false
    }

    private fun save() {
        val templateId = state.templateId ?: return
        val hasAmount = state.wildcards.any { it.role.isAmountRole() }
        if (!hasAmount) {
            state = state.copy(error = "Pick which segment is the Income, Expense, or Transfer amount")
            return
        }

        state = state.copy(saving = true, error = null)
        val nameToSave = state.name.trim().ifBlank { null }
        Thread {
            try {
                kotlinx.coroutines.runBlocking {
                    mapTemplate(templateId, pendingRoles.toMap(), name = nameToSave).fold(
                        { state = state.copy(saving = false, error = it) },
                        { state = state.copy(saving = false, convertedFromQueue = it.convertedFromQueue) },
                    )
                }
            } catch (t: Throwable) {
                timber.log.Timber.e(t, "TemplateMapping save() crashed")
                state = state.copy(saving = false, error = "save crashed: ${t.message}")
            }
        }.start()
    }
}
