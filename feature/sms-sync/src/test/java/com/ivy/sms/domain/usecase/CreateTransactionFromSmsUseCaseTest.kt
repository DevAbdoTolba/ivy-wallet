package com.ivy.sms.domain.usecase

import arrow.core.right
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.Expense
import com.ivy.data.model.Income
import com.ivy.data.model.Transaction
import com.ivy.data.model.TransactionId
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.ColorInt
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.TransactionRepository
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.domain.model.SmsMessage
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.domain.model.WildcardSlot
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

class CreateTransactionFromSmsUseCaseTest {

    private val accountRepo = mockk<AccountRepository>()
    private val transactionRepo = mockk<TransactionRepository>()
    private val pendingRepo = mockk<PendingReviewItemRepository>()
    private val useCase = CreateTransactionFromSmsUseCase(accountRepo, transactionRepo, pendingRepo)

    private val accountId = AccountId(UUID.randomUUID())

    init {
        // Default: no transaction imported from this SMS yet, pending twin
        // removal succeeds quietly. Dedup-specific tests override these.
        coEvery { transactionRepo.findIdBySmsSourceDedupKey(any()) } returns null
        coEvery { pendingRepo.dismissByDedupKey(any()) } returns Unit.right()
    }

    private suspend fun stubUsdAccount() {
        coEvery { accountRepo.findById(accountId) } returns Account(
            id = accountId,
            name = NotBlankTrimmedString.from("USD Checking").getOrNull()!!,
            asset = AssetCode.from("USD").getOrNull()!!,
            color = ColorInt(0xFF000000.toInt()),
            icon = null,
            includeInBalance = true,
            orderNum = 0.0,
        )
    }

    @Test
    fun expenseRole_populatesSmsMetadata_andUsesWalletCurrencyNotSmsCurrency() = runTest {
        val tplId = SmsTemplateId(UUID.randomUUID())
        val amountWild = WildcardId(UUID.randomUUID())
        val template = SmsTemplate(
            id = tplId,
            pattern = "Spent <*> EUR",
            exampleBody = "Spent 12.50 EUR",
            wildcardSlots = listOf(
                WildcardSlot(amountWild, 1, "Spent <*>", "12.50", WildcardRole.Expense),
            ),
            state = TemplateState.ACTIVE,
            senderIdHint = "TestBank",
            firstSeen = Instant.EPOCH,
            lastSeen = Instant.EPOCH,
            matchCount = 1,
        )
        val message = SmsMessage(
            dedupKey = "deduphash",
            senderId = "TestBank",
            body = "Spent 12.50 EUR",
            timestamp = Instant.ofEpochMilli(1_700_000_000_000L),
        )

        stubUsdAccount()
        val savedSlot = slot<Transaction>()
        coJustRun { transactionRepo.save(capture(savedSlot)) }

        useCase(message, template, accountId)

        coVerify { transactionRepo.save(any()) }
        savedSlot.captured.shouldBeInstanceOf<Expense>()
        val expense = savedSlot.captured as Expense
        expense.metadata.smsSourceDedupKey shouldBe "deduphash"
        expense.metadata.smsTemplateId shouldBe tplId.value
        expense.metadata.smsSourceSenderId shouldBe "TestBank"
        expense.value.asset.code shouldBe "USD"
    }

    @Test
    fun incomeRole_producesIncomeTransaction() = runTest {
        val tplId = SmsTemplateId(UUID.randomUUID())
        val amountWild = WildcardId(UUID.randomUUID())
        val template = SmsTemplate(
            id = tplId,
            pattern = "Received <*>",
            exampleBody = "Received 100",
            wildcardSlots = listOf(
                WildcardSlot(amountWild, 1, "", "100", WildcardRole.Income),
            ),
            state = TemplateState.ACTIVE,
            senderIdHint = "TestBank",
            firstSeen = Instant.EPOCH,
            lastSeen = Instant.EPOCH,
            matchCount = 1,
        )
        val message = SmsMessage("k", "TestBank", "Received 100", Instant.EPOCH)

        stubUsdAccount()
        val saved = slot<Transaction>()
        coJustRun { transactionRepo.save(capture(saved)) }

        useCase(message, template, accountId)

        saved.captured.shouldBeInstanceOf<Income>()
    }

