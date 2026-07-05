package com.ivy.sms.domain.usecase

import arrow.core.left
import arrow.core.right
import com.ivy.data.model.AccountId
import com.ivy.data.model.TransactionId
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SmsWatermarkPreferences
import com.ivy.sms.domain.model.PendingReviewItem
import com.ivy.sms.domain.model.QuarantineReason
import com.ivy.sms.domain.model.SmsMessage
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.domain.model.WildcardSlot
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * FR-007/FR-009 routing behavior: a Transfer-role match without a resolvable
 * destination must QUARANTINE as TRANSFER_NEEDS_DESTINATION — never commit,
 * never fall back to the source wallet (the pre-136 ghost bug).
 */
class RouteTransferDestinationTest {

    private val createTransaction = mockk<CreateTransactionFromSmsUseCase>()
    private val pendingRepo = mockk<PendingReviewItemRepository>()
    private val prefs = mockk<SmsWatermarkPreferences>()

    private val route = RouteSmsUseCase(
        createTransaction = createTransaction,
        pendingRepo = pendingRepo,
        prefs = prefs,
    )

    private val accountId = AccountId(UUID.randomUUID())
    private val template = SmsTemplate(
        id = SmsTemplateId(UUID.randomUUID()),
        pattern = "You transferred <*> EGP",
        exampleBody = "You transferred 500.00 EGP",
        wildcardSlots = listOf(
            WildcardSlot(
                id = WildcardId(UUID.randomUUID()),
                positionInPattern = 2,
                contextSnippet = "",
                exampleValue = "500.00",
                role = WildcardRole.Transfer,
            ),
        ),
        state = TemplateState.ACTIVE,
        senderIdHint = "CIB",
        firstSeen = Instant.EPOCH,
        lastSeen = Instant.EPOCH,
        matchCount = 1,
    )
    private val message = SmsMessage(
        dedupKey = "key-1",
        senderId = "CIB",
        body = "You transferred 500.00 EGP",
        timestamp = Instant.EPOCH,
    )

    @Test
    fun `missing destination quarantines as TRANSFER_NEEDS_DESTINATION`() = runTest {
        coEvery { prefs.autoRouteEnabled("CIB") } returns true.right()
        coEvery {
            createTransaction(message, template, accountId, null)
        } returns "TRANSFER_NEEDS_DESTINATION:no destination wallet set".left()
        val enqueued = slot<PendingReviewItem>()
        coEvery { pendingRepo.enqueue(capture(enqueued)) } returns Unit.right()

        val outcome = route(
            message = message,
            template = template,
            senderLinks = mapOf("CIB" to accountId),
        )

        outcome shouldBe RouteOutcome.Quarantined(QuarantineReason.TRANSFER_NEEDS_DESTINATION).right()
        enqueued.captured.quarantineReason shouldBe QuarantineReason.TRANSFER_NEEDS_DESTINATION
    }

    @Test
    fun `destination override threads through to transaction creation`() = runTest {
        val destination = AccountId(UUID.randomUUID())
        val txId = TransactionId(UUID.randomUUID())
        coEvery {
            createTransaction(message, template, accountId, destination)
        } returns txId.right()

        val outcome = route(
            message = message,
            template = template,
            senderLinks = mapOf("CIB" to accountId),
            userInitiated = true,
            transferDestinationOverride = destination,
        )

        outcome shouldBe RouteOutcome.Created(txId).right()
        coVerify(exactly = 1) { createTransaction(message, template, accountId, destination) }
        coVerify(exactly = 0) { pendingRepo.enqueue(any()) }
    }
}
