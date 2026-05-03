package com.ivy.sms.domain.usecase

import arrow.core.right
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SmsTemplateRepository
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

class BlacklistTemplateUseCaseTest {

    private val templateRepo = mockk<SmsTemplateRepository>()
    private val pendingRepo = mockk<PendingReviewItemRepository>(relaxed = true)
    private val useCase = BlacklistTemplateUseCase(templateRepo, pendingRepo)

    private fun template(state: TemplateState, role: WildcardRole = WildcardRole.Expense): SmsTemplate =
        SmsTemplate(
            id = SmsTemplateId(UUID.randomUUID()),
            pattern = "test",
            exampleBody = "test",
            wildcardSlots = listOf(
                WildcardSlot(
                    id = WildcardId(UUID.randomUUID()),
                    positionInPattern = 0,
                    contextSnippet = "",
                    exampleValue = "12.34",
                    role = role,
                ),
            ),
            state = state,
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
    fun disable_revertsToUnmappedAndClearsRoleBindings() = runTest {
        val tpl = template(TemplateState.BLACKLISTED, role = WildcardRole.Expense)
        coEvery { templateRepo.findById(tpl.id) } returns tpl.right()
        val saved = slot<SmsTemplate>()
        coEvery { templateRepo.upsert(capture(saved)) } returns Unit.right()

        useCase.disable(tpl.id)

        saved.captured.state shouldBe TemplateState.UNMAPPED
        saved.captured.wildcardSlots.first().role shouldBe WildcardRole.Unmapped
    }
}
