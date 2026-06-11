package com.ivy.sms.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.data.model.AccountId
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.data.SmsWatermarkPreferences
import com.ivy.sms.domain.model.SmsTemplate
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
import javax.inject.Inject
import javax.inject.Singleton

data class MapTemplateResult(
    val convertedFromQueue: Int,
    /** Pending items that couldn't be aligned to this template's pattern.
     *  Surfaces "Partially mapped" UX after save — without this the user
     *  sees the template still flagged as needing roles even though one
     *  message routed cleanly. */
    val failedAlignment: Int = 0,
    /** Total pending items considered. `convertedFromQueue + failedAlignment
     *  ≤ totalPending` (the gap is items skipped for non-alignment reasons,
     *  e.g. their own template wasn't ACTIVE yet). */
    val totalPending: Int = 0,
)

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

@Singleton
class MapTemplateUseCase @Inject constructor(
    private val templateRepo: SmsTemplateRepository,
    private val pendingRepo: PendingReviewItemRepository,
    private val senderRepo: SenderAccountLinkRepository,
    private val route: RouteSmsUseCase,
    private val prefs: SmsWatermarkPreferences,
) {
    private val _progress = MutableStateFlow<MapTemplateProgress?>(null)
    val progress: StateFlow<MapTemplateProgress?> = _progress.asStateFlow()

    suspend operator fun invoke(
        templateId: SmsTemplateId,
        wildcardRoles: Map<WildcardId, WildcardRole>,
        name: String? = null,
        patchedPattern: String? = null,
        patchedSlots: List<WildcardSlot>? = null,
        /** Wallet to scope reprocess to. When non-null, only pending items
         *  whose senderId resolves (via [SenderAccountLinkRepository]) to
         *  this account are routed. Prevents wallet-2's mapping action from
         *  silently creating transactions for wallet-1's queued items —
         *  the user reported this as a cross-wallet leak. */
        walletScope: AccountId? = null,
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

        val updated = original.copy(
            pattern = workingPattern,
            wildcardSlots = updatedSlots,
            state = TemplateState.ACTIVE,
            name = name?.trim()?.ifBlank { null } ?: original.name,
        )

        templateRepo.upsert(updated).onLeft { return it.left() }
        if (original.state != TemplateState.ACTIVE) {
            // Going from Unmapped/Pending → Active counts as "mapped" for the
            // user's persistent progress hero on the review screen.
            prefs.incrementTemplatesMappedTotal()
        }

        // Drain any queued pending items from the SAME SENDER. Use EACH item's
        // OWN template (re-fetched to pick up any state change made above for
        // the just-mapped one) — earlier this used `updated` for everything,
        // which silently failed for items belonging to OTHER templates from
        // the same sender. Concretely: a Vodafone Cash sender produces both
        // "تم استلام …" (income) and "تم تحويل …" (transfer) clusters because
        // their first three stable tokens differ; mapping the income template
        // can't possibly route a transfer message via that pattern, so the
        // transfer items stayed pending and the user said "the transfer
        // message wasn't even read".
        val links = senderRepo.findAll().getOrNull().orEmpty()
        val senderToAccount = links.associate { it.senderId to it.accountId }

        val pendingForSender = pendingRepo.findAll().getOrNull().orEmpty()
            .filter { it.sms.senderId == updated.senderIdHint }
        val pending = if (walletScope != null) {
            // Drop items whose sender currently links to a different wallet
            // than the one the user opened the mapper from. With 1:1
            // sender→wallet links this is usually a no-op, but it protects
            // against a sender being relinked between syncs — old pending
            // items shouldn't suddenly route into the new wallet.
            pendingForSender.filter {
                senderToAccount[it.sms.senderId] == walletScope
            }
        } else {
            pendingForSender
        }
        timber.log.Timber.tag("SmsTrace").i(
            "MAP → reprocess tpl=%s sender=%s walletScope=%s pendingForSender=%d pendingForWallet=%d",
            updated.id.value, updated.senderIdHint,
            walletScope?.value?.toString() ?: "any",
            pendingForSender.size, pending.size,
        )
        if (pending.isEmpty()) {
            _progress.value = null
            return MapTemplateResult(
                convertedFromQueue = 0,
                failedAlignment = 0,
                totalPending = 0,
            ).right()
        }

        // Seed an initial 0/total before the loop so the screen can size its
        // progress bar immediately rather than waiting for the first row.
        _progress.value = MapTemplateProgress(processed = 0, total = pending.size, converted = 0)

        var converted = 0
        var failed = 0
        for ((idx, item) in pending.withIndex()) {
            val itemTemplate = if (item.template.id == updated.id) {
                updated
            } else {
                templateRepo.findById(item.template.id).getOrNull()
            }
            if (itemTemplate != null) {
                val outcome = route(item.sms, itemTemplate, senderToAccount).getOrNull()
                when {
                    outcome is RouteOutcome.Created -> {
                        pendingRepo.dismiss(item.id)
                        prefs.incrementReviewedTotal()
                        converted++
                    }
                    // Any non-Created outcome means the message stays
                    // pending under the same template — could be alignment
                    // failure, quarantine, or amount-not-parseable. We
                    // surface it as "failed alignment" because that's the
                    // dominant cause given the user's current cluster
                    // shapes; the UI nudges them to edit the pattern.
                    itemTemplate.id == updated.id -> failed++
                    else -> Unit // sibling template; not our concern here
                }
            }
            _progress.value = MapTemplateProgress(
                processed = idx + 1,
                total = pending.size,
                converted = converted,
            )
        }
        // Clear so the next subscriber doesn't see a stale "100% done" snapshot.
        _progress.value = null
        return MapTemplateResult(
            convertedFromQueue = converted,
            failedAlignment = failed,
            totalPending = pending.size,
        ).right()
    }
}
