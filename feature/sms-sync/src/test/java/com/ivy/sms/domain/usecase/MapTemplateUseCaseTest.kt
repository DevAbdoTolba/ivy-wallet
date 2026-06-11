package com.ivy.sms.domain.usecase

import arrow.core.right
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.TransactionId
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.ColorInt
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.repository.AccountRepository
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
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.UUID

class MapTemplateUseCaseTest {

    private val templateRepo = mockk<SmsTemplateRepository>()
    private val pendingRepo = mockk<PendingReviewItemRepository>(relaxed = true)
    private val senderRepo = mockk<SenderAccountLinkRepository>()
    private val route = mockk<RouteSmsUseCase>()
    private val prefs = mockk<com.ivy.sms.data.SmsWatermarkPreferences>(relaxed = true)
    private val accountRepo = mockk<AccountRepository>()
    private val findMatching = mockk<FindMatchingMessagesUseCase>(relaxed = true)

    private val mapTemplate = MapTemplateUseCase(
        templateRepo, pendingRepo, senderRepo, route, prefs, accountRepo, findMatching,
    )

    private val wildcardId = WildcardId(UUID.randomUUID())
    private val templateId = SmsTemplateId(UUID.randomUUID())
    private val seedTemplate = SmsTemplate(
        id = templateId,
        pattern = "Order <*> at Cafe",
        exampleBody = "Order 12.34 at Cafe",
        wildcardSlots = listOf(
            WildcardSlot(wildcardId, 1, "Order <*> at", "12.34", WildcardRole.Unmapped),
        ),
        state = TemplateState.UNMAPPED,
        senderIdHint = "TestBank",
        firstSeen = Instant.EPOCH,
        lastSeen = Instant.EPOCH,
        matchCount = 1,
    )

    private fun pendingItem(body: String, template: SmsTemplate = seedTemplate) = PendingReviewItem(
        id = PendingReviewItemId(UUID.randomUUID()),
        sms = SmsMessage("k-$body", "TestBank", body, Instant.EPOCH),
        template = template,
        quarantineReason = QuarantineReason.TEMPLATE_NOT_MAPPED,
        enqueuedAt = Instant.EPOCH,
    )

    private fun account(id: AccountId, name: String) = Account(
        id = id,
        name = NotBlankTrimmedString.from(name).getOrNull()!!,
        asset = AssetCode.from("EGP").getOrNull()!!,
        color = ColorInt(0xFF000000.toInt()),
        icon = null,
        includeInBalance = true,
        orderNum = 0.0,
    )

    @Test
    fun rejects_save_whenNoAmountRole() = runTest {
        coEvery { templateRepo.findById(templateId) } returns seedTemplate.right()

        val result = mapTemplate(
            templateId = templateId,
            wildcardRoles = mapOf(wildcardId to WildcardRole.Merchant),
        )

        result.leftOrNull()!! shouldStartWith "VALIDATION"
    }

    @Test
    fun rejects_save_whenMultipleAmountRoles() = runTest {
        val secondId = WildcardId(UUID.randomUUID())
        val twoSlot = seedTemplate.copy(
            wildcardSlots = seedTemplate.wildcardSlots + WildcardSlot(
                id = secondId,
                positionInPattern = 3,
                contextSnippet = "",
                exampleValue = "1.0",
                role = WildcardRole.Unmapped,
            ),
        )
        coEvery { templateRepo.findById(templateId) } returns twoSlot.right()

        val result = mapTemplate(
            templateId = templateId,
            wildcardRoles = mapOf(
                wildcardId to WildcardRole.Income,
                secondId to WildcardRole.Expense,
            ),
        )

        result.leftOrNull()!! shouldStartWith "VALIDATION"
    }

    @Test
    fun convertsQueuedItems_whenSavedSuccessfully() = runTest {
        coEvery { templateRepo.findById(templateId) } returns seedTemplate.right()
        coEvery { templateRepo.upsert(any()) } returns Unit.right()

        val item = pendingItem(body = "Order 12.34 at Cafe")
        coEvery { pendingRepo.findByTemplateId(templateId) } returns listOf(item).right()
        coEvery { senderRepo.findAll() } returns listOf(
            SenderAccountLink("TestBank", AccountId(UUID.randomUUID()), Instant.EPOCH),
        ).right()
        coEvery { route(any(), any(), any(), any()) } returns
            RouteOutcome.Created(TransactionId(UUID.randomUUID())).right()

        val result = mapTemplate(
            templateId = templateId,
            wildcardRoles = mapOf(wildcardId to WildcardRole.Expense),
        )

        val res = result.getOrNull()!!
        res.convertedFromQueue shouldBe 1
        res.totalOwn shouldBe 1
        res.alignedCount shouldBe 1
        res.failedTotal shouldBe 0
        coVerify { pendingRepo.dismiss(item.id) }
        // Reprocess is scoped to the saved template's own items â€” the
        // whole-sender findAll fan-out must be gone.
        coVerify(exactly = 0) { pendingRepo.findAll() }
    }

