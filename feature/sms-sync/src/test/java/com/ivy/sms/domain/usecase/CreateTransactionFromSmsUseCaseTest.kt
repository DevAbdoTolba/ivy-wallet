package com.ivy.sms.domain.usecase

import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.Expense
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
import com.ivy.sms.domain.model.TransactionClassification
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardMapping
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

    @Test
    fun populatesSmsMetadata_andUsesWalletCurrencyNotSmsCurrency() = runTest {
        val tplId = SmsTemplateId(UUID.randomUUID())
        val amountWild = WildcardId(UUID.randomUUID())
        val template = SmsTemplate(
            id = tplId,
            pattern = "Spent <*> EUR",
            wildcardSlots = listOf(
                WildcardSlot(amountWild, 1, "Spent <*>", WildcardMapping.Amount),
            ),
            state = TemplateState.ACTIVE,
            classification = TransactionClassification.EXPENSE,
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

        coEvery { accountRepo.findById(accountId) } returns Account(
            id = accountId,
            name = NotBlankTrimmedString.from("USD Checking").getOrNull()!!,
            asset = AssetCode.from("USD").getOrNull()!!,
            color = ColorInt(0xFF000000.toInt()),
            icon = null,
            includeInBalance = true,
            orderNum = 0.0,
        )
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
}