    @Test
    fun currentTotalAndFee_areStoredOnMetadata() = runTest {
        val tplId = SmsTemplateId(UUID.randomUUID())
        val amount = WildcardId(UUID.randomUUID())
        val balance = WildcardId(UUID.randomUUID())
        val fee = WildcardId(UUID.randomUUID())
        val template = SmsTemplate(
            id = tplId,
            pattern = "Spent <*> bal <*> fee <*>",
            exampleBody = "Spent 12 bal 100 fee 1",
            wildcardSlots = listOf(
                WildcardSlot(amount, 1, "", "12", WildcardRole.Expense),
                WildcardSlot(balance, 3, "", "100", WildcardRole.CurrentTotal),
                WildcardSlot(fee, 5, "", "1", WildcardRole.TransactionFee),
            ),
            state = TemplateState.ACTIVE,
            senderIdHint = "TestBank",
            firstSeen = Instant.EPOCH,
            lastSeen = Instant.EPOCH,
            matchCount = 1,
        )
        val message = SmsMessage(
            dedupKey = "k",
            senderId = "TestBank",
            body = "Spent 12 bal 100 fee 1",
            timestamp = Instant.EPOCH,
        )

        stubUsdAccount()
        val saved = slot<Transaction>()
        coJustRun { transactionRepo.save(capture(saved)) }

        useCase(message, template, accountId)

        saved.captured.metadata.smsCurrentTotal shouldBe "100"
        saved.captured.metadata.smsTransactionFee shouldBe "1"
    }

    // --- extractWildcardValues: strict alignment ---

    private fun template(pattern: String, slots: List<WildcardSlot>) = SmsTemplate(
        id = SmsTemplateId(UUID.randomUUID()),
        pattern = pattern,
        exampleBody = pattern,
        wildcardSlots = slots,
        state = TemplateState.ACTIVE,
        senderIdHint = "TestBank",
        firstSeen = Instant.EPOCH,
        lastSeen = Instant.EPOCH,
        matchCount = 1,
    )

    private fun expenseSlot(position: Int) = WildcardSlot(
        id = WildcardId(UUID.randomUUID()),
        positionInPattern = position,
        contextSnippet = "",
        exampleValue = "100",
        role = WildcardRole.Expense,
    )

    @Test
    fun alignment_failsWhenAdjacentLiteralsHaveInterveningBodyTokens() {
        val template = template("Debit <*> EGP done", listOf(expenseSlot(1)))
        // "EGP done" are ADJACENT pattern literals — "done" must sit right
        // after "EGP"; the old subsequence scan skipped "extra junk" and
        // counted unrelated longer messages as matches.
        val message = SmsMessage("k", "TestBank", "Debit 100 EGP extra junk done", Instant.EPOCH)

        extractWildcardValues(template, message) shouldBe null
    }

    @Test
    fun alignment_backtracksWhenWildcardRegionContainsTheTerminatorLiteral() {
        val amountSlot = expenseSlot(1)
        val template = template("paid <*> EGP only", listOf(amountSlot))
        // The variable region itself contains "EGP" — stopping at the FIRST
        // occurrence leaves "extra 5 EGP only" unmatched, so the aligner
        // must retry with the next occurrence.
        val message = SmsMessage("k", "TestBank", "paid 100 EGP extra 5 EGP only", Instant.EPOCH)

        val values = extractWildcardValues(template, message)

        values shouldNotBe null
        values!![amountSlot.id] shouldBe "100"
    }

