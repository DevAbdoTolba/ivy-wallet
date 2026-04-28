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
import com.ivy.sms.domain.model.TransactionClassification
import com.ivy.sms.domain.model.WildcardMapping
import com.ivy.sms.domain.model.WildcardSlot
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
        wildcardMappings: Map<com.ivy.sms.domain.model.WildcardId, WildcardMapping>,
        classification: TransactionClassification,
    ): Either<String, MapTemplateResult> {
        val original = templateRepo.findById(templateId).getOrNull()
            ?: return "TEMPLATE_NOT_FOUND".left()

        val updatedSlots: List<WildcardSlot> = original.wildcardSlots.map { slot ->
            slot.copy(mapping = wildcardMappings[slot.id] ?: slot.mapping)
        }

        val hasAmount = updatedSlots.any { it.mapping == WildcardMapping.Amount }
        if (!hasAmount) return "VALIDATION:at least one wildcard must be mapped to Amount".left()

        val updated = original.copy(
            wildcardSlots = updatedSlots,
            classification = classification,
            state = TemplateState.ACTIVE,
        )

        templateRepo.upsert(updated).onLeft { return it.left() }

        // Drain any queued pending items for this template — re-route them now that mapping exists.
        val pending = pendingRepo.findByTemplateId(templateId).getOrNull().orEmpty()
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
