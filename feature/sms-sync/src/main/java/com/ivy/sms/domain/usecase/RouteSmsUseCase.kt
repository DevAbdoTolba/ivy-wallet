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
import timber.log.Timber

private const val TRACE = "SmsTrace"

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
        val tag = "tpl=${template.id.value} sender=${message.senderId} body='${message.body.take(60)}…'"
        Timber.tag(TRACE).d("ROUTE → enter %s state=%s", tag, template.state)

        if (template.state == TemplateState.BLACKLISTED) {
            Timber.tag(TRACE).d("ROUTE ✗ BLACKLISTED %s", tag)
            return RouteOutcome.Blacklisted.right()
        }

        val account = senderLinks[message.senderId]
        val hasAmountRole = template.wildcardSlots.any { it.role.isAmountRole() }
        if (template.state == TemplateState.ACTIVE && account != null && hasAmountRole) {
            return when (val r = createTransaction(message, template, account)) {
                is Either.Right -> {
                    Timber.tag(TRACE).d("ROUTE ✓ CREATED txn=%s %s", r.value.value, tag)
                    RouteOutcome.Created(r.value).right()
                }
                is Either.Left -> {
                    val reason = when {
                        r.value.startsWith("AMOUNT_NOT_PARSEABLE") -> QuarantineReason.AMOUNT_NOT_PARSEABLE
                        r.value.startsWith("CURRENCY_MISMATCH") -> QuarantineReason.CURRENCY_MISMATCH
                        else -> QuarantineReason.AMOUNT_NOT_PARSEABLE
                    }
                    Timber.tag(TRACE).w("ROUTE ✗ QUARANTINED reason=%s detail='%s' %s", reason, r.value, tag)
                    quarantine(message, template, reason)
                }
            }
        }

        val reason = when {
            account == null -> QuarantineReason.SENDER_NOT_LINKED
            !hasAmountRole -> QuarantineReason.TEMPLATE_NOT_MAPPED
            else -> QuarantineReason.TEMPLATE_NOT_MAPPED
        }
        Timber.tag(TRACE).w(
            "ROUTE ✗ QUARANTINED pre-route reason=%s state=%s account=%s hasAmount=%s %s",
            reason, template.state, account, hasAmountRole, tag,
        )
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
