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
        val lower = watermarks.scanLowerBound().getOrNull() ?: 0L
        val rows = inbox.read(lower, 0L).getOrNull().orEmpty()
        val matchingTokens = template.pattern.split(Regex("\\s+")).size
        val candidates = rows.filter {
            with(smsMessageMapper) { it.toDomain() }.body.split(Regex("\\s+")).size == matchingTokens
        }
        val existingDedups = transactionRepo.findAll()
            .mapNotNull { it.metadata.smsSourceDedupKey }
            .toSet()
        val newOnly = candidates.filter { row ->
            val msg = with(smsMessageMapper) { row.toDomain() }
            msg.dedupKey !in existingDedups
        }
        ReprocessPreview(
            templateId = templateId,
            matchingMessages = candidates.size,
            newTransactionsToCreate = newOnly.size,
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
        val lower = watermarks.scanLowerBound().getOrNull() ?: 0L
        val rows = inbox.read(lower, 0L).getOrNull().orEmpty()
        val existingDedups = transactionRepo.findAll()
            .mapNotNull { it.metadata.smsSourceDedupKey }
            .toSet()
        val links = senderRepo.findAll().getOrNull().orEmpty()
        val senderToAccount: Map<String, AccountId> = links.associate { it.senderId to it.accountId }
        var created = 0
        var quarantined = 0
        for (row in rows) {
            val message = with(smsMessageMapper) { row.toDomain() }
            if (message.dedupKey in existingDedups) continue
            when (val outcome = route(message, template, senderToAccount).getOrNull()) {
                is RouteOutcome.Created -> created++
                is RouteOutcome.Quarantined -> quarantined++
                is RouteOutcome.Blacklisted, null -> { /* skip */ }
            }
        }
        ReprocessResult(transactionsCreated = created, itemsQuarantined = quarantined).right()
    }
}
