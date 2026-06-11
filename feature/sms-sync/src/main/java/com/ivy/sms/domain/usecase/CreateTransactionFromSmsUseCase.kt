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
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.domain.model.SmsMessage
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.domain.model.WildcardSlot
import com.ivy.sms.domain.model.isAmountRole
import com.ivy.sms.domain.parser.AmountParser
import com.ivy.sms.domain.parser.DateTimeParser
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

private const val TRACE = "SmsTrace"

@Singleton
class CreateTransactionFromSmsUseCase @Inject constructor(
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val pendingRepository: PendingReviewItemRepository,
) {
    /**
     * Serializes the dedup-check → save window. The guard below is
     * read-then-write with NO unique index on transactions.smsSourceDedupKey
     * (the index is deferred to the next Room migration), so two concurrent
     * routing paths — e.g. an application-scoped rescan racing a
     * template-save reprocess — could both see "not imported yet" and
     * double-create the same SMS. Every routing path converges on this use
     * case (kept @Singleton for exactly that reason), so one process-wide
     * mutex closes the race.
     */
    private val dedupMutex = Mutex()

    suspend operator fun invoke(
        message: SmsMessage,
        template: SmsTemplate,
        account: AccountId,
    ): Either<String, TransactionId> = dedupMutex.withLock {
        val tag = "tpl=${template.id.value} body='${message.body.take(60)}…'"
        Timber.tag(TRACE).d("CREATE → enter %s pattern='%s'", tag, template.pattern)

        // Transaction-level dedup guard: the same SMS reaches this point via
        // several paths (launch scan, manual sync, pending-queue drain,
        // template-save reprocess). metadata.smsSourceDedupKey is the source
        // of truth for "already imported" — short-circuit to the existing
        // transaction so every caller sees a Created outcome, and clear the
        // pending twin an earlier failed route may have left behind.
        val existing = runCatching {
            transactionRepository.findIdBySmsSourceDedupKey(message.dedupKey)
        }.getOrNull()
        if (existing != null) {
            Timber.tag(TRACE).d("CREATE ↺ dedup hit — existing txn=%s %s", existing.value, tag)
            pendingRepository.dismissByDedupKey(message.dedupKey)
            return existing.right()
        }

        val amountSlot = template.wildcardSlots.firstOrNull { it.role.isAmountRole() }
        if (amountSlot == null) {
            Timber.tag(TRACE).w("CREATE ✗ no amount-role slot %s", tag)
            return "TEMPLATE_NOT_MAPPED:no amount-role wildcard".left()
        }

        val values = extractWildcardValues(template, message)
        if (values == null) {
            Timber.tag(TRACE).w("CREATE ✗ extract returned null (alignment fail) %s", tag)
            return "AMOUNT_NOT_PARSEABLE:token alignment failure".left()
        }
        Timber.tag(TRACE).d(
            "CREATE   extract %s → %s",
            tag,
            values.entries.joinToString { "${it.key.value}='${it.value}'" },
        )

        val amountText = values[amountSlot.id]
        if (amountText == null) {
            Timber.tag(TRACE).w(
                "CREATE ✗ no amount value at slot=%s role=%s %s",
                amountSlot.id.value, amountSlot.role, tag,
            )
            return "AMOUNT_NOT_PARSEABLE:no amount value".left()
        }

        val rawAmount = AmountParser.parseAmount(amountText).getOrNull()
        if (rawAmount == null) {
            Timber.tag(TRACE).w("CREATE ✗ AmountParser failed on '%s' %s", amountText, tag)
            return "AMOUNT_NOT_PARSEABLE:'$amountText'".left()
        }

        val acct = accountRepository.findById(account)
            ?: return "STORAGE_ERROR:account not found".left()

        // Optional roles: pick the first wildcard for each (uniqueness enforced at save).
        val merchantText = values.allByRole(template, WildcardRole.Merchant)
            .joinToString(" ")
            .ifBlank { null }
        // Date roles split into three UI variants. DateOnly and TimeOnly may
        // COEXIST on a template and are combined below; any missing or
        // unparseable component is filled from the SMS message timestamp —
        // never midnight.
        val dateFullText = values.firstByRole(template, WildcardRole.DateFull)
        val dateOnlyText = values.firstByRole(template, WildcardRole.DateOnly)
        val timeOnlyText = values.firstByRole(template, WildcardRole.TimeOnly)
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

        val txTime: Instant = resolveTransactionTime(
            dateFullText = dateFullText,
            dateOnlyText = dateOnlyText,
            timeOnlyText = timeOnlyText,
            fallback = message.timestamp,
        )

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
            else -> {
                Timber.tag(TRACE).w("CREATE ✗ non-amount role on amount slot %s", tag)
                return "TEMPLATE_NOT_MAPPED:non-amount role on amount slot".left()
            }
        }

        return runCatching { transactionRepository.save(tx) }
            .fold(
                onSuccess = {
                    Timber.tag(TRACE).d(
                        "CREATE ✓ saved txn=%s amount=%s role=%s %s",
                        transactionId.value, effectiveAmount, amountSlot.role, tag,
                    )
                    // Any Created outcome must also remove the pending twin
                    // with the same dedupKey (enqueued by an earlier failed
                    // route) — otherwise the drain path converts it again
                    // into a duplicate transaction.
                    pendingRepository.dismissByDedupKey(message.dedupKey)
                    transactionId.right()
                },
                onFailure = {
                    Timber.tag(TRACE).e(it, "CREATE ✗ repo.save threw %s", tag)
                    "STORAGE_ERROR:${it.message}".left()
                },
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
 * Resolve the transaction timestamp from the captured date/time slot values.
 *
 *  - DateFull wins outright when its text parses with BOTH date and time.
 *  - DateOnly + TimeOnly are combined into one LocalDateTime when both are
 *    mapped and parse (the two roles may coexist on a template).
 *  - A missing or unparseable component is taken from the SMS message
 *    timestamp: a date-only SMS received at 14:32 books at 14:32 on the
 *    stated date — NOT at midnight — and a lone time-of-day uses the
 *    message's date.
 */
private fun resolveTransactionTime(
    dateFullText: String?,
    dateOnlyText: String?,
    timeOnlyText: String?,
    fallback: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): Instant {
    dateFullText?.let { text ->
        DateTimeParser.parseLocalDateTime(text).getOrNull()?.let {
            return it.atZone(zone).toInstant()
        }
    }
    // A DateFull capture that turned out to be date-only (or time-only) still
    // contributes its component — the role variant is a UX hint, not a
    // format guarantee.
    val date = listOfNotNull(dateOnlyText, dateFullText)
        .firstNotNullOfOrNull { DateTimeParser.parseLocalDate(it).getOrNull() }
    val time = listOfNotNull(timeOnlyText, dateFullText)
        .firstNotNullOfOrNull { DateTimeParser.parseLocalTime(it).getOrNull() }
    if (date == null && time == null) return fallback
    val fallbackLocal = fallback.atZone(zone).toLocalDateTime()
    return LocalDateTime.of(
        date ?: fallbackLocal.toLocalDate(),
        time ?: fallbackLocal.toLocalTime(),
    ).atZone(zone).toInstant()
}

/**
 * Align a pattern with a concrete SMS body and return the captured value for
 * every wildcard slot, or null when the body does not match. The pattern may
 * include consecutive `<*>` runs; the body tokens between a run's start and
 * the next pattern literal are distributed one-per-slot in order, and the
 * LAST slot absorbs any leftover only when its role expects free text.
 *
 * Alignment rules (this predicate is shared verbatim by FindMatching's view
 * counts and by routing — when it returns non-null the message must also be
 * convertible, so the two counts can't drift apart):
 *  - Literals are adjacency-strict: a literal that follows another literal
 *    must sit at the very NEXT body token. The old forward scan let pattern
 *    "A B" match body "A x y B", so unrelated longer messages "matched" at
 *    view time and then mis-extracted or quarantined at route time.
 *  - A wildcard run stops at its terminator (the next pattern literal), but
 *    terminator occurrences are tried in order with bounded backtracking:
 *    bank bodies repeat currency tokens ("جم", "EGP") inside the variable
 *    region, so the FIRST occurrence is often inside the region itself and
 *    only a later one lets the rest of the pattern align.
 *  - A slot bound to an amount-bearing role (Income/Expense/Transfer/
 *    TransactionFee/CurrentTotal) that would capture NOTHING fails the whole
 *    alignment instead of silently succeeding and quarantining later.
 */
internal fun extractWildcardValues(
    template: SmsTemplate,
    message: SmsMessage,
): Map<WildcardId, String>? {
    val patternTokens = template.pattern.split(Regex("\\s+")).filter { it.isNotBlank() }
    val bodyTokens = message.body.split(Regex("\\s+")).filter { it.isNotBlank() }
    val slotByPosition = template.wildcardSlots.associateBy { it.positionInPattern }

    val failure = AlignFailure()
    val out = alignFrom(patternTokens, bodyTokens, slotByPosition, 0, 0, failure)
    if (out == null) {
        Timber.tag(TRACE).w(
            "ALIGN ✗ %s | patternLen=%d bodyLen=%d tpl=%s",
            failure.reason ?: "empty alignment",
            patternTokens.size,
            bodyTokens.size,
            template.id.value,
        )
    }
    return out?.values
}

/**
 * One body token tagged with the pattern position it aligned to — the
 * display-shaped output of the SAME strict aligner [extractWildcardValues]
 * runs for routing. The preview canvases (mapping screen chip canvas,
 * templates-list example preview) render from this so what the user sees can
 * never disagree with what routing does.
 */
internal sealed interface AlignedDisplayToken {
    val text: String
    val positionInPattern: Int

    /** Body token that matched the pattern literal at [positionInPattern]. */
    data class Literal(
        override val text: String,
        override val positionInPattern: Int,
    ) : AlignedDisplayToken

    /** Body token captured by the wildcard run position [positionInPattern].
     *  Several consecutive tokens may share one position when the run's last
     *  slot absorbs leftover tokens. */
    data class Wildcard(
        override val text: String,
        override val positionInPattern: Int,
    ) : AlignedDisplayToken
}

/**
 * Strict display alignment for the preview canvases. Runs the IDENTICAL
 * backtracking aligner routing uses and tags every CONSUMED body token, in
 * body order, with its pattern position. Returns null exactly when routing
 * would fail to align this body — callers fall back to their own loose
 * rendering for broken/legacy patterns (which routing matches nothing with
 * anyway). Body tokens beyond the aligned prefix are not included; callers
 * render them as plain trailing text.
 */
internal fun alignForDisplay(
    pattern: String,
    body: String,
    slots: List<WildcardSlot>,
): List<AlignedDisplayToken>? {
    val patternTokens = pattern.split(Regex("\\s+")).filter { it.isNotBlank() }
    val bodyTokens = body.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (patternTokens.isEmpty()) return null
    val slotByPosition = slots.associateBy { it.positionInPattern }
    return alignFrom(patternTokens, bodyTokens, slotByPosition, 0, 0, AlignFailure())
        ?.displayTokens
}

/** Upper bound on terminator occurrences tried per wildcard run, so a
 *  pathological body full of repeated literals can't explode the search. */
private const val MAX_TERMINATOR_CANDIDATES = 5

/** Deepest-failure diagnostics. Backtracking retries the same regions, so
 *  intermediate misses must not spam logcat — the deepest reason is recorded
 *  here and logged ONCE by [extractWildcardValues] when nothing aligned. */
private class AlignFailure {
    private var deepestPatternIdx = -1
    var reason: String? = null
        private set

    fun record(patternIdx: Int, reason: String) {
        if (patternIdx >= deepestPatternIdx) {
            deepestPatternIdx = patternIdx
            this.reason = reason
        }
    }
}

/** Working result of [alignFrom]: captured slot values for extraction plus
 *  the per-body-token assignment the display canvases render from. The two
 *  are built by the same walk, so they can't drift apart. */
private class Alignment {
    val values = mutableMapOf<WildcardId, String>()
    val displayTokens = mutableListOf<AlignedDisplayToken>()

    fun absorb(other: Alignment) {
        values.putAll(other.values)
        displayTokens.addAll(other.displayTokens)
    }
}

private fun alignFrom(
    patternTokens: List<String>,
    bodyTokens: List<String>,
    slotByPosition: Map<Int, WildcardSlot>,
    startPatternIdx: Int,
    startBodyIdx: Int,
    failure: AlignFailure,
): Alignment? {
    val out = Alignment()
    var patternIdx = startPatternIdx
    var bodyIdx = startBodyIdx

    while (patternIdx < patternTokens.size) {
        val ptok = patternTokens[patternIdx]
        if (ptok != com.ivy.sms.data.WILDCARD_TOKEN) {
            // Adjacency-strict: a literal not preceded by a wildcard must sit
            // exactly at bodyIdx — no forward scan, no skipped body tokens.
            val bodyTok = bodyTokens.getOrNull(bodyIdx)
            if (bodyTok == null || !bodyTok.equals(ptok, ignoreCase = true)) {
                failure.record(
                    patternIdx,
                    "literal '$ptok' at pattern[$patternIdx] != " +
                        "body[$bodyIdx]='${bodyTok ?: "<end>"}'",
                )
                return null
            }
            out.displayTokens.add(AlignedDisplayToken.Literal(bodyTok, patternIdx))
            bodyIdx++
            patternIdx++
            continue
        }

        // Maximal wildcard run: collect every consecutive <*> in the pattern.
        val runStart = patternIdx
        var runEnd = patternIdx
        while (runEnd + 1 < patternTokens.size &&
            patternTokens[runEnd + 1] == com.ivy.sms.data.WILDCARD_TOKEN
        ) {
            runEnd++
        }

        val terminatorIdx = runEnd + 1
        if (terminatorIdx >= patternTokens.size) {
            // The run is the pattern tail — it absorbs the rest of the body.
            return if (
                captureRun(bodyTokens, slotByPosition, runStart, runEnd, bodyIdx, bodyTokens.size, out, failure)
            ) {
                out
            } else {
                null
            }
        }

        // The terminator may occur INSIDE the variable region (repeated
        // currency tokens are routine in this corpus), so try each occurrence
        // in order until the remainder of the pattern aligns too.
        val terminator = patternTokens[terminatorIdx]
        var searchFrom = bodyIdx
        var attempts = 0
        while (attempts < MAX_TERMINATOR_CANDIDATES) {
            val stopAt = (searchFrom until bodyTokens.size).firstOrNull {
                bodyTokens[it].equals(terminator, ignoreCase = true)
            } ?: break
            attempts++
            val trial = Alignment()
            if (captureRun(bodyTokens, slotByPosition, runStart, runEnd, bodyIdx, stopAt, trial, failure)) {
                val rest = alignFrom(
                    patternTokens = patternTokens,
                    bodyTokens = bodyTokens,
                    slotByPosition = slotByPosition,
                    startPatternIdx = terminatorIdx,
                    startBodyIdx = stopAt,
                    failure = failure,
                )
                if (rest != null) {
                    out.absorb(trial)
                    out.absorb(rest)
                    return out
                }
            }
            searchFrom = stopAt + 1
        }
        failure.record(
            runStart,
            "wildcard-run terminator '$terminator' at pattern[$terminatorIdx] has no " +
                "aligning occurrence in body[$bodyIdx..${bodyTokens.size - 1}] " +
                "(tried $attempts)",
        )
        return null
    }
    return out
}

/**
 * Distribute one wildcard region's body tokens across the run's slots,
 * one per slot in order. The LAST slot absorbs leftover body tokens ONLY if
 * its mapped role expects free text (Merchant, Ignored, Unmapped) — for
 * digit-bearing roles we must not suck trailing Arabic words into the value
 * (the user's "balance not caught" symptom). When a multi-token leftover
 * region lands on an amount-bearing slot ("EGP 502.16" collapsed into ONE
 * slot by a multi-currency merge), the slot takes the first AmountParser-
 * parseable token instead of blindly the first one — "EGP" used to win and
 * quarantine every message of the format. An EMPTY region on an
 * amount-bearing slot fails the capture so the aligner can backtrack (or
 * report an honest alignment failure).
 */
private fun captureRun(
    bodyTokens: List<String>,
    slotByPosition: Map<Int, WildcardSlot>,
    runStart: Int,
    runEnd: Int,
    regionStart: Int,
    regionEnd: Int,
    out: Alignment,
    failure: AlignFailure,
): Boolean {
    val runLength = runEnd - runStart + 1
    val available = bodyTokens.subList(regionStart, regionEnd)
    for (i in 0 until runLength) {
        val position = runStart + i
        val slot = slotByPosition[position]
        if (i >= available.size) {
            if (slot != null && slot.role.isAmountBearing()) {
                failure.record(
                    position,
                    "required ${slot.role} slot at pattern[$position] got an empty region",
                )
                return false
            }
            continue
        }
        val isLast = i == runLength - 1
        val region = if (isLast) available.subList(i, available.size) else listOf(available[i])
        // Display view: every body token in this region belongs to this run
        // position — the canvases render one chip per token, all keyed to the
        // same slot. Recorded even for slot-less `<*>` positions (degenerate
        // patterns) because the body token is consumed positionally anyway.
        for (tok in region) {
            out.displayTokens.add(AlignedDisplayToken.Wildcard(tok, position))
        }
        if (slot == null) continue
        val role = slot.role
        val absorbsLeftover = isLast && (
            role == WildcardRole.Merchant ||
                role == WildcardRole.Ignored ||
                role == WildcardRole.Unmapped
            )
        val tokenForSlot = when {
            absorbsLeftover -> region.joinToString(" ")
            role.isAmountBearing() ->
                region.firstOrNull { AmountParser.parseAmount(it).isRight() } ?: region.first()
            else -> region.first()
        }
        if (tokenForSlot.isNotBlank()) out.values[slot.id] = tokenForSlot
    }
    return true
}

/** Roles whose captured value feeds AmountParser (or is stored as a numeric
 *  string): alignment requires them to actually capture a token. */
private fun WildcardRole.isAmountBearing(): Boolean =
    isAmountRole() || this == WildcardRole.CurrentTotal || this == WildcardRole.TransactionFee

