package com.ivy.sms.domain.usecase

import arrow.core.right
import com.ivy.base.TestDispatchersProvider
import com.ivy.data.model.AccountId
import com.ivy.data.model.Expense
import com.ivy.data.model.PositiveValue
import com.ivy.data.model.Transaction
import com.ivy.data.model.TransactionId
import com.ivy.data.model.TransactionMetadata
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.PositiveDouble
import com.ivy.data.repository.TransactionRepository
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.data.SmsInboxDataSource
import com.ivy.sms.data.SmsMessageMapper
import com.ivy.sms.data.SmsRow
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.data.SmsWatermarkPreferences
import com.ivy.sms.domain.model.SenderAccountLink
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.domain.model.WildcardSlot
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.UUID

class ReprocessHistoricalUseCaseTest {

    private val templateRepo = mockk<SmsTemplateRepository>()
    private val inbox = mockk<SmsInboxDataSource>()
    private val watermarks = mockk<SmsWatermarkPreferences>()
    private val senderRepo = mockk<SenderAccountLinkRepository>()
    private val transactionRepo = mockk<TransactionRepository>()
    private val smsMessageMapper = SmsMessageMapper()
    private val route = mockk<RouteSmsUseCase>(relaxed = true)

    private val useCase = ReprocessHistoricalUseCase(
        templateRepo = templateRepo,
        inbox = inbox,
        watermarks = watermarks,
        senderRepo = senderRepo,
        transactionRepo = transactionRepo,
        smsMessageMapper = smsMessageMapper,
        route = route,
        dispatchers = TestDispatchersProvider,
    )

    @Test
    fun confirm_rejectsInvalidToken() = runTest {
        val tplId = SmsTemplateId(UUID.randomUUID())
        coEvery { templateRepo.findById(tplId) } returns activeTemplate(tplId).right()

        val result = useCase.confirm(tplId, "wrong-token")

        result.leftOrNull()!! shouldStartWith "VALIDATION"
    }

    @Test
    fun preview_excludesAlreadyImportedDedupKeys() = runTest {
        val tplId = SmsTemplateId(UUID.randomUUID())
        coEvery { templateRepo.findById(tplId) } returns activeTemplate(tplId).right()
        coEvery { watermarks.scanLowerBound() } returns 0L.right()
        coEvery { inbox.read(any(), any(), any()) } returns emptyList<SmsRow>().right()
        coEvery { transactionRepo.findAll() } returns emptyList()

        val result = useCase.preview(tplId).getOrNull()!!

        result.matchingMessages shouldBe 0
        result.newTransactionsToCreate shouldBe 0
    }

    @Test
    fun preview_countsOnlyAligningRows_readFromTheTemplatesSenderOnly() = runTest {
        val tplId = SmsTemplateId(UUID.randomUUID())
        coEvery { templateRepo.findById(tplId) } returns activeTemplate(tplId).right()
        coEvery { watermarks.scanLowerBound() } returns 0L.right()
        // Stubbed with the exact sender filter — a read without it (the old
        // whole-inbox behavior) finds no answer and fails the test.
        coEvery { inbox.read(0L, 0L, "TestBank") } returns listOf(aligningRow, nonAligningRow).right()
        coEvery { transactionRepo.findAll() } returns emptyList()

        val result = useCase.preview(tplId).getOrNull()!!

        result.matchingMessages shouldBe 1
        result.newTransactionsToCreate shouldBe 1
    }

    @Test
    fun preview_excludesImportedMessages_fromTheNewCount() = runTest {
        val tplId = SmsTemplateId(UUID.randomUUID())
        coEvery { templateRepo.findById(tplId) } returns activeTemplate(tplId).right()
        coEvery { watermarks.scanLowerBound() } returns 0L.right()
        coEvery { inbox.read(0L, 0L, "TestBank") } returns listOf(aligningRow).right()
        val importedKey = with(smsMessageMapper) { aligningRow.toDomain() }.dedupKey
        coEvery { transactionRepo.findAll() } returns listOf(importedExpense(importedKey))

        val result = useCase.preview(tplId).getOrNull()!!

        result.matchingMessages shouldBe 1
        result.newTransactionsToCreate shouldBe 0
    }