    @Test
    fun invalidatesFindMatchingCache_afterUpsert() = runTest {
        coEvery { templateRepo.findById(templateId) } returns seedTemplate.right()
        coEvery { templateRepo.upsert(any()) } returns Unit.right()
        coEvery { pendingRepo.findByTemplateId(templateId) } returns
            emptyList<PendingReviewItem>().right()
        coEvery { senderRepo.findAll() } returns
            emptyList<SenderAccountLink>().right()

        mapTemplate(
            templateId = templateId,
            wildcardRoles = mapOf(wildcardId to WildcardRole.Expense),
        )

        coVerify { findMatching.invalidate(templateId) }
    }

    @Test
    fun autoGeneralizesDigitLiterals_intoUnmappedSlots() = runTest {
        val balanceBaked = seedTemplate.copy(
            pattern = "Spent <*> EGP balance 387.44.",
            exampleBody = "Spent 50 EGP balance 387.44.",
        )
        coEvery { templateRepo.findById(templateId) } returns balanceBaked.right()
        val saved = slot<SmsTemplate>()
        coEvery { templateRepo.upsert(capture(saved)) } returns Unit.right()
        coEvery { pendingRepo.findByTemplateId(templateId) } returns
            emptyList<PendingReviewItem>().right()
        coEvery { senderRepo.findAll() } returns
            emptyList<SenderAccountLink>().right()

        val result = mapTemplate(
            templateId = templateId,
            wildcardRoles = mapOf(wildcardId to WildcardRole.Expense),
        )

        result.isRight() shouldBe true
        saved.captured.pattern shouldBe "Spent <*> EGP balance <*>"
        saved.captured.wildcardSlots.size shouldBe 2
        val generalized = saved.captured.wildcardSlots.single { it.id != wildcardId }
        generalized.positionInPattern shouldBe 4
        generalized.exampleValue shouldBe "387.44."
        generalized.role shouldBe WildcardRole.Unmapped
    }

    @Test
    fun keepsDigitLiteral_whenUserConfirmedIt() = runTest {
        val balanceBaked = seedTemplate.copy(
            pattern = "Spent <*> EGP balance 387.44.",
            exampleBody = "Spent 50 EGP balance 387.44.",
        )
        coEvery { templateRepo.findById(templateId) } returns balanceBaked.right()
        val saved = slot<SmsTemplate>()
        coEvery { templateRepo.upsert(capture(saved)) } returns Unit.right()
        coEvery { pendingRepo.findByTemplateId(templateId) } returns
            emptyList<PendingReviewItem>().right()
        coEvery { senderRepo.findAll() } returns
            emptyList<SenderAccountLink>().right()

        mapTemplate(
            templateId = templateId,
            wildcardRoles = mapOf(wildcardId to WildcardRole.Expense),
            confirmedLiteralPositions = setOf(4),
        )

        saved.captured.pattern shouldBe "Spent <*> EGP balance 387.44."
        saved.captured.wildcardSlots.size shouldBe 1
    }

    @Test
    fun blocksSave_whenPatternAlignsNoneOfOwnPending() = runTest {
        coEvery { templateRepo.findById(templateId) } returns seedTemplate.right()
        coEvery { pendingRepo.findByTemplateId(templateId) } returns listOf(
            pendingItem(body = "Completely different message body"),
            pendingItem(body = "Another shape entirely"),
        ).right()
        coEvery { senderRepo.findAll() } returns
            emptyList<SenderAccountLink>().right()

        val result = mapTemplate(
            templateId = templateId,
            wildcardRoles = mapOf(wildcardId to WildcardRole.Expense),
        )

        result.leftOrNull()!! shouldBe "ZERO_ALIGNMENT:2"
        // Nothing persists on a blocked save.
        coVerify(exactly = 0) { templateRepo.upsert(any()) }
    }

    @Test
    fun savesAnyway_whenZeroAlignmentExplicitlyAllowed() = runTest {
        coEvery { templateRepo.findById(templateId) } returns seedTemplate.right()
        coEvery { templateRepo.upsert(any()) } returns Unit.right()
        coEvery { pendingRepo.findByTemplateId(templateId) } returns listOf(
            pendingItem(body = "Completely different message body"),
        ).right()
        coEvery { senderRepo.findAll() } returns
            emptyList<SenderAccountLink>().right()
        coEvery { route(any(), any(), any(), any()) } returns
            RouteOutcome.Quarantined(QuarantineReason.AMOUNT_NOT_PARSEABLE).right()

        val result = mapTemplate(
            templateId = templateId,
            wildcardRoles = mapOf(wildcardId to WildcardRole.Expense),
            allowZeroAlignment = true,
        )

        val res = result.getOrNull()!!
        res.alignedCount shouldBe 0
        res.totalOwn shouldBe 1
        res.failedAlignment shouldBe 1
        coVerify { templateRepo.upsert(any()) }
    }

