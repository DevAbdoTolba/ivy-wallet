package com.ivy.sms.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.domain.model.PendingReviewItemId
import com.ivy.sms.domain.model.SmsTemplateId
import javax.inject.Inject

data class ConversionResult(
    val converted: Int,
    val remaining: Int,
)

class ResolvePendingItemUseCase @Inject constructor(
    private val pendingRepo: PendingReviewItemRepository,
    private val templateRepo: SmsTemplateRepository,
    private val senderRepo: SenderAccountLinkRepository,
    private val route: RouteSmsUseCase,
) {

    suspend fun dismiss(id: PendingReviewItemId): Either<String, Unit> = pendingRepo.dismiss(id)

    suspend fun convertViaTemplateMapping(templateId: SmsTemplateId): Either<String, ConversionResult> {
        val template = templateRepo.findById(templateId).getOrNull()
            ?: return "TEMPLATE_NOT_FOUND".left()
        val items = pendingRepo.findByTemplateId(templateId).getOrNull().orEmpty()
        if (items.isEmpty()) return ConversionResult(0, 0).right()
        val links = senderRepo.findAll().getOrNull().orEmpty()
        val senderToAccount = links.associate { it.senderId to it.accountId }
        var converted = 0
        for (item in items) {
            // userInitiated: the user explicitly mapped/saved — bypasses the
            // per-sender auto-route gate so held items can actually convert.
            val outcome = route(item.sms, template, senderToAccount, userInitiated = true).getOrNull()
            if (outcome is RouteOutcome.Created) {
                pendingRepo.dismiss(item.id)
                converted++
            }
        }
        return ConversionResult(converted = converted, remaining = items.size - converted).right()
    }
}
