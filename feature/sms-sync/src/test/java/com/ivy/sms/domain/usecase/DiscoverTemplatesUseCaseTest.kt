package com.ivy.sms.domain.usecase

import arrow.core.right
import com.ivy.sms.data.DrainParser
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.domain.model.SmsMessage
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.domain.model.WildcardSlot
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.UUID

class DiscoverTemplatesUseCaseTest {

    private val templateRepo = mockk<SmsTemplateRepository>()
    private val parser = DrainParser()
    private val discover = DiscoverTemplatesUseCase(parser, templateRepo)

    private val templateId = SmsTemplateId(UUID.randomUUID())
    private val slotId = WildcardId(UUID.randomUUID())
    private val mappedSlots = listOf(
        WildcardSlot(slotId, 1, "Paid <*> fees", "10", WildcardRole.Expense),
    )

    private fun seededTemplate(state: TemplateState): SmsTemplate = SmsTemplate(
        id = templateId,
        pattern = "Paid <*> fees to Cafe branch downtown",
        exampleBody = "Paid 10 fees to Cafe branch downtown",
        wildcardSlots = mappedSlots,
        state = state,
        senderIdHint = "TestBank",
        firstSeen = Instant.EPOCH,
        lastSeen = Instant.EPOCH,
        matchCount = 3,
    )

    private fun message(body: String): SmsMessage = SmsMessage(
        dedupKey = "k",
        senderId = "TestBank",
        body = body,
        timestamp = Instant.ofEpochMilli(1_700_000_000_000L),
    )

    @Test
    fun frozenStates_neverRewritePatternOrSlots_onlyCounters() = runTest {
        for (state in listOf(TemplateState.ACTIVE, TemplateState.BLACKLISTED)) {
            val existing = seededTemplate(state)
            parser.rebuildFromTemplates(listOf(existing))
            coEvery { templateRepo.findById(templateId) } returns existing.right()
            val saved = slot<SmsTemplate>()
            coEvery { templateRepo.upsert(capture(saved)) } returns Unit.right()

            // In-memory the cluster DOES re-merge: "uptown" disagrees with the
            // pattern literal "downtown" at index 6, so the cluster pattern
            // gains a wildcard there. The persisted template must not pick
            // that up — only the counters move.
            val result = discover(message("Paid 99 fees to Cafe branch uptown"))

            result.getOrNull()!!.created shouldBe false
            saved.captured.pattern shouldBe existing.pattern
            saved.captured.wildcardSlots shouldBe existing.wildcardSlots
            saved.captured.matchCount shouldBe 4
            saved.captured.lastSeen shouldBe Instant.ofEpochMilli(1_700_000_000_000L)
        }
    }

    @Test
    fun unmappedTemplates_refreshPatternAndSlots() = runTest {
        val existing = seededTemplate(TemplateState.UNMAPPED)
        parser.rebuildFromTemplates(listOf(existing))
        coEvery { templateRepo.findById(templateId) } returns existing.right()
        val saved = slot<SmsTemplate>()
        coEvery { templateRepo.upsert(capture(saved)) } returns Unit.right()

        val result = discover(message("Paid 99 fees to Cafe branch uptown"))

        result.getOrNull()!!.created shouldBe false
        saved.captured.pattern shouldBe "Paid <*> fees to Cafe branch <*>"
        // Prior slot at position 1 survives with its role; the newly-emerged
        // wildcard at position 6 gets a fresh Unmapped slot.
        val byPosition = saved.captured.wildcardSlots.associateBy { it.positionInPattern }
        byPosition.getValue(1).id shouldBe slotId
        byPosition.getValue(1).role shouldBe WildcardRole.Expense
        byPosition.getValue(1).exampleValue shouldBe "99"
        byPosition.getValue(6).role shouldBe WildcardRole.Unmapped
        byPosition.getValue(6).exampleValue shouldBe "uptown"
    }

    @Test
    fun newCluster_persistsUnmappedTemplate_andReportsCreated() = runTest {
        coEvery { templateRepo.findById(any()) } returns (null as SmsTemplate?).right()
        val saved = slot<SmsTemplate>()
        coEvery { templateRepo.upsert(capture(saved)) } returns Unit.right()

        val result = discover(message("Welcome to TestBank services"))

        result.getOrNull()!!.created shouldBe true
        saved.captured.state shouldBe TemplateState.UNMAPPED
        saved.captured.senderIdHint shouldBe "TestBank"
    }
}
