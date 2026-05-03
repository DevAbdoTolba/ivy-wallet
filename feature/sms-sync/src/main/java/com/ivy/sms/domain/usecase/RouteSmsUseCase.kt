package com.ivy.sms.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.data.model.AccountId
import com.ivy.data.model.TransactionId
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.domain.model.PendingReviewItem
import com.ivy.sms.domain.model.PendingReviewItemId
import com.ivy.sms.domain.model.QuarantineReason
import com.ivy.sms.domain.model.SmsMessage
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.model.isAmountRole
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

sealed interface RouteOutcome {
    data object Blacklisted : RouteOutcome
    data class Quarantined(val reason: QuarantineReason) : RouteOutcome
    data class Created(val transactionId: TransactionId) : RouteOutcome
}

class RouteSmsUseCase @Inject constructor(
    private val createTransaction: CreateTransactionFromSmsUseCase,
    private val pendingRepo: PendingReviewItemRepository,
) {
    suspend operator fun invoke(
        message: SmsMessage,
        template: SmsTemplate,
        senderLinks: Map<String, AccountId>,
    ): Either<String, RouteOutcome> {
        // Blacklist short-circuit MUST come BEFORE the Tier 1 check.
        if (template.state == TemplateState.BLACKLISTED) {
            return RouteOutcome.Blacklisted.right()
        }

        val account = senderLinks[message.senderId]
        val hasAmountRole = template.wildcardSlots.any { it.role.isAmountRole() }
        if (template.state == TemplateState.ACTIVE && account != null && hasAmountRole) {
            return when (val r = createTransaction(message, template, account)) {
                is Either.Right -> RouteOutcome.Created(r.value).right()
                is Either.Left -> {
                    val reason = when {
                        r.value.startsWith("AMOUNT_NOT_PARSEABLE") -> QuarantineReason.AMOUNT_NOT_PARSEABLE
                        r.value.startsWith("CURRENCY_MISMATCH") -> QuarantineReason.CURRENCY_MISMATCH
                        else -> QuarantineReason.AMOUNT_NOT_PARSEABLE
                    }
                    quarantine(message, template, reason)
                }
            }
        }

        val reason = when {
            account == null -> QuarantineReason.SENDER_NOT_LINKED
            else -> QuarantineReason.TEMPLATE_NOT_MAPPED
        }
        return quarantine(message, template, reason)
    }

    private suspend fun quarantine(
        message: SmsMessage,
        template: SmsTemplate,
        reason: QuarantineReason,
    ): Either<String, RouteOutcome> {
        val item = PendingReviewItem(
            id = PendingReviewItemId(UUID.randomUUID()),
            sms = message,
            template = template,
            quarantineReason = reason,
            enqueuedAt = Instant.now(),
        )
        return pendingRepo.enqueue(item).fold(
            { it.left() },
            { RouteOutcome.Quarantined(reason).right() },
        )
    }
}

internal fun SmsTemplate.hasAmountRole(): Boolean =
    wildcardSlots.any { it.role.isAmountRole() }
