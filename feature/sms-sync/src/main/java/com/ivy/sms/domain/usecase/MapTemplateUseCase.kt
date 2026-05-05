package com.ivy.sms.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
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
    ): Either<String, MapTemplateResult> {
        val original = templateRepo.findById(templateId).getOrNull()
            ?: return "TEMPLATE_NOT_FOUND".left()

        val updatedSlots: List<WildcardSlot> = original.wildcardSlots.map { slot ->
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
        val pending = pendingRepo.findAll().getOrNull().orEmpty()
            .filter { it.sms.senderId == updated.senderIdHint }
        timber.log.Timber.tag("SmsTrace").i(
            "MAP → reprocess tpl=%s sender=%s pendingForSender=%d",
            updated.id.value, updated.senderIdHint, pending.size,
        )
        if (pending.isEmpty()) {
            _progress.value = null
            return MapTemplateResult(0).right()
        }

        val links = senderRepo.findAll().getOrNull().orEmpty()
        val senderToAccount = links.associate { it.senderId to it.accountId }

        // Seed an initial 0/total before the loop so the screen can size its
        // progress bar immediately rather than waiting for the first row.
        _progress.value = MapTemplateProgress(processed = 0, total = pending.size, converted = 0)

        var converted = 0
        for ((idx, item) in pending.withIndex()) {
            val itemTemplate = if (item.template.id == updated.id) {
                updated
            } else {
                templateRepo.findById(item.template.id).getOrNull()
            }
            if (itemTemplate != null) {
                val outcome = route(item.sms, itemTemplate, senderToAccount).getOrNull()
                if (outcome is RouteOutcome.Created) {
                    pendingRepo.dismiss(item.id)
                    prefs.incrementReviewedTotal()
                    converted++
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
        return MapTemplateResult(converted).right()
    }
}
