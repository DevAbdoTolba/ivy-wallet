package com.ivy.sms.domain.usecase

import arrow.core.right
import com.ivy.base.TestDispatchersProvider
import com.ivy.data.model.AccountId
import com.ivy.sms.data.BuildFingerprintProvider
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.data.SmsInboxDataSource
import com.ivy.sms.data.SmsMessageMapper
import com.ivy.sms.data.SmsRow
import com.ivy.sms.data.SmsWatermarkPreferences
import com.ivy.sms.domain.model.QuarantineReason
import com.ivy.sms.domain.model.SenderAccountLink
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.UUID

class ScanInboxUseCaseTest {

    private val inbox = mockk<SmsInboxDataSource>()
    private val watermarks = mockk<SmsWatermarkPreferences>()
    private val senderRepo = mockk<SenderAccountLinkRepository>()
    private val discover = mockk<DiscoverTemplatesUseCase>()
    private val route = mockk<RouteSmsUseCase>()
    private val buildFingerprint = mockk<BuildFingerprintProvider>()

    private val useCase = ScanInboxUseCase(
        inbox = inbox,
        watermarks = watermarks,
        senderRepo = senderRepo,
        discover = discover,
        route = route,
        smsMessageMapper = SmsMessageMapper(),
        buildFingerprint = buildFingerprint,
        dispatchers = TestDispatchersProvider,
    )

    init {
        every { buildFingerprint.fingerprint } returns "test-build"
        coJustRun { discover.seed() }
    }

    private fun link(
        senderId: String,
        watermark: Instant? = null,
        linkedAt: Instant = Instant.ofEpochMilli(1L),
        historicalLowerBound: Instant? = null,
    ) = SenderAccountLink(
        senderId = senderId,
        accountId = AccountId(UUID.randomUUID()),
        linkedAt = linkedAt,
        historicalLowerBound = historicalLowerBound,
        watermark = watermark,
    )

    private fun template(sender: String) = SmsTemplate(
        id = SmsTemplateId(UUID.randomUUID()),
        pattern = "paid <*> EGP",
        exampleBody = "paid 10 EGP",
        wildcardSlots = emptyList(),
        state = TemplateState.UNMAPPED,
        senderIdHint = sender,
        firstSeen = Instant.EPOCH,
        lastSeen = Instant.EPOCH,
        matchCount = 1,
    )

    private fun row(sender: String, dateEpochMillis: Long) = SmsRow(
        id = dateEpochMillis,
        address = sender,
        dateEpochMillis = dateEpochMillis,
        body = "paid 10 EGP",
    )

    @Test
    fun scan_readsEachLinkWithItsOwnWatermark() = runTest {
        val linkA = link("Bank-A", watermark = Instant.ofEpochMilli(1_000L))
        val linkB = link("Bank-B", watermark = Instant.ofEpochMilli(2_000L))
        coEvery { senderRepo.findAll() } returns listOf(linkA, linkB).right()
        coEvery { watermarks.scanLowerBound() } returns 500L.right()
        coEvery { watermarks.read() } returns 1_500L.right()
        coEvery { inbox.read(any(), any(), any()) } returns emptyList<SmsRow>().right()

        useCase()

        coVerify { inbox.read(500L, 1_000L, "Bank-A") }
        coVerify { inbox.read(500L, 2_000L, "Bank-B") }
    }

    @Test
    fun scan_linkWithOwnLowerBound_readsFromIt_notTheGlobalOne() = runTest {
        val linked = link(
            "Bank-A",
            watermark = Instant.ofEpochMilli(1_000L),
            historicalLowerBound = Instant.ofEpochMilli(800L),
        )
        coEvery { senderRepo.findAll() } returns listOf(linked).right()
        coEvery { watermarks.scanLowerBound() } returns 500L.right()
        coEvery { watermarks.read() } returns 1_000L.right()
        coEvery { inbox.read(any(), any(), any()) } returns emptyList<SmsRow>().right()

        useCase()

        coVerify { inbox.read(800L, 1_000L, "Bank-A") }
    }

