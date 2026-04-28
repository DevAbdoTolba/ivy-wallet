package com.ivy.sms.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.data.model.AccountId
import com.ivy.data.model.Expense
import com.ivy.data.model.Income
import com.ivy.data.model.PositiveValue
import com.ivy.data.model.Transaction
import com.ivy.data.model.TransactionId
import com.ivy.data.model.TransactionMetadata
import com.ivy.data.model.Transfer
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.model.primitive.PositiveDouble
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.TransactionRepository
import com.ivy.sms.domain.model.SmsMessage
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.TransactionClassification
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardMapping
import com.ivy.sms.domain.parser.AmountParser
import com.ivy.sms.domain.parser.DateTimeParser
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class CreateTransactionFromSmsUseCase @Inject constructor(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
) {
    suspend operator fun invoke(
        message: SmsMessage,
        template: SmsTemplate,
        account: AccountId,
    ): Either<String, TransactionId> {
        val classification = template.classification
            ?: return "TEMPLATE_NOT_MAPPED:no classification".left()

        val values = extractWildcardValues(template, message)
            ?: return "AMOUNT_NOT_PARSEABLE:token alignment failure".left()

        val amountSlotId = template.wildcardSlots
            .firstOrNull { it.mapping == WildcardMapping.Amount }
            ?.id
            ?: return "AMOUNT_NOT_PARSEABLE:no amount mapping".left()

        val amountText = values[amountSlotId]
            ?: return "AMOUNT_NOT_PARSEABLE:no amount value".left()

        val amount = AmountParser.parseAmount(amountText).getOrNull()
            ?: return "AMOUNT_NOT_PARSEABLE:'$amountText'".left()

        val positive = PositiveDouble.from(amount.toDouble()).getOrNull()
            ?: return "AMOUNT_NOT_PARSEABLE:non-positive '$amountText'".left()

        val acct = accountRepository.findById(account)
            ?: return "STORAGE_ERROR:account not found".left()

        val merchantText = values.firstByMapping(template, WildcardMapping.Merchant)
        val referenceText = values.firstByMapping(template, WildcardMapping.Reference)
        val dateTimeText = values.firstByMapping(template, WildcardMapping.DateTime)

        val txTime: Instant = dateTimeText
            ?.let { DateTimeParser.parseDateTime(it).getOrNull() }
            ?: message.timestamp

        val title = merchantText?.let(NotBlankTrimmedString::from)?.getOrNull()
        val description = referenceText?.let(NotBlankTrimmedString::from)?.getOrNull()
        val transactionId = TransactionId(UUID.randomUUID())
        val value = PositiveValue(amount = positive, asset = acct.asset)

        val metadata = TransactionMetadata(
            recurringRuleId = null,
            paidForDateTime = null,
            loanId = null,
            loanRecordId = null,
            smsSourceDedupKey = message.dedupKey,
            smsTemplateId = template.id.value,
            smsSourceSenderId = message.senderId,
            smsSourceTimestamp = message.timestamp,
        )

        val tx: Transaction = when (classification) {
            TransactionClassification.INCOME -> Income(
                id = transactionId,
                title = title,
                description = description,
                category = null,
                time = txTime,
                settled = true,
                metadata = metadata,
                tags = emptyList(),
                value = value,
                account = account,
            )
            TransactionClassification.EXPENSE -> Expense(
                id = transactionId,
                title = title,
                description = description,
                category = null,
                time = txTime,
                settled = true,
                metadata = metadata,
                tags = emptyList(),
                value = value,
                account = account,
            )
            TransactionClassification.TRANSFER -> Transfer(
                id = transactionId,
                title = title,
                description = description,
                category = null,
                time = txTime,
                settled = true,
                metadata = metadata,
                tags = emptyList(),
                fromAccount = account,
                fromValue = value,
                toAccount = account,
                toValue = value,
            )
        }

        return runCatching { transactionRepository.save(tx) }
            .fold(
                onSuccess = { transactionId.right() },
                onFailure = { "STORAGE_ERROR:${it.message}".left() },
            )
    }
}

private fun Map<WildcardId, String>.firstByMapping(
    template: SmsTemplate,
    mapping: WildcardMapping,
): String? {
    val slot = template.wildcardSlots.firstOrNull { it.mapping == mapping } ?: return null
    return this[slot.id]
}

internal fun extractWildcardValues(
    template: SmsTemplate,
    message: SmsMessage,
): Map<WildcardId, String>? {
    val templateTokens = template.pattern.split(Regex("\\s+")).filter { it.isNotBlank() }
    val messageTokens = message.body.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (templateTokens.size != messageTokens.size) return null
    val slotByPosition = template.wildcardSlots.associateBy { it.positionInPattern }
    val out = mutableMapOf<WildcardId, String>()
    for ((idx, t) in templateTokens.withIndex()) {
        if (t == com.ivy.sms.data.WILDCARD_TOKEN) {
            val slot = slotByPosition[idx] ?: continue
            out[slot.id] = messageTokens[idx]
        }
    }
    return out
}
