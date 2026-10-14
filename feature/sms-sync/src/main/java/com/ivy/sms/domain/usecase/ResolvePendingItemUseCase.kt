package com.ivy.sms.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.data.model.AccountId
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.data.SmsWatermarkPreferences
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
    private val prefs: SmsWatermarkPreferences,
) {

    suspend fun dismiss(id: PendingReviewItemId): Either<String, Unit> = pendingRepo.dismiss(id)

    /**
     * Resolves ONE TRANSFER_NEEDS_DESTINATION item with the wallet the user
     * picked on the review card. With [rememberForTemplate] the destination is
     * persisted on the template FIRST (so a concurrent drain routes siblings
     * consistently), and after a successful conversion the template's other
     * queued items are re-routed too — they can now auto-convert.
     */
    suspend fun resolveWithDestination(
        itemId: PendingReviewItemId,
        destination: AccountId,
        rememberForTemplate: Boolean,
    ): Either<String, RouteOutcome> {
        val item = pendingRepo.findAll().getOrNull().orEmpty()
            .firstOrNull { it.id == itemId }
            ?: return "PENDING_ITEM_NOT_FOUND".left()

        // Fresh template read: picks up the remembered destination and any
        // other edit that happened since this item was enqueued.
        val template = templateRepo.findById(item.template.id).getOrNull() ?: item.template

        val links = senderRepo.findAll().getOrNull().orEmpty()
        val senderToAccount = links.associate { it.senderId to it.accountId }

        // userInitiated: the user is resolving this exact item — the
        // per-sender auto-route gate must not divert it back to the queue.
        val outcome = route(
            message = item.sms,
            template = template,
            senderLinks = senderToAccount,
            userInitiated = true,
            transferDestinationOverride = destination,
        ).getOrNull() ?: return "ROUTE_FAILED".left()

        if (outcome is RouteOutcome.Created) {
            pendingRepo.dismiss(item.id)
            prefs.incrementReviewedTotal()
            if (rememberForTemplate) {
                templateRepo.updateTransferDestination(item.template.id, destination)
                    .onLeft { return it.left() }
                // Siblings quarantined for the same missing destination can
                // convert now that the template carries a default.
                convertViaTemplateMapping(template.id)
            }
        }
        return outcome.right()
    }

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
