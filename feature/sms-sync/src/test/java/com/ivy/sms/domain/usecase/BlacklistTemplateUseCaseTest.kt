package com.ivy.sms.domain.usecase

import arrow.core.right
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.model.TransactionClassification
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardMapping
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

class BlacklistTemplateUseCaseTest {

    private val templateRepo = mockk<SmsTemplateRepository>()
    private val pendingRepo = mockk<PendingReviewItemRepository>(relaxed = true)
    private val useCase = BlacklistTemplateUseCase(templateRepo, pendingRepo)

    private fun template(state: TemplateState): SmsTemplate = SmsTemplate(
        id = SmsTemplateId(UUID.randomUUID()),
        pattern = "test",
        wildcardSlots = listOf(WildcardSlot(WildcardId(UUID.randomUUID()), 0, "", WildcardMapping.Amount)),
        state = state,
        classification = TransactionClassification.EXPENSE,
        senderIdHint = "TestBank",
        firstSeen = Instant.EPOCH,
        lastSeen = Instant.EPOCH,
        matchCount = 1,
    )

    @Test
    fun enable_clearsQueueItems() = runTest {
        val tpl = template(TemplateState.UNMAPPED)
        coEvery { templateRepo.findById(tpl.id) } returns tpl.right()
        coEvery { templateRepo.upsert(any()) } returns Unit.right()
        coEvery { pendingRepo.clearByTemplate(tpl.id) } returns Unit.right()

        useCase.enable(tpl.id)

        coVerify { pendingRepo.clearByTemplate(tpl.id) }
    }

    @Test
    fun enable_transitionsToBlacklisted() = runTest {
        val tpl = template(TemplateState.ACTIVE)
        coEvery { templateRepo.findById(tpl.id) } returns tpl.right()
        val saved = slot<SmsTemplate>()
        coEvery { templateRepo.upsert(capture(saved)) } returns Unit.right()
        coEvery { pendingRepo.clearByTemplate(tpl.id) } returns Unit.right()

        useCase.enable(tpl.id)

        saved.captured.state shouldBe TemplateState.BLACKLISTED
    }

    @Test
    fun disable_revertsToUnmappedNotActive() = runTest {
        val tpl = template(TemplateState.BLACKLISTED)
        coEvery { templateRepo.findById(tpl.id) } returns tpl.right()
        val saved = slot<SmsTemplate>()
        coEvery { templateRepo.upsert(capture(saved)) } returns Unit.right()

        useCase.disable(tpl.id)

        saved.captured.state shouldBe TemplateState.UNMAPPED
        saved.captured.classification shouldBe null
    }
}