    @Test
    fun alignment_failsWhenAmountSlotRegionIsEmpty() {
        val template = template("paid <*> EGP", listOf(expenseSlot(1)))
        // No token between "paid" and "EGP" — the amount slot would be blank.
        // Empty amount = alignment failure, not a success that quarantines.
        val message = SmsMessage("k", "TestBank", "paid EGP", Instant.EPOCH)

        extractWildcardValues(template, message) shouldBe null
    }

    @Test
    fun alignment_failsWhenFeeSlotRegionIsEmpty() {
        val feeSlot = WildcardSlot(
            id = WildcardId(UUID.randomUUID()),
            positionInPattern = 3,
            contextSnippet = "",
            exampleValue = "1",
            role = WildcardRole.TransactionFee,
        )
        val template = template("paid <*> fee <*> end", listOf(expenseSlot(1), feeSlot))
        val message = SmsMessage("k", "TestBank", "paid 100 fee end", Instant.EPOCH)

        extractWildcardValues(template, message) shouldBe null
    }

    @Test
    fun amountSlot_overMultiTokenRegion_picksTheParseableToken() {
        val amountSlot = expenseSlot(1)
        val template = template("paid <*> at", listOf(amountSlot))
        // Multi-currency clusters collapse currency+amount into ONE slot —
        // the region's first token is "EGP", the parseable one is "502.16".
        val message = SmsMessage("k", "TestBank", "paid EGP 502.16 at", Instant.EPOCH)

        val values = extractWildcardValues(template, message)

        values shouldNotBe null
        values!![amountSlot.id] shouldBe "502.16"
    }

    @Test
    fun balanceSlot_atPatternTail_doesNotAbsorbTrailingWords() {
        val balanceSlot = WildcardSlot(
            id = WildcardId(UUID.randomUUID()),
            positionInPattern = 1,
            contextSnippet = "",
            exampleValue = "502.16.",
            role = WildcardRole.CurrentTotal,
        )
        val template = template("bal <*>", listOf(balanceSlot))
        val message = SmsMessage("k", "TestBank", "bal 502.16. tabe3 hesabak", Instant.EPOCH)

        val values = extractWildcardValues(template, message)

        values shouldNotBe null
        values!![balanceSlot.id] shouldBe "502.16."
    }

    // --- date/time assembly ---

    private fun dateTemplate(pattern: String, vararg slots: WildcardSlot) =
        template(pattern, slots.toList())

    private fun dateSlot(position: Int, role: WildcardRole) = WildcardSlot(
        id = WildcardId(UUID.randomUUID()),
        positionInPattern = position,
        contextSnippet = "",
        exampleValue = "",
        role = role,
    )

    @Test
    fun dateOnlyAndTimeOnly_combineIntoOneTimestamp() = runTest {
        val template = dateTemplate(
            "Spent <*> on <*> at <*>",
            expenseSlot(1),
            dateSlot(3, WildcardRole.DateOnly),
            dateSlot(5, WildcardRole.TimeOnly),
        )
        val message = SmsMessage(
            dedupKey = "k",
            senderId = "TestBank",
            body = "Spent 50 on 27/04/2026 at 14:30",
            timestamp = Instant.ofEpochMilli(1_700_000_000_000L),
        )

        stubUsdAccount()
        val saved = slot<Transaction>()
        coJustRun { transactionRepo.save(capture(saved)) }

        useCase(message, template, accountId)

        val expected = LocalDateTime.of(2026, 4, 27, 14, 30)
            .atZone(ZoneId.systemDefault())
            .toInstant()
        saved.captured.time shouldBe expected
    }

