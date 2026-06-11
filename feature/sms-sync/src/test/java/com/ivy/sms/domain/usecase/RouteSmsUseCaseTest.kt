package com.ivy.sms.domain.usecase

import arrow.core.left
import arrow.core.right
import com.ivy.data.model.AccountId
import com.ivy.data.model.TransactionId
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SmsWatermarkPreferences
import com.ivy.sms.domain.model.QuarantineReason
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
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.UUID

class RouteSmsUseCaseTest {

    private val createTransaction = mockk<CreateTransactionFromSmsUseCase>()
    private val pendingRepo = mockk<PendingReviewItemRepository>(relaxed = true)
    private val prefs = mockk<SmsWatermarkPreferences> {
        coEvery { autoRouteEnabled(any()) } returns true.right()
    }
    private val route = RouteSmsUseCase(createTransaction, pendingRepo, prefs)

    private fun template(state: TemplateState, hasAmount: Boolean = true): SmsTemplate = SmsTemplate(
        id = SmsTemplateId(UUID.randomUUID()),
        pattern = "test",
        exampleBody = "test",
        wildcardSlots = if (hasAmount) listOf(
            WildcardSlot(
                id = WildcardId(UUID.randomUUID()),
                positionInPattern = 0,
                contextSnippet = "",
                exampleValue = "12.34",
                role = WildcardRole.Expense,
            ),
        ) else emptyList(),
        state = state,
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

    @Test
    fun activeButNoAmountRole_quarantines() = runTest {
        val tpl = template(TemplateState.ACTIVE, hasAmount = false)
        val account = AccountId(UUID.randomUUID())
        coEvery { pendingRepo.enqueue(any()) } returns Unit.right()

        val outcome = route(message, tpl, mapOf("TestBank" to account)).getOrNull()

        outcome.shouldBeInstanceOf<RouteOutcome.Quarantined>()
        coVerify(exactly = 0) { createTransaction(any(), any(), any()) }
    }

    @Test
    fun autoRouteOff_quarantinesTier1Match_insteadOfCreating() = runTest {
        val tpl = template(TemplateState.ACTIVE)
        val account = AccountId(UUID.randomUUID())
        coEvery { prefs.autoRouteEnabled("TestBank") } returns false.right()
        coEvery { pendingRepo.enqueue(any()) } returns Unit.right()

        val outcome = route(message, tpl, mapOf("TestBank" to account)).getOrNull()

        outcome.shouldBeInstanceOf<RouteOutcome.Quarantined>()
        (outcome as RouteOutcome.Quarantined).reason shouldBe QuarantineReason.AUTO_ROUTE_DISABLED
        coVerify(exactly = 0) { createTransaction(any(), any(), any()) }
    }

    @Test
    fun autoRouteOff_userInitiatedRouting_stillCreates() = runTest {
        val tpl = template(TemplateState.ACTIVE)
        val account = AccountId(UUID.randomUUID())
        val txId = TransactionId(UUID.randomUUID())
        coEvery { prefs.autoRouteEnabled("TestBank") } returns false.right()
        coEvery { createTransaction(message, tpl, account) } returns txId.right()

        val outcome = route(
            message, tpl, mapOf("TestBank" to account), userInitiated = true,
        ).getOrNull()

        outcome.shouldBeInstanceOf<RouteOutcome.Created>()
        (outcome as RouteOutcome.Created).transactionId shouldBe txId
    }

    @Test
    fun autoRoutePrefReadFails_defaultsToCreating() = runTest {
        val tpl = template(TemplateState.ACTIVE)
        val account = AccountId(UUID.randomUUID())
        val txId = TransactionId(UUID.randomUUID())
        coEvery { prefs.autoRouteEnabled("TestBank") } returns "STORAGE_ERROR:io".left()
        coEvery { createTransaction(message, tpl, account) } returns txId.right()

        val outcome = route(message, tpl, mapOf("TestBank" to account)).getOrNull()

        outcome.shouldBeInstanceOf<RouteOutcome.Created>()
    }
}
