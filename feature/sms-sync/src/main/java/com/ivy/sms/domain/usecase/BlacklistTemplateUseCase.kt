package com.ivy.sms.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import javax.inject.Inject

class BlacklistTemplateUseCase @Inject constructor(
    private val templateRepo: SmsTemplateRepository,
    private val pendingRepo: PendingReviewItemRepository,
) {
    suspend fun enable(templateId: SmsTemplateId): Either<String, Unit> {
        val template = templateRepo.findById(templateId).getOrNull()
            ?: return "TEMPLATE_NOT_FOUND".left()
        templateRepo.upsert(template.copy(state = TemplateState.BLACKLISTED))
            .onLeft { return it.left() }
        // Same conceptual transaction — clear queued items for this template.
        return pendingRepo.clearByTemplate(templateId)
    }

    suspend fun disable(templateId: SmsTemplateId): Either<String, Unit> {
        val template = templateRepo.findById(templateId).getOrNull()
            ?: return "TEMPLATE_NOT_FOUND".left()
        // Disable goes back to UNMAPPED — user must re-bind roles before Tier 1
        // can fire again (per data-model.md §6 fourth rule).
        val resetSlots = template.wildcardSlots.map {
            it.copy(role = com.ivy.sms.domain.model.WildcardRole.Unmapped)
        }
        return templateRepo.upsert(
            template.copy(
                state = TemplateState.UNMAPPED,
                wildcardSlots = resetSlots,
            ),
        ).map { Unit }
    }
}
