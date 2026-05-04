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
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.domain.model.isAmountRole
import com.ivy.sms.domain.parser.AmountParser
import com.ivy.sms.domain.parser.DateTimeParser
import java.math.BigDecimal
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
        val amountSlot = template.wildcardSlots.firstOrNull { it.role.isAmountRole() }
            ?: return "TEMPLATE_NOT_MAPPED:no amount-role wildcard".left()

        val values = extractWildcardValues(template, message)
            ?: return "AMOUNT_NOT_PARSEABLE:token alignment failure".left()

        val amountText = values[amountSlot.id]
            ?: return "AMOUNT_NOT_PARSEABLE:no amount value".left()

        val rawAmount = AmountParser.parseAmount(amountText).getOrNull()
            ?: return "AMOUNT_NOT_PARSEABLE:'$amountText'".left()

        val acct = accountRepository.findById(account)
            ?: return "STORAGE_ERROR:account not found".left()

        // Optional roles: pick the first wildcard for each (uniqueness enforced at save).
        val merchantText = values.allByRole(template, WildcardRole.Merchant)
            .joinToString(" ")
            .ifBlank { null }
        // Date roles split into three UI variants — pick whichever the user bound.
        // DateTimeParser tries several common formats, so the variant is just a UX
        // hint. If parsing fails, we fall back to the SMS message timestamp below.
        val dateTimeText = values.firstByRole(template, WildcardRole.DateFull)
            ?: values.firstByRole(template, WildcardRole.DateOnly)
            ?: values.firstByRole(template, WildcardRole.TimeOnly)
        val currentTotal = values.firstByRole(template, WildcardRole.CurrentTotal)
        val transactionFee = values.firstByRole(template, WildcardRole.TransactionFee)

        // Aggregate the fee into the transaction amount when the fee is a
        // SEPARATE deduction on top of the principal — i.e. for Expense and
        // Transfer the wallet leaves with `amount + fee`, for Income the
        // bank usually advertises the gross amount and deducts the fee
        // before crediting, so wallet receives `amount - fee`. Without this
        // the user transferred 8500 with a 1 EGP fee and the wallet showed
        // -8500 instead of the actual -8501.
        val feeBigDecimal = transactionFee?.let { AmountParser.parseAmount(it).getOrNull() }
        val effectiveAmount = when (amountSlot.role) {
            WildcardRole.Expense, WildcardRole.Transfer -> if (feeBigDecimal != null) {
                rawAmount.add(feeBigDecimal)
            } else {
                rawAmount
            }
            WildcardRole.Income -> if (feeBigDecimal != null) {
                rawAmount.subtract(feeBigDecimal).coerceAtLeast(BigDecimal.ZERO)
            } else {
                rawAmount
            }
            else -> rawAmount
        }
        val positive = PositiveDouble.from(effectiveAmount.toDouble()).getOrNull()
            ?: return "AMOUNT_NOT_PARSEABLE:non-positive '$amountText'".left()

        val txTime: Instant = dateTimeText
            ?.let { DateTimeParser.parseDateTime(it).getOrNull() }
            ?: message.timestamp

        // Title resolution (optional, never blank):
        //   1. User-set template name (FR-024) — explicit always wins.
        //   2. First few non-numeric words of the SMS body — keeps the
        //      transaction title readable AND distinct from the merchant
        //      that goes into the description on the next line. The user
        //      asked for this swap: "if there is a merchant selected, the
        //      title should be the first words of the message".
        //   3. Captured merchant text — only used when there's no body
        //      (legacy templates without a stored example). Should rarely fire.
        val titleSource = template.name?.takeIf { it.isNotBlank() }
            ?: firstWordsOf(message.body)
            ?: merchantText
        val title = titleSource?.let(NotBlankTrimmedString::from)?.getOrNull()

        // Description: line 1 is "to/from <merchant>" if a merchant slot was
        // mapped, line 2 is "Fee: <amount>" if a transaction-fee slot was
        // mapped. Keeps the wallet detail panel useful at a glance — the user
        // can see who/what the txn was for AND the fee charged without
        // tapping into the SMS source.
        val merchantPrefix = when (amountSlot.role) {
            WildcardRole.Income -> "from"
            WildcardRole.Expense, WildcardRole.Transfer -> "to"
            else -> null
        }
        val merchantLine = if (merchantText != null && merchantPrefix != null) {
            "$merchantPrefix $merchantText"
        } else {
            merchantText
        }
        val feeLine = transactionFee
            ?.let { AmountParser.parseAmount(it).getOrNull() }
            ?.let { feeAmount -> "Fee: ${feeAmount.stripTrailingZeros().toPlainString()}" }
        val descriptionText = listOfNotNull(merchantLine, feeLine)
            .joinToString("\n")
            .ifBlank { null }
        val description = descriptionText?.let(NotBlankTrimmedString::from)?.getOrNull()

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
            smsCurrentTotal = currentTotal,
            smsTransactionFee = transactionFee,
        )

        val tx: Transaction = when (amountSlot.role) {
            WildcardRole.Income -> Income(
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
            WildcardRole.Expense -> Expense(
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
            WildcardRole.Transfer -> Transfer(
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
            else -> return "TEMPLATE_NOT_MAPPED:non-amount role on amount slot".left()
        }

        return runCatching { transactionRepository.save(tx) }
            .fold(
                onSuccess = { transactionId.right() },
                onFailure = { "STORAGE_ERROR:${it.message}".left() },
            )
    }
}

/**
 * Best-effort short label drawn from the SMS body — takes the first few
 * tokens verbatim (digits included), capped at ~30 chars. Earlier this
 * skipped digit-bearing tokens, which produced titles like "Withdrawal of"
 * instead of "Withdrawal of 100" and the user couldn't tell two transactions
 * apart at a glance. Now if there's a token among the first few words —
 * a number, a date, anything — it carries through to the title.
 */
private fun firstWordsOf(body: String): String? {
    if (body.isBlank()) return null
    val words = body.split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .take(5)
    if (words.isEmpty()) return null
    val joined = words.joinToString(" ")
    return if (joined.length <= 36) joined else joined.take(36).trimEnd() + "…"
}

private fun Map<WildcardId, String>.firstByRole(
    template: SmsTemplate,
    role: WildcardRole,
): String? {
    val slot = template.wildcardSlots.firstOrNull { it.role == role } ?: return null
    return this[slot.id]
}

private fun Map<WildcardId, String>.allByRole(
    template: SmsTemplate,
    role: WildcardRole,
): List<String> {
    return template.wildcardSlots
        .filter { it.role == role }
        .mapNotNull { this[it.id] }
}

/**
 * Align a (possibly merged-collapsed) pattern with a concrete SMS body and return
 * the captured value for every wildcard slot.
 *
 * Drain merges fragment runs of variable tokens into a single `<*>`, so a pattern
 * may be shorter than the body (e.g. pattern `Spent EGP <*> at <*>` matches body
 * `Spent EGP 70 at Coffee Shop` — the trailing wildcard absorbs two tokens). A
 * naive index-by-index extraction would mis-align everything past the first
 * collapsed wildcard, which is exactly why pending items kept their old
 * "AMOUNT_NOT_PARSEABLE" reason after the user mapped the template.
 *
 * Algorithm: walk the pattern. For each literal, scan the body forward to find
 * the next matching token (case-insensitive) and consume it. For each `<*>`,
 * grab all body tokens up to the next literal (or end-of-body if no more
 * literals follow) and join them — that joined string is the slot value.
 */
internal fun extractWildcardValues(
    template: SmsTemplate,
    message: SmsMessage,
): Map<WildcardId, String>? {
    val patternTokens = template.pattern.split(Regex("\\s+")).filter { it.isNotBlank() }
    val bodyTokens = message.body.split(Regex("\\s+")).filter { it.isNotBlank() }
    val slotByPosition = template.wildcardSlots.associateBy { it.positionInPattern }

    val out = mutableMapOf<WildcardId, String>()
    var bodyIdx = 0

    for ((patternIdx, ptok) in patternTokens.withIndex()) {
        if (ptok != com.ivy.sms.data.WILDCARD_TOKEN) {
            val matchIdx = (bodyIdx until bodyTokens.size).firstOrNull {
                bodyTokens[it].equals(ptok, ignoreCase = true)
            } ?: return null
            bodyIdx = matchIdx + 1
            continue
        }

        // Wildcard: find next literal pattern token to know where this <*> stops.
        val nextLiteralPatternIdx = (patternIdx + 1 until patternTokens.size).firstOrNull {
            patternTokens[it] != com.ivy.sms.data.WILDCARD_TOKEN
        }

        val slot = slotByPosition[patternIdx]
        if (nextLiteralPatternIdx == null) {
            // Trailing wildcard — consume rest of body.
            if (slot != null && bodyIdx < bodyTokens.size) {
                out[slot.id] = bodyTokens.subList(bodyIdx, bodyTokens.size).joinToString(" ")
            }
            bodyIdx = bodyTokens.size
        } else {
            val nextLiteral = patternTokens[nextLiteralPatternIdx]
            val matchIdx = (bodyIdx until bodyTokens.size).firstOrNull {
                bodyTokens[it].equals(nextLiteral, ignoreCase = true)
            } ?: return null
            if (slot != null && matchIdx > bodyIdx) {
                out[slot.id] = bodyTokens.subList(bodyIdx, matchIdx).joinToString(" ")
            }
            // Don't advance past matchIdx — the literal will be consumed on the
            // next loop iteration. Bare-empty wildcards (matchIdx == bodyIdx) are
            // legal: pattern `<*> X` against body `X` leaves the leading slot empty.
            bodyIdx = matchIdx
        }
    }

    return out
}