    @Test
    fun splitsQuarantines_intoAlignmentVsAmountParse() = runTest {
        coEvery { templateRepo.findById(templateId) } returns seedTemplate.right()
        coEvery { templateRepo.upsert(any()) } returns Unit.right()
        coEvery { pendingRepo.findByTemplateId(templateId) } returns listOf(
            // Aligns with the pattern but the (mocked) route still quarantines
            // it as AMOUNT_NOT_PARSEABLE â†’ must count as failedAmountParse.
            pendingItem(body = "Order 12.34 at Cafe"),
            // Doesn't align â†’ the same quarantine reason must count as
            // failedAlignment.
            pendingItem(body = "Totally different message body"),
        ).right()
        coEvery { senderRepo.findAll() } returns
            emptyList<SenderAccountLink>().right()
        coEvery { route(any(), any(), any(), any()) } returns
            RouteOutcome.Quarantined(QuarantineReason.AMOUNT_NOT_PARSEABLE).right()

        val res = mapTemplate(
            templateId = templateId,
            wildcardRoles = mapOf(wildcardId to WildcardRole.Expense),
        ).getOrNull()!!

        res.totalOwn shouldBe 2
        res.alignedCount shouldBe 1
        res.failedAmountParse shouldBe 1
        res.failedAlignment shouldBe 1
        res.failedSenderNotLinked shouldBe 0
        res.convertedFromQueue shouldBe 0
    }

    @Test
    fun countsSenderNotLinked_separately() = runTest {
        coEvery { templateRepo.findById(templateId) } returns seedTemplate.right()
        coEvery { templateRepo.upsert(any()) } returns Unit.right()
        coEvery { pendingRepo.findByTemplateId(templateId) } returns listOf(
            pendingItem(body = "Order 12.34 at Cafe"),
        ).right()
        coEvery { senderRepo.findAll() } returns
            emptyList<SenderAccountLink>().right()
        coEvery { route(any(), any(), any(), any()) } returns
            RouteOutcome.Quarantined(QuarantineReason.SENDER_NOT_LINKED).right()

        val res = mapTemplate(
            templateId = templateId,
            wildcardRoles = mapOf(wildcardId to WildcardRole.Expense),
        ).getOrNull()!!

        res.failedSenderNotLinked shouldBe 1
        res.failedAlignment shouldBe 0
        res.failedAmountParse shouldBe 0
    }

    @Test
    fun rejectsSave_whenSenderLinkedToDifferentWallet() = runTest {
        val walletA = AccountId(UUID.randomUUID())
        val walletB = AccountId(UUID.randomUUID())
        coEvery { templateRepo.findById(templateId) } returns seedTemplate.right()
        coEvery { senderRepo.findAll() } returns listOf(
            SenderAccountLink("TestBank", walletA, Instant.EPOCH),
        ).right()
        coEvery { accountRepo.findById(walletA) } returns account(walletA, "Wallet A")
        coEvery { accountRepo.findById(walletB) } returns account(walletB, "Wallet B")

        val result = mapTemplate(
            templateId = templateId,
            wildcardRoles = mapOf(wildcardId to WildcardRole.Expense),
            walletScope = walletB,
        )

        val err = result.leftOrNull()!!
        err shouldStartWith "WALLET_SCOPE_MISMATCH"
        err shouldContain "Wallet A"
        err shouldContain "Wallet B"
        coVerify(exactly = 0) { templateRepo.upsert(any()) }
    }

    @Test
    fun allowsSave_whenSenderLinkedToScopedWallet() = runTest {
        val walletA = AccountId(UUID.randomUUID())
        coEvery { templateRepo.findById(templateId) } returns seedTemplate.right()
        coEvery { templateRepo.upsert(any()) } returns Unit.right()
        coEvery { senderRepo.findAll() } returns listOf(
            SenderAccountLink("TestBank", walletA, Instant.EPOCH),
        ).right()
        val item = pendingItem(body = "Order 12.34 at Cafe")
        coEvery { pendingRepo.findByTemplateId(templateId) } returns listOf(item).right()
        coEvery { route(any(), any(), any(), any()) } returns
            RouteOutcome.Created(TransactionId(UUID.randomUUID())).right()

        val res = mapTemplate(
            templateId = templateId,
            wildcardRoles = mapOf(wildcardId to WildcardRole.Expense),
            walletScope = walletA,
        ).getOrNull()!!

        res.convertedFromQueue shouldBe 1
        res.totalOwn shouldBe 1
    }
}
