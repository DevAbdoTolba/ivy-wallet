package com.ivy.sms.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.base.TestDispatchersProvider
import com.ivy.base.model.TransactionType
import com.ivy.data.db.DatabaseTransaction
import com.ivy.data.db.dao.read.TransactionDao
import com.ivy.data.db.dao.write.WriteTransactionDao
import com.ivy.data.db.entity.TransactionEntity
import com.ivy.sms.data.*
import com.ivy.sms.domain.model.*
import io.kotest.matchers.shouldBe
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.UUID

class RepairGhostTransfersUseCaseTest {
    private val read = mockk<TransactionDao>()
    private val write = mockk<WriteTransactionDao>(relaxed = true)
    private val inbox = mockk<SmsInboxDataSource>()
    private val templates = mockk<SmsTemplateRepository>()
    private val senders = mockk<SenderAccountLinkRepository>()
    private val pending = mockk<PendingReviewItemRepository>()
    private val prefs = mockk<SmsWatermarkPreferences>()
    private val transaction = mockk<DatabaseTransaction> {
        coEvery { invoke<Unit>(any()) } coAnswers { firstArg<suspend () -> Unit>()() }
    }
    private val mapper = SmsMessageMapper()
    private val row = SmsRow(1, "Bank", 1000L, "Transfer 50 EGP")
    private val message = with(mapper) { row.toDomain() }
    private val account = UUID.randomUUID()
    private val template = SmsTemplate(
        id = SmsTemplateId(UUID.randomUUID()), pattern = "Transfer <*> EGP",
        exampleBody = row.body, wildcardSlots = emptyList(), state = TemplateState.ACTIVE,
        senderIdHint = row.address, firstSeen = Instant.EPOCH, lastSeen = Instant.EPOCH, matchCount = 2,
    )
    private val ghost = TransactionEntity(
        accountId = account, toAccountId = account, type = TransactionType.TRANSFER, amount = 50.0,
        smsSourceDedupKey = message.dedupKey, smsSourceTimestamp = message.timestamp,
        smsSourceSenderId = row.address, smsTemplateId = template.id.value,
    )
    private val repair = RepairGhostTransfersUseCase(
        read, write, inbox, mapper, templates, senders, pending, prefs,
        TestDispatchersProvider, transaction,
    )

    init {
        coEvery { prefs.ghostRepairAppliedVersion() } returns null.right()
        coEvery { prefs.writeGhostRepairAppliedVersion(any()) } returns Unit.right()
        coEvery { read.findAll() } returns listOf(ghost)
        coEvery { senders.findAll() } returns emptyList<SenderAccountLink>().right()
        coEvery { inbox.read(any(), any(), any()) } returns listOf(row).right()
        coEvery { templates.findById(template.id) } returns template.right()
        coEvery { pending.enqueue(any()) } returns Unit.right()
    }

    @Test fun `read failure keeps the original row and does not stamp completion`() = runTest {
        coEvery { inbox.read(any(), any(), any()) } returns "PERMISSION_DENIED".left()
        repair() shouldBe "PERMISSION_DENIED".left()
        coVerify(exactly = 0) { write.deleteById(any()) }
        coVerify(exactly = 0) { prefs.writeGhostRepairAppliedVersion(any()) }
    }

    @Test fun `missing original SMS keeps the recoverable transaction metadata`() = runTest {
        coEvery { inbox.read(any(), any(), any()) } returns emptyList<SmsRow>().right()
        repair().isLeft() shouldBe true
        coVerify(exactly = 0) { write.deleteById(any()) }
    }

    @Test fun `failed enqueue leaves repair incomplete for a retry`() = runTest {
        coEvery { pending.enqueue(any()) } returns "disk full".left()
        repair().isLeft() shouldBe true
        coVerify(exactly = 0) { prefs.writeGhostRepairAppliedVersion(any()) }
    }

    @Test fun `successful rescue retains the original SMS identity in review`() = runTest {
        val saved = slot<PendingReviewItem>()
        coEvery { pending.enqueue(capture(saved)) } returns Unit.right()
        repair() shouldBe Unit.right()
        saved.captured.sms shouldBe message
        saved.captured.quarantineReason shouldBe QuarantineReason.TRANSFER_NEEDS_DESTINATION
        coVerify(exactly = 1) { write.deleteById(ghost.id) }
        coVerify(exactly = 1) { prefs.writeGhostRepairAppliedVersion(GHOST_TRANSFER_REPAIR_VERSION) }
    }

    @Test fun `valid transfers and manual rows are untouched`() = runTest {
        coEvery { read.findAll() } returns listOf(
            ghost.copy(toAccountId = UUID.randomUUID()), ghost.copy(smsSourceDedupKey = null),
        )
        repair() shouldBe Unit.right()
        coVerify(exactly = 0) { write.deleteById(any()) }
        coVerify(exactly = 0) { inbox.read(any(), any(), any()) }
    }

    @Test fun `applied version skips every scan and write`() = runTest {
        coEvery { prefs.ghostRepairAppliedVersion() } returns GHOST_TRANSFER_REPAIR_VERSION.right()
        repair() shouldBe Unit.right()
        coVerify(exactly = 0) { read.findAll() }
    }
}
