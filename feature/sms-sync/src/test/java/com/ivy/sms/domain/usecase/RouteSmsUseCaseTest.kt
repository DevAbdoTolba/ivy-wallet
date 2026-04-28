package com.ivy.sms.domain.usecase

import arrow.core.left
import arrow.core.right
import com.ivy.data.model.AccountId
import com.ivy.data.model.TransactionId
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.domain.model.QuarantineReason
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
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.UUID

class RouteSmsUseCaseTest {

    private val createTransaction = mockk<CreateTransactionFromSmsUseCase>()
    private val pendingRepo = mockk<PendingReviewItemRepository>(relaxed = true)
    private val route = RouteSmsUseCase(createTransaction, pendingRepo)

    private fun template(state: TemplateState, hasAmount: Boolean = true): SmsTemplate = SmsTemplate(
        id = SmsTemplateId(UUID.randomUUID()),
        pattern = "test",
        wildcardSlots = if (hasAmount) listOf(
            WildcardSlot(WildcardId(UUID.randomUUID()), 0, "", WildcardMapping.Amount),
        ) else emptyList(),
        state = state,
        classification = TransactionClassification.EXPENSE,
        senderIdHint = "TestBank",
        firstSeen = Instant.EPOCH,
        lastSeen = Instant.EPOCH,
        matchCount = 1,
    )

    private val message = SmsMessage(
        dedupKey = "k",
        senderId = "TestBank",
        body = "test",
        timestamp = Instant.EPOCH,
    )

    @Test
    fun blacklisted_shortCircuits_beforeTier1() = runTest {
        val tpl = template(TemplateState.BLACKLISTED)
        val account = AccountId(UUID.randomUUID())

        val outcome = route(message, tpl, mapOf("TestBank" to account))

        outcome.getOrNull().shouldBeInstanceOf<RouteOutcome.Blacklisted>()
        coVerify(exactly = 0) { createTransaction(any(), any(), any()) }
        coVerify(exactly = 0) { pendingRepo.enqueue(any()) }
    }

    @Test
    fun unmappedTemplate_quarantines_withTemplateNotMapped() = runTest {
        val tpl = template(TemplateState.UNMAPPED)
        val account = AccountId(UUID.randomUUID())
        coEvery { pendingRepo.enqueue(any()) } returns Unit.right()

        val outcome = route(message, tpl, mapOf("TestBank" to account)).getOrNull()

        outcome.shouldBeInstanceOf<RouteOutcome.Quarantined>()
        (outcome as RouteOutcome.Quarantined).reason shouldBe QuarantineReason.TEMPLATE_NOT_MAPPED
    }

    @Test
    fun activeMappedTemplate_createsTransaction() = runTest {
        val tpl = template(TemplateState.ACTIVE)
        val account = AccountId(UUID.randomUUID())
        val txId = TransactionId(UUID.randomUUID())
        coEvery { createTransaction(message, tpl, account) } returns txId.right()

        val outcome = route(message, tpl, mapOf("TestBank" to account)).getOrNull()

        outcome.shouldBeInstanceOf<RouteOutcome.Created>()
        (outcome as RouteOutcome.Created).transactionId shouldBe txId
    }

    @Test
    fun activeButAmountFails_quarantines_withAmountNotParseable() = runTest {
        val tpl = template(TemplateState.ACTIVE)
        val account = AccountId(UUID.randomUUID())
        coEvery { createTransaction(message, tpl, account) } returns "AMOUNT_NOT_PARSEABLE:bad".left()
        coEvery { pendingRepo.enqueue(any()) } returns Unit.right()

        val outcome = route(message, tpl, mapOf("TestBank" to account)).getOrNull()

        outcome.shouldBeInstanceOf<RouteOutcome.Quarantined>()
        (outcome as RouteOutcome.Quarantined).reason shouldBe QuarantineReason.AMOUNT_NOT_PARSEABLE
    }
}