    @Test
    fun scan_freshLink_linkedAfterGlobalWatermark_backfillsFromPeriodLowerBound() = runTest {
        // Linked AFTER the global watermark last advanced — its history was
        // never covered by a global scan, so the watermark bound must be 0.
        val fresh = link("Bank-New", watermark = null, linkedAt = Instant.ofEpochMilli(9_000L))
        coEvery { senderRepo.findAll() } returns listOf(fresh).right()
        coEvery { watermarks.scanLowerBound() } returns 500L.right()
        coEvery { watermarks.read() } returns 5_000L.right()
        coEvery { inbox.read(any(), any(), any()) } returns emptyList<SmsRow>().right()

        useCase()

        coVerify { inbox.read(500L, 0L, "Bank-New") }
    }

    @Test
    fun scan_legacyLink_linkedBeforeGlobalWatermark_inheritsItAsFallback() = runTest {
        // Pre-existing install: the link predates per-link watermarks but its
        // rows were covered by the old global scans — inherit the global bound.
        val legacy = link("Bank-Old", watermark = null, linkedAt = Instant.ofEpochMilli(1_000L))
        coEvery { senderRepo.findAll() } returns listOf(legacy).right()
        coEvery { watermarks.scanLowerBound() } returns 500L.right()
        coEvery { watermarks.read() } returns 5_000L.right()
        coEvery { inbox.read(any(), any(), any()) } returns emptyList<SmsRow>().right()

        useCase()

        coVerify { inbox.read(500L, 5_000L, "Bank-Old") }
    }

    @Test
    fun scan_stampsOnlySendersThatProducedRows() = runTest {
        val linkA = link("Bank-A")
        val linkB = link("Bank-B")
        coEvery { senderRepo.findAll() } returns listOf(linkA, linkB).right()
        coEvery { watermarks.scanLowerBound() } returns 0L.right()
        coEvery { watermarks.read() } returns 0L.right()
        coEvery { inbox.read(any(), any(), "Bank-A") } returns listOf(row("Bank-A", 42_000L)).right()
        coEvery { inbox.read(any(), any(), "Bank-B") } returns emptyList<SmsRow>().right()
        coEvery { discover(any()) } returns
            DiscoverResult(template("Bank-A"), created = false).right()
        coEvery { route(any(), any(), any(), any()) } returns
            RouteOutcome.Quarantined(QuarantineReason.TEMPLATE_NOT_MAPPED).right()
        coEvery { watermarks.write(42_000L) } returns Unit.right()
        coEvery { senderRepo.upsert(any()) } returns Unit.right()

        useCase()

        // Bank-A gets its own max processed DATE; zero-row Bank-B must NOT be
        // stamped (the old fallback marked its unscanned history as synced).
        coVerify(exactly = 1) {
            senderRepo.upsert(
                match { it.senderId == "Bank-A" && it.watermark == Instant.ofEpochMilli(42_000L) },
            )
        }
        coVerify(exactly = 0) { senderRepo.upsert(match { it.senderId == "Bank-B" }) }
    }

    @Test
    fun newTemplatesDiscovered_countsOnlyTrueCreations() = runTest {
        val linked = link("Bank-A")
        coEvery { senderRepo.findAll() } returns listOf(linked).right()
        coEvery { watermarks.scanLowerBound() } returns 0L.right()
        coEvery { watermarks.read() } returns 0L.right()
        coEvery { inbox.read(any(), any(), "Bank-A") } returns listOf(
            row("Bank-A", 1_000L),
            row("Bank-A", 2_000L),
            row("Bank-A", 3_000L),
        ).right()
        val tpl = template("Bank-A")
        coEvery { discover(any()) } returnsMany listOf(
            DiscoverResult(tpl, created = true).right(),
            DiscoverResult(tpl, created = false).right(),
            DiscoverResult(tpl, created = false).right(),
        )
        coEvery { route(any(), any(), any(), any()) } returns
            RouteOutcome.Quarantined(QuarantineReason.TEMPLATE_NOT_MAPPED).right()
        coEvery { watermarks.write(3_000L) } returns Unit.right()
        coEvery { senderRepo.upsert(any()) } returns Unit.right()

        val summary = useCase().getOrNull()!!

        summary.newMessagesProcessed shouldBe 3
        summary.newTemplatesDiscovered shouldBe 1
    }
}
