package com.ivy.sms.domain.usecase

import arrow.core.right
import com.ivy.data.model.AccountId
import com.ivy.data.model.TransactionId
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.domain.model.PendingReviewItem
import com.ivy.sms.domain.model.PendingReviewItemId
import com.ivy.sms.domain.model.QuarantineReason
import com.ivy.sms.domain.model.SenderAccountLink
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
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.UUID

class ResolvePendingItemUseCaseTest {

    private val pendingRepo = mockk<PendingReviewItemRepository>(relaxed = true)
    private val templateRepo = mockk<SmsTemplateRepository>()
    private val senderRepo = mockk<SenderAccountLinkRepository>()
    private val route = mockk<RouteSmsUseCase>()

    private val useCase = ResolvePendingItemUseCase(pendingRepo, templateRepo, senderRepo, route)

    private val tplId = SmsTemplateId(UUID.randomUUID())
    private val activeTemplate = SmsTemplate(
        id = tplId,
        pattern = "test",
        exampleBody = "test",
        wildcardSlots = listOf(
            WildcardSlot(
                id = WildcardId(UUID.randomUUID()),
                positionInPattern = 0,
                contextSnippet = "",
                exampleValue = "",
                role = WildcardRole.Expense,
            ),
        ),
        state = TemplateState.ACTIVE,
        senderIdHint = "TestBank",
        firstSeen = Instant.EPOCH,
        lastSeen = Instant.EPOCH,
        matchCount = 1,
    )

    @Test
    fun dismiss_removesOnlyTargetedItem() = runTest {
        val itemId = PendingReviewItemId(UUID.randomUUID())
        coEvery { pendingRepo.dismiss(itemId) } returns Unit.right()

        useCase.dismiss(itemId)

        coVerify(exactly = 1) { pendingRepo.dismiss(itemId) }
    }

    @Test
    fun convert_dispatchesRoutingForEachQueuedItem() = runTest {
        val item1 = PendingReviewItem(
            id = PendingReviewItemId(UUID.randomUUID()),
            sms = SmsMessage("k1", "TestBank", "b1", Instant.EPOCH),
            template = activeTemplate,
            quarantineReason = QuarantineReason.TEMPLATE_NOT_MAPPED,
            enqueuedAt = Instant.EPOCH,
        )
        val item2 = PendingReviewItem(
            id = PendingReviewItemId(UUID.randomUUID()),
            sms = SmsMessage("k2", "TestBank", "b2", Instant.EPOCH),
            template = activeTemplate,
            quarantineReason = QuarantineReason.TEMPLATE_NOT_MAPPED,
            enqueuedAt = Instant.EPOCH,
        )
        coEvery { templateRepo.findById(tplId) } returns activeTemplate.right()
        coEvery { pendingRepo.findByTemplateId(tplId) } returns listOf(item1, item2).right()
        coEvery { senderRepo.findAll() } returns listOf(
            SenderAccountLink("TestBank", AccountId(UUID.randomUUID()), Instant.EPOCH),
        ).right()
        coEvery { route(any(), any(), any(), any()) } returns RouteOutcome.Created(TransactionId(UUID.randomUUID())).right()

        val result = useCase.convertViaTemplateMapping(tplId).getOrNull()!!

        result.converted shouldBe 2
        coVerify { pendingRepo.dismiss(item1.id) }
        coVerify { pendingRepo.dismiss(item2.id) }
        // The user explicitly mapped/saved — routing must bypass the
        // per-sender auto-route gate or held items could never convert.
        coVerify(exactly = 2) { route(any(), any(), any(), userInitiated = true) }
    }
}
