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

        val amount = AmountParser.parseAmount(amountText).getOrNull()
            ?: return "AMOUNT_NOT_PARSEABLE:'$amountText'".left()

        val positive = PositiveDouble.from(amount.toDouble()).getOrNull()
            ?: return "AMOUNT_NOT_PARSEABLE:non-positive '$amountText'".left()

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

        val txTime: Instant = dateTimeText
            ?.let { DateTimeParser.parseDateTime(it).getOrNull() }
            ?: message.timestamp

        // Title resolution (optional, never blank):
        //   1. User-set template name (FR-024).
        //   2. Captured merchant text — useful when no name is set.
        //   3. First few non-numeric words of the SMS body — last-resort fallback
        //      so the transactions list never shows a blank row.
        // Merchant ALSO flows to the description as "to/from <merchant>" so the
        // same merchant string appears in both places when both apply.
        val titleSource = template.name?.takeIf { it.isNotBlank() }
            ?: merchantText
            ?: firstWordsOf(message.body)
        val title = titleSource?.let(NotBlankTrimmedString::from)?.getOrNull()

        // Merchant flows to description as "to <merchant>" / "from <merchant>"
        // depending on the amount role — matches the reading direction the user
        // sees on the transactions list ("Spent X to Cafe", "Received X from Bob").
        val merchantPrefix = when (amountSlot.role) {
            WildcardRole.Income -> "from"
            WildcardRole.Expense, WildcardRole.Transfer -> "to"
            else -> null
        }
        val descriptionText = if (merchantText != null && merchantPrefix != null) {
            "$merchantPrefix $merchantText"
        } else {
            merchantText
        }
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
 * Best-effort short label drawn from the SMS body: takes the first few
 * non-numeric tokens (skipping digit-only chunks that would just look like noise
 * in the title), capped at ~30 chars. Used as the last-resort fallback when the
 * user hasn't named the template AND no merchant was captured.
 */
private fun firstWordsOf(body: String): String? {
    if (body.isBlank()) return null
    val digit = Regex("[0-9\\u0660-\\u0669\\u06F0-\\u06F9]")
    val words = body.split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .filterNot { digit.containsMatchIn(it) }
        .take(4)
    if (words.isEmpty()) return null
    val joined = words.joinToString(" ")
    return if (joined.length <= 30) joined else joined.take(30).trimEnd() + "…"
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
