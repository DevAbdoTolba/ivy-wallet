package com.ivy.sms.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.domain.model.WildcardSlot
import com.ivy.sms.domain.model.isAmountRole
import com.ivy.sms.domain.model.isUnique
import javax.inject.Inject

data class MapTemplateResult(
    val convertedFromQueue: Int,
)

class MapTemplateUseCase @Inject constructor(
    private val templateRepo: SmsTemplateRepository,
    private val pendingRepo: PendingReviewItemRepository,
    private val senderRepo: SenderAccountLinkRepository,
    private val route: RouteSmsUseCase,
) {
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

        // Drain any queued pending items from the SAME SENDER (not just same
        // templateId). Drain may have clustered the user's existing SMS into a
        // separate template, so the just-mapped roles can resolve items that
        // never had this templateId. extractWildcardValues returns null for
        // bodies that don't actually align, so this can't mis-route.
        val pending = pendingRepo.findAll().getOrNull().orEmpty()
            .filter { it.sms.senderId == updated.senderIdHint }
        if (pending.isEmpty()) return MapTemplateResult(0).right()

        val links = senderRepo.findAll().getOrNull().orEmpty()
        val senderToAccount = links.associate { it.senderId to it.accountId }

        var converted = 0
        for (item in pending) {
            val outcome = route(item.sms, updated, senderToAccount).getOrNull() ?: continue
            if (outcome is RouteOutcome.Created) {
                pendingRepo.dismiss(item.id)
                converted++
            }
        }
        return MapTemplateResult(converted).right()
    }
}
