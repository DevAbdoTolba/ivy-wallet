package com.ivy.sms.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.data.model.AccountId
import com.ivy.data.repository.AccountRepository
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.data.SmsWatermarkPreferences
import com.ivy.sms.data.WILDCARD_TOKEN
import com.ivy.sms.domain.model.QuarantineReason
import com.ivy.sms.domain.model.SmsMessage
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.domain.model.WildcardSlot
import com.ivy.sms.domain.model.isAmountRole
import com.ivy.sms.domain.model.isUnique
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class MapTemplateResult(
    val convertedFromQueue: Int,
    /** Own pending items whose body the saved pattern could NOT align. */
    val failedAlignment: Int = 0,
    /** Own pending items that aligned but whose captured amount didn't parse. */
    val failedAmountParse: Int = 0,
    /** Own pending items whose sender isn't linked to any wallet. */
    val failedSenderNotLinked: Int = 0,
    /** Own pending items skipped for any other reason (currency mismatch,
     *  routing error, …). */
    val failedOther: Int = 0,
    /** Pending items belonging to THIS template — the honest denominator for
     *  the "Partially mapped" card. Sibling templates from the same sender
     *  are NOT counted here; their drain is handled by
     *  PendingReviewViewModel's active-set trigger. */
    val totalOwn: Int = 0,
    /** Save-time dry-run: how many of [totalOwn] the final pattern aligned
     *  (via extractWildcardValues) BEFORE routing. */
    val alignedCount: Int = 0,
) {
    val failedTotal: Int
        get() = failedAlignment + failedAmountParse + failedSenderNotLinked + failedOther
}

/**
 * Live progress emitted while [MapTemplateUseCase] is reprocessing pending
 * review items. Lets the screen render an x/y bar so the user can see
 * what's happening when a freshly-mapped template has lots of queued items
 * to drain — they reported "I save and just sit there with no feedback".
 */
data class MapTemplateProgress(
    val processed: Int,
    val total: Int,
    val converted: Int,
)

/** Same digit test DrainParser.preNormalize applies for descent collapse:
 *  Latin 0-9 plus Arabic-Indic and Eastern Arabic-Indic digit blocks,
 *  checked after trimming trailing punctuation. The two MUST stay in sync —
 *  a token Drain treats as variable must not survive as a save-time literal. */
private val digitChar = Regex("[0-9\\u0660-\\u0669\\u06F0-\\u06F9]")

private val whitespaceRegex = Regex("\\s+")

private fun isDigitBearing(token: String): Boolean {
    val cleaned = token.trimEnd('.', ',', ':', ';', '!', '?')
    return digitChar.containsMatchIn(cleaned)
}