    @Test
    fun dateOnlyAlone_usesMessageTimeOfDay_notMidnight() = runTest {
        val template = dateTemplate(
            "Spent <*> on <*>",
            expenseSlot(1),
            dateSlot(3, WildcardRole.DateOnly),
        )
        val msgTimestamp = Instant.ofEpochMilli(1_700_000_000_000L)
        val message = SmsMessage("k", "TestBank", "Spent 50 on 27/04/2026", msgTimestamp)

        stubUsdAccount()
        val saved = slot<Transaction>()
        coJustRun { transactionRepo.save(capture(saved)) }

        useCase(message, template, accountId)

        val zone = ZoneId.systemDefault()
        val expected = LocalDateTime.of(
            LocalDate.of(2026, 4, 27),
            msgTimestamp.atZone(zone).toLocalTime(),
        ).atZone(zone).toInstant()
        saved.captured.time shouldBe expected
    }

    @Test
    fun timeOnlyAlone_usesMessageDate_withParsedTime() = runTest {
        val template = dateTemplate(
            "Spent <*> at <*>",
            expenseSlot(1),
            dateSlot(3, WildcardRole.TimeOnly),
        )
        val msgTimestamp = Instant.ofEpochMilli(1_700_000_000_000L)
        val message = SmsMessage("k", "TestBank", "Spent 50 at 09:15", msgTimestamp)

        stubUsdAccount()
        val saved = slot<Transaction>()
        coJustRun { transactionRepo.save(capture(saved)) }

        useCase(message, template, accountId)

        val zone = ZoneId.systemDefault()
        val expected = LocalDateTime.of(
            msgTimestamp.atZone(zone).toLocalDate(),
            LocalTime.of(9, 15),
        ).atZone(zone).toInstant()
        saved.captured.time shouldBe expected
    }

    // --- transaction-level dedup guard ---

    private fun dedupTemplate() = SmsTemplate(
        id = SmsTemplateId(UUID.randomUUID()),
        pattern = "Spent <*> EUR",
        exampleBody = "Spent 12.50 EUR",
        wildcardSlots = listOf(
            WildcardSlot(WildcardId(UUID.randomUUID()), 1, "", "12.50", WildcardRole.Expense),
        ),
        state = TemplateState.ACTIVE,
        senderIdHint = "TestBank",
        firstSeen = Instant.EPOCH,
        lastSeen = Instant.EPOCH,
        matchCount = 1,
    )

    private val dedupMessage = SmsMessage(
        dedupKey = "deduphash",
        senderId = "TestBank",
        body = "Spent 12.50 EUR",
        timestamp = Instant.EPOCH,
    )

    @Test
    fun sameMessageRoutedTwice_scanThenDrain_savesExactlyOneTransaction() = runTest {
        val template = dedupTemplate()
        stubUsdAccount()
        // Simulate the real repository: the dedup lookup misses until the
        // first save persists the metadata key.
        var importedId: TransactionId? = null
        coEvery { transactionRepo.findIdBySmsSourceDedupKey("deduphash") } answers { importedId }
        val saved = slot<Transaction>()
        coEvery { transactionRepo.save(capture(saved)) } answers { importedId = saved.captured.id }

        val first = useCase(dedupMessage, template, accountId).getOrNull()!!
        val second = useCase(dedupMessage, template, accountId).getOrNull()!!

        second shouldBe first
        coVerify(exactly = 1) { transactionRepo.save(any()) }
    }

    @Test
    fun dedupHit_returnsExistingId_withoutSaving_andClearsPendingTwin() = runTest {
        val existing = TransactionId(UUID.randomUUID())
        coEvery { transactionRepo.findIdBySmsSourceDedupKey("deduphash") } returns existing

        val result = useCase(dedupMessage, dedupTemplate(), accountId)

        result.getOrNull() shouldBe existing
        coVerify(exactly = 0) { transactionRepo.save(any()) }
        coVerify { pendingRepo.dismissByDedupKey("deduphash") }
    }

    @Test
    fun createdOutcome_clearsPendingTwin_byDedupKey() = runTest {
        stubUsdAccount()
        coJustRun { transactionRepo.save(any()) }

        useCase(dedupMessage, dedupTemplate(), accountId)

        coVerify { pendingRepo.dismissByDedupKey("deduphash") }
    }
}
