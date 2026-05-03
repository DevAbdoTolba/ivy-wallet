package com.ivy.sms.domain.usecase

import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.Expense
import com.ivy.data.model.Income
import com.ivy.data.model.Transaction
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.ColorInt
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.repository.AccountRepository
import com.ivy.data.repository.TransactionRepository
import com.ivy.sms.domain.model.SmsMessage
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.domain.model.WildcardSlot
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.UUID

class CreateTransactionFromSmsUseCaseTest {

    private val accountRepo = mockk<AccountRepository>()
    private val transactionRepo = mockk<TransactionRepository>()
    private val useCase = CreateTransactionFromSmsUseCase(accountRepo, transactionRepo)

    private val accountId = AccountId(UUID.randomUUID())

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
}
