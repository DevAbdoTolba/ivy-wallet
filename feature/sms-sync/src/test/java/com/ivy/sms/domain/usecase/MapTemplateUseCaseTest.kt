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
import com.ivy.sms.domain.model.TransactionClassification
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardMapping
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

class MapTemplateUseCaseTest {

    private val templateRepo = mockk<SmsTemplateRepository>()
    private val pendingRepo = mockk<PendingReviewItemRepository>(relaxed = true)
    private val senderRepo = mockk<SenderAccountLinkRepository>()
    private val route = mockk<RouteSmsUseCase>()

    private val mapTemplate = MapTemplateUseCase(templateRepo, pendingRepo, senderRepo, route)

    private val wildcardId = WildcardId(UUID.randomUUID())
    private val templateId = SmsTemplateId(UUID.randomUUID())
    private val seedTemplate = SmsTemplate(
        id = templateId,
        pattern = "Order <*> at Cafe",
        wildcardSlots = listOf(
            WildcardSlot(wildcardId, 1, "Order <*> at", WildcardMapping.Unmapped),
        ),
        state = TemplateState.UNMAPPED,
        classification = null,
        senderIdHint = "TestBank",
        firstSeen = Instant.EPOCH,
        lastSeen = Instant.EPOCH,
        matchCount = 1,
    )

    @Test
    fun rejects_save_whenNoAmountWildcard() = runTest {
        coEvery { templateRepo.findById(templateId) } returns seedTemplate.right()

        val result = mapTemplate(
            templateId = templateId,
            wildcardMappings = mapOf(wildcardId to WildcardMapping.Merchant),
            classification = TransactionClassification.EXPENSE,
        )

        result.leftOrNull()!! shouldStartWith "VALIDATION"
    }

    @Test
    fun convertsQueuedItems_whenSavedSuccessfully() = runTest {
        coEvery { templateRepo.findById(templateId) } returns seedTemplate.right()
        coEvery { templateRepo.upsert(any()) } returns Unit.right()

        val pendingItem = PendingReviewItem(
            id = PendingReviewItemId(UUID.randomUUID()),
            sms = SmsMessage("k", "TestBank", "body", Instant.EPOCH),
            template = seedTemplate,
            quarantineReason = QuarantineReason.TEMPLATE_NOT_MAPPED,
            enqueuedAt = Instant.EPOCH,
        )
        coEvery { pendingRepo.findByTemplateId(templateId) } returns listOf(pendingItem).right()
        coEvery { senderRepo.findAll() } returns listOf(
            SenderAccountLink("TestBank", AccountId(UUID.randomUUID()), Instant.EPOCH),
        ).right()
        coEvery { route(any(), any(), any()) } returns RouteOutcome.Created(TransactionId(UUID.randomUUID())).right()

        val result = mapTemplate(
            templateId = templateId,
            wildcardMappings = mapOf(wildcardId to WildcardMapping.Amount),
            classification = TransactionClassification.EXPENSE,
        )

        result.getOrNull()!!.convertedFromQueue shouldBe 1
        coVerify { pendingRepo.dismiss(pendingItem.id) }
    }
}