    @Test
    fun confirm_skipsNonAligningRows_insteadOfQuarantining() = runTest {
        val tplId = SmsTemplateId(UUID.randomUUID())
        coEvery { templateRepo.findById(tplId) } returns activeTemplate(tplId).right()
        coEvery { watermarks.scanLowerBound() } returns 0L.right()
        coEvery { inbox.read(0L, 0L, "TestBank") } returns listOf(aligningRow, nonAligningRow).right()
        coEvery { transactionRepo.findAll() } returns emptyList()
        coEvery { senderRepo.findAll() } returns emptyList<SenderAccountLink>().right()
        coEvery { route(any(), any(), any(), any()) } returns
            RouteOutcome.Created(TransactionId(UUID.randomUUID())).right()

        val result = useCase.confirm(tplId, tplId.value.toString()).getOrNull()!!

        result.transactionsCreated shouldBe 1
        result.itemsQuarantined shouldBe 0
        // The non-aligning OTP row must never reach routing (= quarantine).
        // Confirm is an explicit user action — it bypasses the per-sender
        // auto-route gate via userInitiated = true.
        coVerify(exactly = 1) { route(any(), any(), any(), userInitiated = true) }
        coVerify { route(match { it.body == "paid 100 EGP" }, any(), any(), any()) }
    }

    @Test
    fun confirm_skipsAlreadyImportedMessages() = runTest {
        val tplId = SmsTemplateId(UUID.randomUUID())
        coEvery { templateRepo.findById(tplId) } returns activeTemplate(tplId).right()
        coEvery { watermarks.scanLowerBound() } returns 0L.right()
        coEvery { inbox.read(0L, 0L, "TestBank") } returns listOf(aligningRow).right()
        val importedKey = with(smsMessageMapper) { aligningRow.toDomain() }.dedupKey
        coEvery { transactionRepo.findAll() } returns listOf(importedExpense(importedKey))
        coEvery { senderRepo.findAll() } returns emptyList<SenderAccountLink>().right()

        val result = useCase.confirm(tplId, tplId.value.toString()).getOrNull()!!

        result.transactionsCreated shouldBe 0
        result.itemsQuarantined shouldBe 0
        coVerify(exactly = 0) { route(any(), any(), any(), any()) }
    }

    private val aligningRow = SmsRow(
        id = 1L,
        address = "TestBank",
        dateEpochMillis = 1_000L,
        body = "paid 100 EGP",
    )

    private val nonAligningRow = SmsRow(
        id = 2L,
        address = "TestBank",
        dateEpochMillis = 2_000L,
        body = "your OTP is 1234",
    )

    private fun activeTemplate(id: SmsTemplateId): SmsTemplate = SmsTemplate(
        id = id,
        pattern = "paid <*> EGP",
        exampleBody = "paid 100 EGP",
        wildcardSlots = listOf(
            WildcardSlot(
                id = WildcardId(UUID.randomUUID()),
                positionInPattern = 1,
                contextSnippet = "",
                exampleValue = "100",
                role = WildcardRole.Expense,
            ),
        ),
        state = TemplateState.ACTIVE,
        senderIdHint = "TestBank",
        firstSeen = Instant.EPOCH,
        lastSeen = Instant.EPOCH,
        matchCount = 1,
    )

    private fun importedExpense(dedupKey: String): Transaction = Expense(
        id = TransactionId(UUID.randomUUID()),
        title = null,
        description = null,
        category = null,
        time = Instant.EPOCH,
        settled = true,
        metadata = TransactionMetadata(
            recurringRuleId = null,
            paidForDateTime = null,
            loanId = null,
            loanRecordId = null,
            smsSourceDedupKey = dedupKey,
        ),
        tags = emptyList(),
        value = PositiveValue(
            amount = PositiveDouble.from(1.0).getOrNull()!!,
            asset = AssetCode.from("USD").getOrNull()!!,
        ),
        account = AccountId(UUID.randomUUID()),
    )
}
