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
import kotlinx.collections.immutable.toImmutableMap
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

    init {
        // Surface MapTemplateUseCase's reprocess progress into the screen
        // state so the user gets live x/y feedback while the queue drains.
        viewModelScope.launch {
            mapTemplate.progress.collect { p ->
                state = state.copy(
                    reprocess = p?.let {
                        ReprocessProgress(
                            processed = it.processed,
                            total = it.total,
                            converted = it.converted,
                        )
                    },
                )
            }
        }
    }

    /**
     * Cached copy of the loaded template so we can rebuild the wildcard chip
     * list whenever pendingRoles changes — without this, a user pick that
     * landed before applyTemplate ran would be silently dropped (the previous
     * version's applyRolePick walked an empty state.wildcards list).
     */
    private var loadedTemplate: SmsTemplate? = null

    private fun rebuildWildcards(): kotlinx.collections.immutable.ImmutableList<WildcardChip> {
        val template = loadedTemplate ?: return state.wildcards
        return template.wildcardSlots.map { slot ->
            WildcardChip(
                id = slot.id,
                positionInPattern = slot.positionInPattern,
                exampleValue = slot.exampleValue,
                role = pendingRoles[slot.id] ?: slot.role,
            )
        }.toImmutableList()
    }

    @Composable
    override fun uiState(): TemplateMappingViewState = state

    fun load(templateId: SmsTemplateId) {
        timber.log.Timber.d("TemplateMapping load(id=${templateId.value})")
        Thread {
            try {
                timber.log.Timber.d("TemplateMapping load(): worker thread running")
                kotlinx.coroutines.runBlocking {
                    val result = templateRepo.findById(templateId)
                    timber.log.Timber.d("TemplateMapping load(): findById -> $result")
                    val template = result.getOrNull()
                    if (template == null) {
                        timber.log.Timber.w("TemplateMapping load(): template not found in DB")
                        state = state.copy(
                            templateId = templateId,
                            error = "Template not found. Re-run a Sync from the wallet.",
                        )
                        return@runBlocking
                    }
                    timber.log.Timber.d("TemplateMapping load(): applying template pattern='${template.pattern}' bodyLen=${template.exampleBody.length}")
                    applyTemplate(template)
                }
            } catch (t: Throwable) {
                timber.log.Timber.e(t, "TemplateMapping load() crashed")
                state = state.copy(error = "load crashed: ${t.message}")
            }
        }.start()
    }

    /** Direct-set hook used by the screen as a safety net when the VM-side
     *  load gets shadowed by a Compose / lifecycle race. Idempotent: only
     *  applies if the current state hasn't already been populated. */
    fun seedFromScreen(template: SmsTemplate) {
        if (state.templateId == template.id && state.pattern.isNotBlank()) return
        timber.log.Timber.d("TemplateMapping seedFromScreen() applying ${template.id.value}")
        applyTemplate(template)
    }

    private fun applyTemplate(template: SmsTemplate) {
        loadedTemplate = template
        // Preserve any roles the user already picked before applyTemplate ran
        // (race: produceState in screen + raw-thread VM load both call this).
        // Only seed defaults for slots not in pendingRoles yet.
        for (slot in template.wildcardSlots) {
            if (slot.id !in pendingRoles) pendingRoles[slot.id] = slot.role
        }
        state = state.copy(
            templateId = template.id,
            pattern = template.pattern,
            exampleBody = template.exampleBody,
            name = template.name.orEmpty(),
            wildcards = rebuildWildcards(),
            rolesByWildcardId = pendingRoles.toImmutableMap(),
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
                // Write through pendingRoles first; rebuildWildcards uses it as
                // the source of truth, so the user's pick survives even when
                // state.wildcards happens to be empty (VM-load race). Mirror
                // the same map into state.rolesByWildcardId so the screen can
                // render the chip's role-color and the save button can
                // enable WITHOUT depending on the (sometimes stale) wildcards
                // list.
                pendingRoles[event.id] = event.role
                applyUniquenessRulesToPending(event.id, event.role)
                state = state.copy(
                    wildcards = rebuildWildcards(),
                    rolesByWildcardId = pendingRoles.toImmutableMap(),
                    activeWildcard = null,
                )
            }
            TemplateMappingEvent.DismissBottomSheet -> {
                state = state.copy(activeWildcard = null)
            }
            is TemplateMappingEvent.NameChanged -> {
                state = state.copy(name = event.value)
            }
            is TemplateMappingEvent.Save -> save(explicit = event.explicitTemplateId)
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

    /**
     * Mirror of applyRolePick's uniqueness rules but applied directly to the
     * pendingRoles map (the new source of truth). Picking an amount role on
     * one slot clears any other slot that already had an amount role; same
     * for date-family and the unique single-role slots.
     */
    private fun applyUniquenessRulesToPending(chosenId: WildcardId, chosenRole: WildcardRole) {
        val others = pendingRoles.keys.filter { it != chosenId }
        for (id in others) {
            val role = pendingRoles[id] ?: continue
            val shouldClear = when {
                chosenRole.isAmountRole() && role.isAmountRole() -> true
                chosenRole.isDateRole() && role.isDateRole() -> true
                isUniqueNonAmount(chosenRole) && role == chosenRole -> true
                else -> false
            }
            if (shouldClear) pendingRoles[id] = WildcardRole.Unmapped
        }
    }

    private fun isUniqueNonAmount(role: WildcardRole): Boolean = when (role) {
        WildcardRole.CurrentTotal, WildcardRole.TransactionFee,
        WildcardRole.DateFull, WildcardRole.DateOnly, WildcardRole.TimeOnly -> true
        else -> false
    }

    private fun save(explicit: SmsTemplateId? = null) {
        // Prefer the screen-supplied templateId so we proceed even when the
        // VM's own field is null (screen↔VM seed race). The user hit
        // "clicking Save does nothing" exactly because state.templateId was
        // null and the previous code silently `return`'d.
        val templateId = explicit ?: state.templateId
        if (templateId == null) {
            timber.log.Timber.w("TemplateMapping save(): no templateId available")
            state = state.copy(error = "Reload the screen — template handle was lost")
            return
        }
        timber.log.Timber.d("TemplateMapping save(templateId=${templateId.value})")
        val hasAmount = pendingRoles.values.any { it.isAmountRole() }
        if (!hasAmount) {
            state = state.copy(error = "Pick which segment is the Income, Expense, or Transfer amount")
            return
        }

        state = state.copy(saving = true, error = null)
        val nameToSave = state.name.trim().ifBlank { null }
        // viewModelScope.launch — same coroutine pattern PeriodPickerViewModel
        // uses, which is proven to run on this user's device. The previous
        // raw `Thread { runBlocking { ... } }.start()` was the same shape
        // that silently failed in WalletSmsConfig.syncNow on the same device.
        viewModelScope.launch {
            try {
                val result = mapTemplate(templateId, pendingRoles.toMap(), name = nameToSave)
                timber.log.Timber.d("TemplateMapping save(): mapTemplate -> $result")
                result.fold(
                    { state = state.copy(saving = false, error = it) },
                    { state = state.copy(saving = false, convertedFromQueue = it.convertedFromQueue) },
                )
            } catch (t: Throwable) {
                timber.log.Timber.e(t, "TemplateMapping save() crashed")
                state = state.copy(saving = false, error = "save crashed: ${t.message}")
            }
        }
    }
}