@Singleton
class MapTemplateUseCase @Inject constructor(
    private val templateRepo: SmsTemplateRepository,
    private val pendingRepo: PendingReviewItemRepository,
    private val senderRepo: SenderAccountLinkRepository,
    private val route: RouteSmsUseCase,
    private val prefs: SmsWatermarkPreferences,
    private val accountRepo: AccountRepository,
    private val findMatching: FindMatchingMessagesUseCase,
) {
    private val _progress = MutableStateFlow<MapTemplateProgress?>(null)
    val progress: StateFlow<MapTemplateProgress?> = _progress.asStateFlow()

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    suspend operator fun invoke(
        templateId: SmsTemplateId,
        wildcardRoles: Map<WildcardId, WildcardRole>,
        name: String? = null,
        patchedPattern: String? = null,
        patchedSlots: List<WildcardSlot>? = null,
        /** Wallet to scope reprocess to. When non-null and the sender resolves
         *  (via [SenderAccountLinkRepository]) to a DIFFERENT wallet, the save
         *  is rejected with a WALLET_SCOPE_MISMATCH error instead of silently
         *  succeeding with nothing routed — the user reported the silent
         *  0/0/0 + auto-nav-back as "save did nothing". */
        walletScope: AccountId? = null,
        /** Pattern positions the user EXPLICITLY reverted to literal in this
         *  mapping session ("Make this part literal again"). These are exempt
         *  from save-time digit auto-generalization — stable digit constants
         *  like card suffixes stay literals when the user says so. */
        confirmedLiteralPositions: Set<Int> = emptySet(),
        /** True only when the user pressed "Save anyway" on the zero-alignment
         *  warning. Otherwise a pattern that aligns 0 of its own queued
         *  messages is rejected with ZERO_ALIGNMENT:<n> before persisting. */
        allowZeroAlignment: Boolean = false,
    ): Either<String, MapTemplateResult> {
        val original = templateRepo.findById(templateId).getOrNull()
            ?: return "TEMPLATE_NOT_FOUND".left()

        // Two save paths converge here:
        //   - Roles-only (legacy): keep the original pattern + slots, overlay
        //     wildcardRoles onto the existing slot roles.
        //   - Tap-to-toggle (new): the UI patched the pattern + slot list,
        //     usually because the user converted a literal to a wildcard or
        //     vice versa. We persist the patch wholesale, still overlaying
        //     wildcardRoles so any role pick the modal applied after the slot
        //     was materialised wins.
        val workingPattern: String = patchedPattern ?: original.pattern
        val workingSlots: List<WildcardSlot> = patchedSlots ?: original.wildcardSlots
        val updatedSlots: List<WildcardSlot> = workingSlots.map { slot ->
            slot.copy(role = wildcardRoles[slot.id] ?: slot.role)
        }

        // Validate: exactly one Income/Expense/Transfer wildcard required.
        val amountSlots = updatedSlots.filter { it.role.isAmountRole() }
        when {
            amountSlots.isEmpty() ->
                return "VALIDATION:bind exactly one wildcard to Income, Expense, or Transfer".left()
            amountSlots.size > 1 ->
                return "VALIDATION:only one wildcard may be Income/Expense/Transfer per template".left()
        }

        // Validate: roles flagged unique appear at most once.
        val roleCounts = updatedSlots
            .filter { it.role.isUnique() }
            .groupingBy { it.role }
            .eachCount()
        val duplicateUnique = roleCounts.entries.firstOrNull { it.value > 1 }
        if (duplicateUnique != null) {
            return "VALIDATION:role ${duplicateUnique.key} can only be used once per template".left()
        }

        // Auto-generalize digit-bearing literals. First-sample Drain patterns
        // keep amounts/balances as raw literals ("387.44.", "20.3"), so a
        // saved pattern baked them in and alignment failed for EVERY sibling
        // message — 90 of 127 real-world alignment failures traced to this.
        // Each such token becomes an Unmapped wildcard slot (exampleValue =
        // the token) unless the user explicitly confirmed it as a literal.
        val generalized = autoGeneralizeDigitLiterals(
            patternTokens = workingPattern.split(whitespaceRegex).filter { it.isNotBlank() },
            slots = updatedSlots,
            confirmedLiteralPositions = confirmedLiteralPositions,
            templateId = templateId,
        )

        val updated = original.copy(
            pattern = generalized.pattern,
            wildcardSlots = generalized.slots,
            state = TemplateState.ACTIVE,
            name = name?.trim()?.ifBlank { null } ?: original.name,
        )

        val links = senderRepo.findAll().getOrNull().orEmpty()
        val senderToAccount = links.associate { it.senderId to it.accountId }

        // Wallet-scope mismatch is an ERROR, not an empty success. Routing
        // resolves the target account from the sender link, so a sender linked
        // to wallet X can never produce transactions in walletScope Y — the
        // old code silently filtered everything out, returned 0/0/0, and the
        // screen auto-navigated back as if the save worked.
        if (walletScope != null) {
            val linked = senderToAccount[updated.senderIdHint]
            if (linked != null && linked != walletScope) {
                val linkedName = accountRepo.findById(linked)?.name?.value ?: "another wallet"
                val scopedName = accountRepo.findById(walletScope)?.name?.value ?: "this wallet"
                timber.log.Timber.tag("SmsTrace").w(
                    "MAP ✗ wallet-scope mismatch tpl=%s sender=%s linked='%s' scoped='%s'",
                    templateId.value, updated.senderIdHint, linkedName, scopedName,
                )
                return (
                    "WALLET_SCOPE_MISMATCH:sender '${updated.senderIdHint}' is linked to " +
                        "wallet '$linkedName', but you're mapping from wallet '$scopedName'. " +
                        "Map it from '$linkedName' or relink the sender first."
                    ).left()
            }
        }

        // Save-time dry-run: the final pattern must actually align something
        // before we persist it as ACTIVE. Checked against every queued
        // pending item belonging to this template, and — when the queue is
        // empty — against the template's own exampleBody (a pattern that
        // can't align its OWN sample is broken and would match nothing
        // forever). Both checks share the same "Save anyway" escape.
        val ownPending = pendingRepo.findByTemplateId(templateId).getOrNull().orEmpty()
        val alignedByItem = ownPending.associate { item ->
            item.id to (extractWildcardValues(updated, item.sms) != null)
        }
        val alignedCount = alignedByItem.values.count { it }
        val exampleMessage = SmsMessage(
            dedupKey = "dry-run:${templateId.value}",
            senderId = updated.senderIdHint,
            body = updated.exampleBody,
            timestamp = original.lastSeen,
        )
        val exampleAligned = extractWildcardValues(updated, exampleMessage) != null
        timber.log.Timber.tag("SmsTrace").i(
            "MAP_SAVE dry-run tpl=%s exampleAligned=%b aligned=%d/%d allowZeroAlignment=%b",
            templateId.value, exampleAligned, alignedCount, ownPending.size, allowZeroAlignment,
        )
        // Queue non-empty: the pattern must align at least one queued item.
        // Queue EMPTY (all drained/dismissed): the exampleBody check is the
        // only signal left — without it a hand-patched pattern that can't
        // align its own sample would save ACTIVE silently (0/0 counters,
        // auto-nav-back) and quarantine every future message of the format.
        // Legacy rows without a stored example (blank body can never align)
        // are exempt — there's nothing to check them against.
        val alignsNothing = if (ownPending.isEmpty()) {
            updated.exampleBody.isNotBlank() && !exampleAligned
        } else {
            alignedCount == 0
        }
        if (!allowZeroAlignment && alignsNothing) {
            // The UI turns this into a blocking "matches 0 of your N queued
            // messages" warning (N == 0 → "can't align its own example")
            // with an explicit "Save anyway" — no more silent useless saves
            // that route nothing.
            return "ZERO_ALIGNMENT:${ownPending.size}".left()
        }

        templateRepo.upsert(updated).onLeft { return it.left() }
        if (original.state != TemplateState.ACTIVE) {
            // Going from Unmapped/Pending → Active counts as "mapped" for the
            // user's persistent progress hero on the review screen.
            prefs.incrementTemplatesMappedTotal()
        }
        // The pattern may have changed under the SAME template id — drop the
        // view-time match cache so the templates list reflects the edit now,
        // not after the next sync.
        findMatching.invalidate(templateId)

        // Reprocess ONLY this template's own pending items. Sibling templates
        // from the same sender are drained by PendingReviewViewModel when the
        // active-template set changes — routing all 295 same-sender items here
        // just re-quarantined the other templates' items hundreds of times.
        val pending = if (walletScope != null) {
            // Defensive: drop items whose own sender currently links to a
            // DIFFERENT wallet (possible after cross-sender merges). Unlinked
            // senders pass through so route() can report SENDER_NOT_LINKED
            // honestly instead of the item silently vanishing from the count.
            ownPending.filter { item ->
                val linked = senderToAccount[item.sms.senderId]
                linked == null || linked == walletScope
            }
        } else {
            ownPending
        }
        timber.log.Timber.tag("SmsTrace").i(
            "MAP → reprocess tpl=%s sender=%s walletScope=%s ownPending=%d pendingForWallet=%d",
            updated.id.value, updated.senderIdHint,
            walletScope?.value?.toString() ?: "any",
            ownPending.size, pending.size,
        )
        if (pending.isEmpty()) {
            _progress.value = null
            return MapTemplateResult(
                convertedFromQueue = 0,
                totalOwn = ownPending.size,
                alignedCount = alignedCount,
            ).right()
        }

        // Seed an initial 0/total before the loop so the screen can size its
        // progress bar immediately rather than waiting for the first row.
        _progress.value = MapTemplateProgress(processed = 0, total = pending.size, converted = 0)

        var converted = 0
        var failedAlignment = 0
        var failedAmountParse = 0
        var failedSenderNotLinked = 0
        var failedOther = 0
        try {
            for ((idx, item) in pending.withIndex()) {
                // Every item here belongs to the just-saved template — route via
                // `updated` directly (it's already ACTIVE with the final pattern).
                // userInitiated: the save IS the user's approval, so the
                // per-sender auto-route gate doesn't apply here.
                val outcome = route(item.sms, updated, senderToAccount, userInitiated = true).getOrNull()
                when {
                    outcome is RouteOutcome.Created -> {
                        pendingRepo.dismiss(item.id)
                        prefs.incrementReviewedTotal()
                        converted++
                    }
                    outcome is RouteOutcome.Quarantined &&
                        outcome.reason == QuarantineReason.SENDER_NOT_LINKED -> failedSenderNotLinked++
                    outcome is RouteOutcome.Quarantined &&
                        outcome.reason == QuarantineReason.AMOUNT_NOT_PARSEABLE ->
                        // Routing lumps alignment failures and unparseable amounts
                        // under one quarantine reason; the dry-run already told us
                        // which items align, so split them honestly here.
                        if (alignedByItem[item.id] == true) failedAmountParse++ else failedAlignment++
                    else -> failedOther++
                }
                _progress.value = MapTemplateProgress(
                    processed = idx + 1,
                    total = pending.size,
                    converted = converted,
                )
            }
        } finally {
            // ALWAYS clear — including when the caller's scope is cancelled
            // mid-loop (this app's custom nav clears the ViewModelStore on
            // every screen change, cancelling save()'s viewModelScope at the
            // next suspension). This use case is a @Singleton: a progress
            // value left non-null here rendered a frozen "Reprocessing
            // pending… X / Y" card on EVERY future mapping screen until some
            // later save happened to complete a full loop.
            _progress.value = null
        }
        return MapTemplateResult(
            convertedFromQueue = converted,
            failedAlignment = failedAlignment,
            failedAmountParse = failedAmountParse,
            failedSenderNotLinked = failedSenderNotLinked,
            failedOther = failedOther,
            totalOwn = ownPending.size,
            alignedCount = alignedCount,
        ).right()
    }

    private data class GeneralizedPattern(
        val pattern: String,
        val slots: List<WildcardSlot>,
    )

    private fun autoGeneralizeDigitLiterals(
        patternTokens: List<String>,
        slots: List<WildcardSlot>,
        confirmedLiteralPositions: Set<Int>,
        templateId: SmsTemplateId,
    ): GeneralizedPattern {
        val tokens = patternTokens.toMutableList()
        val newSlots = mutableListOf<WildcardSlot>()
        for ((idx, token) in patternTokens.withIndex()) {
            if (token == WILDCARD_TOKEN) continue
            if (idx in confirmedLiteralPositions) continue
            if (!isDigitBearing(token)) continue
            tokens[idx] = WILDCARD_TOKEN
            newSlots += WildcardSlot(
                id = WildcardId(UUID.randomUUID()),
                positionInPattern = idx,
                contextSnippet = "",
                exampleValue = token,
                role = WildcardRole.Unmapped,
            )
            timber.log.Timber.tag("SmsTrace").i(
                "MAP_SAVE auto-generalized digit literal '%s' at pattern[%d] tpl=%s",
                token, idx, templateId.value,
            )
        }
        if (newSlots.isEmpty()) {
            return GeneralizedPattern(patternTokens.joinToString(" "), slots)
        }
        return GeneralizedPattern(
            pattern = tokens.joinToString(" "),
            slots = (slots + newSlots).sortedBy { it.positionInPattern },
        )
    }
}
