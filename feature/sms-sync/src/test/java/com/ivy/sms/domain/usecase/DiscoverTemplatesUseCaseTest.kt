package com.ivy.sms.domain.usecase

import arrow.core.right
import com.ivy.sms.data.DrainParser
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.data.SmsTemplateRepository
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
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.UUID

class DiscoverTemplatesUseCaseTest {

    private val templateRepo = mockk<SmsTemplateRepository>()
    private val senderRepo = mockk<SenderAccountLinkRepository>()
    private val parser = DrainParser()
    private val discover = DiscoverTemplatesUseCase(parser, templateRepo, senderRepo)

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

            // The frozen cluster matches "uptown" (6/7 positional agreement)
            // but is count-only: neither the in-memory pattern (DrainParser
            // skips the merge for frozen clusters) nor the persisted template
            // may change — only the counters move.
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
        // The confident merge consults the trust gate — no ACTIVE sibling
        // template exists, so the sender is untrusted.
        coEvery { templateRepo.findAll() } returns emptyList<SmsTemplate>().right()
        val saved = slot<SmsTemplate>()
        coEvery { templateRepo.upsert(capture(saved)) } returns Unit.right()

        val result = discover(message("Paid 99 fees to Cafe branch uptown"))

        result.getOrNull()!!.created shouldBe false
        saved.captured.pattern shouldBe "Paid <*> fees to Cafe branch <*>"
        // Prior slot at position 1 survives with its role; the newly-emerged
        // wildcard at position 6 gets an INFERRED default ("uptown" is free
        // text → Merchant) so the mapping screen opens pre-filled.
        val byPosition = saved.captured.wildcardSlots.associateBy { it.positionInPattern }
        byPosition.getValue(1).id shouldBe slotId
        byPosition.getValue(1).role shouldBe WildcardRole.Expense
        byPosition.getValue(1).exampleValue shouldBe "99"
        byPosition.getValue(6).role shouldBe WildcardRole.Merchant
        byPosition.getValue(6).exampleValue shouldBe "uptown"
        // Pre-bound Expense at position 1 means the refresh is confidently
        // inferable — but the sender has NO other ACTIVE template, so it
        // must stay UNMAPPED (trust gate).
        saved.captured.state shouldBe TemplateState.UNMAPPED
    }

    @Test
    fun newCluster_persistsUnmappedTemplate_andReportsCreated() = runTest {
        coEvery { templateRepo.findById(any()) } returns (null as SmsTemplate?).right()
        coEvery { templateRepo.findAll() } returns emptyList<SmsTemplate>().right()
        val saved = slot<SmsTemplate>()
        coEvery { templateRepo.upsert(capture(saved)) } returns Unit.right()

        val result = discover(message("Welcome to TestBank services"))

        result.getOrNull()!!.created shouldBe true
        saved.captured.state shouldBe TemplateState.UNMAPPED
        saved.captured.senderIdHint shouldBe "TestBank"
    }

    @Test
    fun newDrainLeaf_alignedToFrozenTemplate_reusesIt_insteadOfReAsking() = runTest {
        // Deep-scan re-ask bug: a variant message lands on a FRESH drain leaf
        // (parser is empty here), but its shape aligns to an already-ACTIVE
        // template. Discover must reuse the frozen template — never mint a
        // second UNMAPPED row asking the user to map the same shape again.
        val active = seededTemplate(TemplateState.ACTIVE)
        coEvery { templateRepo.findById(any()) } returns (null as SmsTemplate?).right()
        coEvery { templateRepo.findAll() } returns listOf(active).right()
        val saved = slot<SmsTemplate>()
        coEvery { templateRepo.upsert(capture(saved)) } returns Unit.right()

        val result = discover(message("Paid 250 fees to Cafe branch downtown"))

        result.getOrNull()!!.created shouldBe false
        saved.captured.id shouldBe active.id
        saved.captured.state shouldBe TemplateState.ACTIVE
        saved.captured.pattern shouldBe active.pattern
        saved.captured.matchCount shouldBe 4
    }

    @Test
    fun confidentMerge_fromTrustedLinkedSender_autoActivates() = runTest {
        // The automaticity core: an UNMAPPED cluster merging its second
        // sample, from a sender that already has ONE user-approved ACTIVE
        // template AND a wallet link. Inference is unambiguous (direction
        // keyword + balance context) → the template activates ITSELF and
        // transactions flow with zero questions.
        val unmappedId = SmsTemplateId(UUID.randomUUID())
        val unmapped = SmsTemplate(
            id = unmappedId,
            pattern = "Debit <*> at balance <*> end",
            exampleBody = "Debit 100.50 at balance 900.00 end",
            wildcardSlots = listOf(
                WildcardSlot(WildcardId(UUID.randomUUID()), 1, "", "100.50", WildcardRole.Unmapped),
                WildcardSlot(WildcardId(UUID.randomUUID()), 4, "", "900.00", WildcardRole.Unmapped),
            ),
            state = TemplateState.UNMAPPED,
            senderIdHint = "TestBank",
            firstSeen = Instant.EPOCH,
            lastSeen = Instant.EPOCH,
            matchCount = 2,
        )
        parser.rebuildFromTemplates(listOf(unmapped))
        coEvery { templateRepo.findById(unmappedId) } returns unmapped.right()
        coEvery { templateRepo.findAll() } returns
            listOf(seededTemplate(TemplateState.ACTIVE)).right()
        coEvery { senderRepo.findBySenderId("TestBank") } returns
            mockk<SenderAccountLink>().right()
        val saved = slot<SmsTemplate>()
        coEvery { templateRepo.upsert(capture(saved)) } returns Unit.right()

        val result = discover(message("Debit 55.00 at balance 800.00 end"))

        result.getOrNull()!!.created shouldBe false
        saved.captured.state shouldBe TemplateState.ACTIVE
        val byPosition = saved.captured.wildcardSlots.associateBy { it.positionInPattern }
        byPosition.getValue(1).role shouldBe WildcardRole.Expense
        byPosition.getValue(4).role shouldBe WildcardRole.CurrentTotal
    }
}
