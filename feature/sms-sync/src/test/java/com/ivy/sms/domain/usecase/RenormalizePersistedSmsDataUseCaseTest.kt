package com.ivy.sms.domain.usecase

import arrow.core.right
import com.ivy.data.db.entity.PendingReviewItemEntity
import com.ivy.sms.data.NORMALIZER_VERSION
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.data.SmsWatermarkPreferences
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

class RenormalizePersistedSmsDataUseCaseTest {

    private val templateRepo = mockk<SmsTemplateRepository>()
    private val pendingRepo = mockk<PendingReviewItemRepository>()
    private val prefs = mockk<SmsWatermarkPreferences>()
    private val useCase = RenormalizePersistedSmsDataUseCase(templateRepo, pendingRepo, prefs)

    private val slotId = WildcardId(UUID.randomUUID())

    // Pre-normalizer persisted shape: "Balance now" glued by an NBSP into ONE
    // token, a trailing LRM on "EGP", an Arabic-Indic exampleValue.
    private fun rawTemplate(
        pattern: String = "Balance now <*> EGP‎",
        slots: List<WildcardSlot> = listOf(
            WildcardSlot(slotId, 1, "Balance now <*>", "١٬٥٠٠", WildcardRole.Expense),
        ),
    ) = SmsTemplate(
        id = SmsTemplateId(UUID.randomUUID()),
        pattern = pattern,
        exampleBody = "Balance now ١٬٥٠٠ EGP‎",
        wildcardSlots = slots,
        state = TemplateState.ACTIVE,
        senderIdHint = "Bank-A",
        firstSeen = Instant.EPOCH,
        lastSeen = Instant.EPOCH,
        matchCount = 7,
    )

    private fun pendingEntity(id: String, body: String) = PendingReviewItemEntity(
        id = id,
        dedupKey = "legacy-raw-key-$id",
        senderId = "Bank-A",
        body = body,
        messageEpochMillis = 1_000L,
        templateId = UUID.randomUUID().toString(),
        quarantineReason = "TEMPLATE_NOT_MAPPED",
        enqueuedAtEpochMillis = 2_000L,
    )

    private fun stubFreshRun(
        templates: List<SmsTemplate> = emptyList(),
        pending: List<PendingReviewItemEntity> = emptyList(),
    ) {
        coEvery { prefs.normalizerAppliedVersion() } returns (null as Int?).right()
        coEvery { templateRepo.findAll() } returns templates.right()
        coEvery { pendingRepo.findAllRaw() } returns pending.right()
        coEvery { prefs.writeNormalizerAppliedVersion(NORMALIZER_VERSION) } returns Unit.right()
    }

    @Test
    fun `renormalizes pattern preserving wildcard tokens and remapping slot positions`() = runTest {
        stubFreshRun(templates = listOf(rawTemplate()))
        val saved = slot<SmsTemplate>()
        coEvery { templateRepo.upsert(capture(saved)) } returns Unit.right()

        useCase() shouldBe Unit.right()

        // NBSP-glued "Balance now" split into two tokens, LRM stripped from
        // "EGP" — the wildcard is preserved verbatim and its slot follows it.
        saved.captured.pattern shouldBe "Balance now <*> EGP"
        val savedSlot = saved.captured.wildcardSlots.single()
        savedSlot.positionInPattern shouldBe 2
        savedSlot.id shouldBe slotId
        savedSlot.role shouldBe WildcardRole.Expense
        savedSlot.exampleValue shouldBe "1,500"
        saved.captured.exampleBody shouldBe "Balance now 1,500 EGP"
        // Everything that isn't text is untouched.
        saved.captured.state shouldBe TemplateState.ACTIVE
        saved.captured.matchCount shouldBe 7
        coVerify { prefs.writeNormalizerAppliedVersion(NORMALIZER_VERSION) }
    }

    @Test
    fun `pure invisible-mark literal is dropped and later slots shift left`() = runTest {
        // First pattern token is a lone RLM — normalizes to "" and drops.
        val template = rawTemplate(
            pattern = "‏ Paid <*> EGP",
            slots = listOf(
                WildcardSlot(slotId, 2, "Paid <*> EGP", "40", WildcardRole.Expense),
            ),
        )
        stubFreshRun(templates = listOf(template))
        val saved = slot<SmsTemplate>()
        coEvery { templateRepo.upsert(capture(saved)) } returns Unit.right()

        useCase() shouldBe Unit.right()

        saved.captured.pattern shouldBe "Paid <*> EGP"
        saved.captured.wildcardSlots.single().positionInPattern shouldBe 1
    }

    @Test
    fun `pending bodies renormalize but dedupKey is untouched`() = runTest {
        stubFreshRun(
            pending = listOf(
                pendingEntity(id = "p1", body = "Total‎ 1,500 EGP"),
                pendingEntity(id = "p2", body = "already clean"),
            ),
        )
        coEvery { pendingRepo.updateBody(any(), any()) } returns Unit.right()

        useCase() shouldBe Unit.right()

        // Only the body column moves (updateBody cannot touch dedupKey by
        // construction — identity stays the raw-body hash); clean rows are
        // not rewritten at all.
        coVerify(exactly = 1) { pendingRepo.updateBody("p1", "Total 1,500 EGP") }
        coVerify(exactly = 0) { pendingRepo.updateBody("p2", any()) }
    }

    @Test
    fun `already normalized data writes nothing but stamps the version`() = runTest {
        stubFreshRun(
            templates = listOf(
                rawTemplate(pattern = "Balance now <*> EGP").copy(
                    exampleBody = "Balance now 1,500 EGP",
                    wildcardSlots = listOf(
                        WildcardSlot(slotId, 2, "Balance now <*>", "1,500", WildcardRole.Expense),
                    ),
                ),
            ),
            pending = listOf(pendingEntity(id = "p1", body = "already clean")),
        )

        useCase() shouldBe Unit.right()

        coVerify(exactly = 0) { templateRepo.upsert(any()) }
        coVerify(exactly = 0) { pendingRepo.updateBody(any(), any()) }
        coVerify { prefs.writeNormalizerAppliedVersion(NORMALIZER_VERSION) }
    }

    @Test
    fun `second run is a no-op`() = runTest {
        var stored: Int? = null
        coEvery { prefs.normalizerAppliedVersion() } answers { stored.right() }
        coEvery { prefs.writeNormalizerAppliedVersion(any()) } answers {
            stored = firstArg()
            Unit.right()
        }
        coEvery { templateRepo.findAll() } returns listOf(rawTemplate()).right()
        coEvery { templateRepo.upsert(any()) } returns Unit.right()
        coEvery { pendingRepo.findAllRaw() } returns emptyList<PendingReviewItemEntity>().right()

        useCase() shouldBe Unit.right()
        useCase() shouldBe Unit.right()

        coVerify(exactly = 1) { templateRepo.findAll() }
        coVerify(exactly = 1) { templateRepo.upsert(any()) }
        coVerify(exactly = 1) { prefs.writeNormalizerAppliedVersion(NORMALIZER_VERSION) }
    }
}
