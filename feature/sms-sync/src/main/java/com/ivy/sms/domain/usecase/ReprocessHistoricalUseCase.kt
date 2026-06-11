package com.ivy.sms.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.model.AccountId
import com.ivy.data.repository.TransactionRepository
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.data.SmsInboxDataSource
import com.ivy.sms.data.SmsMessageMapper
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.data.SmsWatermarkPreferences
import com.ivy.sms.domain.model.SmsMessage
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ReprocessPreview(
    val templateId: SmsTemplateId,
    val matchingMessages: Int,
    val newTransactionsToCreate: Int,
)

data class ReprocessResult(
    val transactionsCreated: Int,
    val itemsQuarantined: Int,
)

class ReprocessHistoricalUseCase @Inject constructor(
    private val templateRepo: SmsTemplateRepository,
    private val inbox: SmsInboxDataSource,
    private val watermarks: SmsWatermarkPreferences,
    private val senderRepo: SenderAccountLinkRepository,
    private val transactionRepo: TransactionRepository,
    private val smsMessageMapper: SmsMessageMapper,
    private val route: RouteSmsUseCase,
    private val dispatchers: DispatchersProvider,
) {

    suspend fun preview(templateId: SmsTemplateId): Either<String, ReprocessPreview> = withContext(dispatchers.io) {
        val template = templateRepo.findById(templateId).getOrNull()
            ?: return@withContext "TEMPLATE_NOT_FOUND".left()
        if (template.state != TemplateState.ACTIVE) {
            return@withContext "VALIDATION:template not ACTIVE".left()
        }
        val aligned = alignedMessages(template)
        val existingDedups = existingDedupKeys()
        ReprocessPreview(
            templateId = templateId,
            matchingMessages = aligned.size,
            newTransactionsToCreate = aligned.count { it.dedupKey !in existingDedups },
        ).right()
    }

    suspend fun confirm(templateId: SmsTemplateId, confirmationToken: String): Either<String, ReprocessResult> = withContext(dispatchers.io) {
        if (confirmationToken != templateId.value.toString()) {
            return@withContext "VALIDATION:invalid confirmation token".left()
        }
        val template = templateRepo.findById(templateId).getOrNull()
            ?: return@withContext "TEMPLATE_NOT_FOUND".left()
        if (template.state != TemplateState.ACTIVE) {
            return@withContext "VALIDATION:template not ACTIVE".left()
        }
        val aligned = alignedMessages(template)
        val existingDedups = existingDedupKeys()
        val links = senderRepo.findAll().getOrNull().orEmpty()
        val senderToAccount: Map<String, AccountId> = links.associate { it.senderId to it.accountId }
        var created = 0
        var quarantined = 0
        for (message in aligned) {
            if (message.dedupKey in existingDedups) continue
            // userInitiated: the user typed the confirmation token — bypass
            // the per-sender auto-route gate.
            when (route(message, template, senderToAccount, userInitiated = true).getOrNull()) {
                is RouteOutcome.Created -> created++
                is RouteOutcome.Quarantined -> quarantined++
                is RouteOutcome.Blacklisted, null -> { /* skip */ }
            }
        }
        ReprocessResult(transactionsCreated = created, itemsQuarantined = quarantined).right()
    }

    /**
     * The candidate set shared by [preview] and [confirm]: inbox rows from
     * the template's OWN sender whose bodies the pattern actually aligns
     * (extractWildcardValues — the same predicate routing uses), so the
     * preview number is exactly the set confirm routes. Non-aligning rows
     * are SKIPPED, never quarantined — confirm used to route every inbox row
     * from every sender against the chosen template, flooding the pending
     * queue with OTPs/promos and inflating the lifetime discovered counter.
     * Templates without a sender hint (legacy rows) reprocess nothing.
     */
    private suspend fun alignedMessages(template: SmsTemplate): List<SmsMessage> {
        val sender = template.senderIdHint.ifBlank { return emptyList() }
        // Same effective lower bound the scan reads this sender with: the
        // per-link period pick wins, the legacy global key is only the
        // fallback for links that predate per-link bounds. Without this,
        // preview/confirm on an install configured through the setup sheet
        // (which writes ONLY link.historicalLowerBound) would read the
        // sender's ENTIRE inbox history while the sync honours the picked
        // window — and confirm would import transactions from outside it.
        val link = senderRepo.findBySenderId(sender).getOrNull()
        val lower = link?.historicalLowerBound?.toEpochMilli()
            ?: watermarks.scanLowerBound().getOrNull()
            ?: 0L
        val rows = inbox.read(lower, 0L, senderFilter = sender).getOrNull().orEmpty()
        return rows
            .map { with(smsMessageMapper) { it.toDomain() } }
            .filter { extractWildcardValues(template, it) != null }
    }

    private suspend fun existingDedupKeys(): Set<String> = transactionRepo.findAll()
        .mapNotNull { it.metadata.smsSourceDedupKey }
        .toSet()
}
