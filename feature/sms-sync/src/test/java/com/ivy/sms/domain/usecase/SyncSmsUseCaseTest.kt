package com.ivy.sms.domain.usecase

import android.content.Context
import android.content.pm.PackageManager
import arrow.core.left
import arrow.core.right
import com.ivy.data.model.AccountId
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.domain.model.SenderAccountLink
import com.ivy.sms.domain.model.SyncResult
import com.ivy.sms.domain.model.SyncTrigger
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.UUID

class SyncSmsUseCaseTest {

    private val context = mockk<Context>()
    private val scan = mockk<ScanInboxUseCase>()
    private val senderRepo = mockk<SenderAccountLinkRepository>()
    private val findMatching = mockk<FindMatchingMessagesUseCase>()
    private val renormalize = mockk<RenormalizePersistedSmsDataUseCase>()

    private val useCase = SyncSmsUseCaseImpl(
        context = context,
        scan = scan,
        senderRepo = senderRepo,
        findMatching = findMatching,
        renormalize = renormalize,
    )

    private val link = SenderAccountLink(
        senderId = "Bank-A",
        accountId = AccountId(UUID.randomUUID()),
        linkedAt = Instant.EPOCH,
    )

    init {
        // ContextCompat.checkSelfPermission delegates to this; granted == 0.
        every { context.checkPermission(any(), any(), any()) } returns
            PackageManager.PERMISSION_GRANTED
        justRun { findMatching.invalidate() }
    }

    @Test
    fun `renormalization completes before the scan seeds the parser`() = runTest {
        coEvery { senderRepo.findAll() } returns listOf(link).right()
        coEvery { renormalize() } returns Unit.right()
        coEvery { scan() } returns ScanSummary(1, 1, 0, 0, 5L).right()

        val result = useCase(SyncTrigger.APP_LAUNCH)

        result shouldBe SyncResult.Completed(
            newMessagesProcessed = 1,
            transactionsCreated = 1,
            itemsQuarantined = 0,
            durationMillis = 5L,
        ).right()
        coVerifyOrder {
            renormalize()
            scan()
        }
    }

    @Test
    fun `renormalization failure aborts the sync before any scan`() = runTest {
        coEvery { senderRepo.findAll() } returns listOf(link).right()
        coEvery { renormalize() } returns "STORAGE_ERROR:boom".left()

        val result = useCase(SyncTrigger.MANUAL_MENU)

        result shouldBe "STORAGE_ERROR:boom".left()
        coVerify(exactly = 0) { scan() }
    }

    @Test
    fun `no linked senders skips both renormalization and scan`() = runTest {
        coEvery { senderRepo.findAll() } returns emptyList<SenderAccountLink>().right()

        val result = useCase(SyncTrigger.APP_LAUNCH)

        result shouldBe SyncResult.Completed(0, 0, 0, 0L).right()
        coVerify(exactly = 0) { renormalize() }
        coVerify(exactly = 0) { scan() }
    }
}
