package com.ivy.sms.ui.templates

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.data.WILDCARD_TOKEN
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.domain.model.WildcardSlot
import com.ivy.sms.domain.model.isAmountRole
import com.ivy.sms.domain.model.isDateRole
import com.ivy.sms.domain.usecase.BlacklistTemplateUseCase
import com.ivy.sms.domain.usecase.MapTemplateUseCase
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@Stable
@HiltViewModel
class TemplateMappingViewModel @Inject constructor(
    private val templateRepo: SmsTemplateRepository,
    private val mapTemplate: MapTemplateUseCase,
    private val blacklist: BlacklistTemplateUseCase,
) : ComposeViewModel<TemplateMappingViewState, TemplateMappingEvent>() {

    private var state by mutableStateOf(TemplateMappingViewState())
    private val pendingRoles = mutableMapOf<WildcardId, WildcardRole>()

    /**
     * Live editable copy of pattern + slots. Seeded from the template on
     * first load and mutated in-place by tap-to-toggle handlers
     * ([LiteralTapped] / [WildcardClearedToLiteral]). Save passes both to
     * MapTemplateUseCase as a wholesale patch — no inferring "did the
     * pattern change" from role diffs.
     */
    private var pendingPatternTokens: List<String> = emptyList()
    private var pendingSlots: List<WildcardSlot> = emptyList()

    /**
     * When the user taps a literal we speculatively insert a slot at that
     * position (role = Unmapped) and open the picker. If they dismiss
     * without choosing a role we revert the insert. Tracked here so
     * [TemplateMappingEvent.DismissBottomSheet] knows what to undo.
     */
    private var lastLiteralTappedSlotId: WildcardId? = null

    /**
     * Wallet the user opened this mapping screen from. When non-null, the
     * save reprocess filters pending items to those resolving (via
     * SenderAccountLink) to this wallet — prevents cross-wallet routing the
     * user reported as a leak.
     */
    private var walletScope: com.ivy.data.model.AccountId? = null

    fun setWalletScope(id: com.ivy.data.model.AccountId?) {
        walletScope = id
    }

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
     * Cached copy of the loaded template — kept so a late applyTemplate
     * (race: VM load + screen produceState seed) doesn't blow away pending
     * edits the user already made.
     */
    private var loadedTemplate: SmsTemplate? = null

    /**
     * Build the wildcard chip list straight from pendingSlots, with roles
     * overlaid from pendingRoles so the chip's colour reflects the user's
     * latest pick even before the slot list rebuild has run.
     */
    private fun rebuildWildcards(): kotlinx.collections.immutable.ImmutableList<WildcardChip> {
        return pendingSlots.map { slot ->
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
                        val msg = "Template not found. Re-run a Sync from the wallet."
                        timber.log.Timber.tag("SmsTrace").w("UI → error banner: '%s'", msg)
                        state = state.copy(templateId = templateId, error = msg)
                        return@runBlocking
                    }
                    timber.log.Timber.d("TemplateMapping load(): applying template pattern='${template.pattern}' bodyLen=${template.exampleBody.length}")
                    applyTemplate(template)
                }
            } catch (t: Throwable) {
                timber.log.Timber.e(t, "TemplateMapping load() crashed")
                timber.log.Timber.tag("SmsTrace").e(
                    t, "UI → error banner: 'load crashed: %s'", t.message,
                )
                state = state.copy(error = "load crashed: ${t.message}")
            }
        }.start()
    }

    /** Direct-set hook used by the screen as a safety net when the VM-side
     *  load gets shadowed by a Compose / lifecycle race. The early-return now
     *  also requires `loadedTemplate` to be non-null — without that check, a
     *  VM re-creation (this app's custom nav clears ViewModelStore mid-screen)
     *  leaves loadedTemplate null but state.pattern populated by Compose
     *  recomposing with the previous instance's state, so the apply is
     *  skipped and subsequent role picks land on a half-empty VM. */
    fun seedFromScreen(template: SmsTemplate) {
        if (loadedTemplate?.id == template.id && state.pattern.isNotBlank()) return
        timber.log.Timber.tag("SmsTrace").d(
            "SEED_FROM_SCREEN → tpl=%s slots=%d",
            template.id.value, template.wildcardSlots.size,
        )
        applyTemplate(template)
    }

    private fun applyTemplate(template: SmsTemplate) {
        timber.log.Timber.tag("SmsTrace").d(
            "APPLY_TEMPLATE → tpl=%s slots=%d patternLen=%d",
            template.id.value, template.wildcardSlots.size, template.pattern.length,
        )
        loadedTemplate = template
        // Seed pendingPatternTokens / pendingSlots only the first time.
        // Both raceable seed paths (VM load + screen produceState) call this,
        // so a second pass would otherwise wipe edits the user already made.
        if (pendingPatternTokens.isEmpty()) {
            pendingPatternTokens = template.pattern
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
        }
        if (pendingSlots.isEmpty()) {
            pendingSlots = template.wildcardSlots.toList()
        }
        for (slot in template.wildcardSlots) {
            if (slot.id !in pendingRoles) pendingRoles[slot.id] = slot.role
        }
        state = state.copy(
            templateId = template.id,
            pattern = pendingPatternTokens.joinToString(" "),
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
                timber.log.Timber.tag("SmsTrace").d(
                    "ROLE_PICK → slot=%s role=%s pendingRolesBefore=%d slotsBefore=%d",
                    event.id.value.toString().take(8),
                    event.role,
                    pendingRoles.size,
                    pendingSlots.size,
                )
                // Race-safety: if the screen rendered chips from fetchedTemplate
                // before the VM finished seeding pendingSlots/Pattern, seed now
                // from the cached loadedTemplate so the slot mirror lands on
                // something — otherwise the .map below operates on an empty
                // list and the user's pick never reaches save().
                ensureSeededFromTemplate()
                // Write through pendingRoles AND pendingSlots so save() and
                // rebuildWildcards see the same role. The slot mirror also
                // means the patched-slots payload sent to MapTemplateUseCase
                // already has the right role on it without us re-overlaying.
                pendingRoles[event.id] = event.role
                applyUniquenessRulesToPending(event.id, event.role)
                pendingSlots = pendingSlots.map { slot ->
                    if (slot.id == event.id) slot.copy(role = event.role) else slot
                }
                // The user committed a role; if this slot was the speculative
                // one created by LiteralTapped, it's no longer eligible for
                // dismiss-revert.
                lastLiteralTappedSlotId = null
                state = state.copy(
                    wildcards = rebuildWildcards(),
                    rolesByWildcardId = pendingRoles.toImmutableMap(),
                    activeWildcard = null,
                )
            }
            is TemplateMappingEvent.LiteralTapped -> handleLiteralTapped(event)
            is TemplateMappingEvent.WildcardClearedToLiteral -> {
                revertSlotToLiteral(event.id)
                lastLiteralTappedSlotId = null
                state = state.copy(activeWildcard = null)
            }
            TemplateMappingEvent.DismissBottomSheet -> {
                // If the picker was opened via LiteralTapped and the user
                // bailed without picking a role, undo the speculative slot
                // insert so the pattern reverts to its prior literal form.
                val tapped = lastLiteralTappedSlotId
                if (tapped != null) {
                    val role = pendingRoles[tapped]
                    if (role == null || role == WildcardRole.Unmapped) {
                        revertSlotToLiteral(tapped)
                    }
                }
                lastLiteralTappedSlotId = null
                state = state.copy(activeWildcard = null)
            }
            is TemplateMappingEvent.NameChanged -> {
                state = state.copy(name = event.value)
            }
            is TemplateMappingEvent.Save -> save(explicit = event.explicitTemplateId)
            is TemplateMappingEvent.IgnoreForever -> ignoreForever(event.explicitTemplateId)
        }
    }

    private fun ignoreForever(explicit: SmsTemplateId?) {
        val tplId = explicit ?: state.templateId ?: loadedTemplate?.id
        if (tplId == null) {
            timber.log.Timber.tag("SmsTrace").w(
                "IGNORE_FOREVER → no templateId available; skipping",
            )
            return
        }
        timber.log.Timber.tag("SmsTrace").i(
            "IGNORE_FOREVER → tpl=%s", tplId.value,
        )
        viewModelScope.launch {
            blacklist.enable(tplId).onLeft { err ->
                timber.log.Timber.tag("SmsTrace").w(
                    "IGNORE_FOREVER ✗ %s | UI → error banner: '%s'", err, err,
                )
                state = state.copy(error = err)
            }
        }
    }

    private fun handleLiteralTapped(event: TemplateMappingEvent.LiteralTapped) {
        // Race-safety: literal tap can fire before applyTemplate seeded
        // pendingPatternTokens. Seed from loadedTemplate so the index check
        // below has something to compare against.
        ensureSeededFromTemplate()
        val pos = event.positionInPattern
        if (pos !in pendingPatternTokens.indices) return
        // Don't double-convert if the position is somehow already a wildcard.
        if (pendingPatternTokens[pos] == WILDCARD_TOKEN) return
        val newSlotId = WildcardId(UUID.randomUUID())
        val newSlot = WildcardSlot(
            id = newSlotId,
            positionInPattern = pos,
            contextSnippet = "",
            exampleValue = event.token,
            role = WildcardRole.Unmapped,
        )
        pendingSlots = (pendingSlots + newSlot).sortedBy { it.positionInPattern }
        pendingPatternTokens = pendingPatternTokens.toMutableList().also {
            it[pos] = WILDCARD_TOKEN
        }
        pendingRoles[newSlotId] = WildcardRole.Unmapped
        lastLiteralTappedSlotId = newSlotId
        state = state.copy(
            pattern = pendingPatternTokens.joinToString(" "),
            wildcards = rebuildWildcards(),
            rolesByWildcardId = pendingRoles.toImmutableMap(),
            activeWildcard = newSlotId,
        )
    }

    /**
     * Force pendingPatternTokens / pendingSlots to be populated from the
     * cached loadedTemplate when they're still empty — applyTemplate may not
     * have run yet on the VM's worker thread when the user already started
     * picking roles via chips rendered from the screen's fetchedTemplate
     * fallback. Idempotent: re-running with already-seeded state is a no-op.
     */
    private fun ensureSeededFromTemplate() {
        val tpl = loadedTemplate ?: return
        if (pendingPatternTokens.isEmpty()) {
            pendingPatternTokens = tpl.pattern
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
        }
        if (pendingSlots.isEmpty()) {
            pendingSlots = tpl.wildcardSlots.toList()
        }
    }

    private fun revertSlotToLiteral(slotId: WildcardId) {
        val slot = pendingSlots.firstOrNull { it.id == slotId } ?: return
        pendingSlots = pendingSlots.filter { it.id != slotId }
        if (slot.positionInPattern in pendingPatternTokens.indices) {
            pendingPatternTokens = pendingPatternTokens.toMutableList().also {
                it[slot.positionInPattern] = slot.exampleValue
            }
        }
        pendingRoles.remove(slotId)
        state = state.copy(
            pattern = pendingPatternTokens.joinToString(" "),
            wildcards = rebuildWildcards(),
            rolesByWildcardId = pendingRoles.toImmutableMap(),
        )
    }

    /**
     * Mirror of the keypad-style "pick once, replace previous" rule applied
     * to BOTH pendingRoles and pendingSlots. Picking an amount role on one
     * slot clears any other amount-role slot; same for the unique date and
     * non-amount roles. DateOnly + TimeOnly may coexist (per dateRolesConflict).
     */
    private fun applyUniquenessRulesToPending(chosenId: WildcardId, chosenRole: WildcardRole) {
        val others = pendingRoles.keys.filter { it != chosenId }
        for (id in others) {
            val role = pendingRoles[id] ?: continue
            val shouldClear = when {
                chosenRole.isAmountRole() && role.isAmountRole() -> true
                dateRolesConflict(chosenRole, role) -> true
                isUniqueNonAmount(chosenRole) && role == chosenRole -> true
                else -> false
            }
            if (shouldClear) {
                pendingRoles[id] = WildcardRole.Unmapped
                pendingSlots = pendingSlots.map { slot ->
                    if (slot.id == id) slot.copy(role = WildcardRole.Unmapped) else slot
                }
            }
        }
    }

    /**
     * Date-role conflict matrix:
     *   - DateFull is mutually exclusive with DateOnly AND TimeOnly (it
     *     already encodes both, so combining wouldn't make sense).
     *   - DateOnly + TimeOnly may COEXIST — that's the SMS-with-separate-
     *     date-and-time-tokens case the user reported.
     *   - DateOnly is unique among DateOnly slots; same for TimeOnly.
     */
    private fun dateRolesConflict(chosen: WildcardRole, other: WildcardRole): Boolean = when {
        chosen == WildcardRole.DateFull && other.isDateRole() -> true
        other == WildcardRole.DateFull && chosen.isDateRole() -> true
        chosen == WildcardRole.DateOnly && other == WildcardRole.DateOnly -> true
        chosen == WildcardRole.TimeOnly && other == WildcardRole.TimeOnly -> true
        else -> false
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
            val msg = "Reload the screen — template handle was lost"
            timber.log.Timber.tag("SmsTrace").w("UI → error banner: '%s'", msg)
            state = state.copy(error = msg)
            return
        }
        // Race fix: the screen renders chips from `fetchedTemplate.wildcardSlots`
        // before applyTemplate has populated pendingSlots / pendingPatternTokens.
        // If the user picks a role on one of those chips and saves while the
        // VM seed is still in flight, `pendingSlots` is empty and validation
        // wrongly reports "no amount role". Falling back to loadedTemplate
        // here gives us the same slot ids the user just tapped against — and
        // pendingRoles still holds their pick because slot ids line up.
        val effectiveSlots: List<WildcardSlot> = pendingSlots.ifEmpty {
            loadedTemplate?.wildcardSlots.orEmpty()
        }
        val effectivePatternTokens: List<String> = pendingPatternTokens.ifEmpty {
            loadedTemplate?.pattern
                ?.split(Regex("\\s+"))
                ?.filter { it.isNotBlank() }
                .orEmpty()
        }
        val patchedPattern = effectivePatternTokens.joinToString(" ")
        val patchedSlotsList = effectiveSlots.toList()
        // Dumps every slot's effective role + the pendingRoles map so a logcat
        // capture during the save flow tells us exactly why validation fired
        // — which slot has which role, and whether the user's pick made it
        // into pendingRoles at all. Also reports loadedTemplate state so we
        // can tell when the fallback path was taken vs the normal path.
        timber.log.Timber.tag("SmsTrace").d(
            "MAP_SAVE → tpl=%s loadedTpl=%s pattern='%s' slots=[%s] pendingRoles={%s}",
            templateId.value,
            loadedTemplate?.id?.value?.toString() ?: "null",
            patchedPattern,
            patchedSlotsList.joinToString { slot ->
                val effective = pendingRoles[slot.id] ?: slot.role
                "${slot.id.value.toString().take(8)}@${slot.positionInPattern}=$effective"
            },
            pendingRoles.entries.joinToString { e ->
                "${e.key.value.toString().take(8)}=${e.value}"
            },
        )
        val hasAmount = patchedSlotsList.any {
            (pendingRoles[it.id] ?: it.role).isAmountRole()
        }
        timber.log.Timber.tag("SmsTrace").d(
            "MAP_SAVE → hasAmount=%b → %s",
            hasAmount,
            if (hasAmount) "proceeding" else "BLOCKED by 'pick amount' validation",
        )
        if (!hasAmount) {
            val msg = "Pick which segment is the Income, Expense, or Transfer amount"
            timber.log.Timber.tag("SmsTrace").w("UI → error banner: '%s'", msg)
            state = state.copy(error = msg)
            return
        }

        timber.log.Timber.tag("SmsTrace").i("UI → saving… (button shows 'Saving…')")
        state = state.copy(saving = true, error = null)
        val nameToSave = state.name.trim().ifBlank { null }
        viewModelScope.launch {
            try {
                val result = mapTemplate(
                    templateId = templateId,
                    wildcardRoles = pendingRoles.toMap(),
                    name = nameToSave,
                    patchedPattern = patchedPattern,
                    patchedSlots = patchedSlotsList,
                    walletScope = walletScope,
                )
                timber.log.Timber.d("TemplateMapping save(): mapTemplate -> $result")
                result.fold(
                    { err ->
                        timber.log.Timber.tag("SmsTrace").w(
                            "UI → error banner: '%s'", err,
                        )
                        state = state.copy(saving = false, error = err)
                    },
                    { res ->
                        // Log exactly what the user will see post-save: a
                        // clean success toast (auto-nav-back) or the
                        // "Partially mapped" card that keeps them on-screen.
                        if (res.failedAlignment > 0) {
                            timber.log.Timber.tag("SmsTrace").w(
                                "UI → 'Partially mapped' card: %d of %d routed, " +
                                    "%d failed alignment — screen stays open",
                                res.convertedFromQueue,
                                res.totalPending,
                                res.failedAlignment,
                            )
                        } else {
                            timber.log.Timber.tag("SmsTrace").i(
                                "UI → save OK: %d routed, no failures — auto nav back",
                                res.convertedFromQueue,
                            )
                        }
                        state = state.copy(
                            saving = false,
                            convertedFromQueue = res.convertedFromQueue,
                            failedAlignment = res.failedAlignment,
                            totalPending = res.totalPending,
                        )
                    },
                )
            } catch (t: Throwable) {
                timber.log.Timber.e(t, "TemplateMapping save() crashed")
                timber.log.Timber.tag("SmsTrace").e(
                    t, "UI → error banner: 'save crashed: %s'", t.message,
                )
                state = state.copy(saving = false, error = "save crashed: ${t.message}")
            }
        }
    }
}
