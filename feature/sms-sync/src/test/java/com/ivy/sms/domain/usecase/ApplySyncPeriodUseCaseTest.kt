package com.ivy.sms.domain.usecase

import arrow.core.right
import com.ivy.data.model.AccountId
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.domain.model.SenderAccountLink
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.UUID

class ApplySyncPeriodUseCaseTest {

    private val senderRepo = mockk<SenderAccountLinkRepository>()
    private val findMatching = mockk<FindMatchingMessagesUseCase>(relaxed = true)
    private val useCase = ApplySyncPeriodUseCase(senderRepo, findMatching)

    private val walletId = AccountId(UUID.randomUUID())

    private fun link(
        historicalLowerBound: Instant? = null,
        watermark: Instant? = null,
    ) = SenderAccountLink(
        senderId = "Bank-A",
        accountId = walletId,
        linkedAt = Instant.ofEpochMilli(1L),
        historicalLowerBound = historicalLowerBound,
        watermark = watermark,
    )

    @Test
    fun firstExplicitPick_setsLowerBound_andClearsWatermark() = runTest {
        val linked = link(historicalLowerBound = null, watermark = Instant.ofEpochMilli(9_000L))
        coEvery { senderRepo.findByAccountId(walletId) } returns listOf(linked).right()
        coEvery { senderRepo.upsert(any()) } returns Unit.right()

        useCase(walletId, 5_000L)

        coVerify {
            senderRepo.upsert(
                match {
                    it.historicalLowerBound == Instant.ofEpochMilli(5_000L) && it.watermark == null
                },
            )
        }
        // A moved bound changes which inbox rows count as "matching".
        verify { findMatching.invalidate() }
    }

    @Test
    fun longerPeriod_movesLowerBoundBack_andClearsWatermark() = runTest {
        val linked = link(
            historicalLowerBound = Instant.ofEpochMilli(5_000L),
            watermark = Instant.ofEpochMilli(9_000L),
        )
        coEvery { senderRepo.findByAccountId(walletId) } returns listOf(linked).right()
        coEvery { senderRepo.upsert(any()) } returns Unit.right()

        useCase(walletId, 2_000L)

        coVerify {
            senderRepo.upsert(
                match {
                    it.historicalLowerBound == Instant.ofEpochMilli(2_000L) && it.watermark == null
                },
            )
        }
    }

    @Test
    fun sameOrShorterPeriod_leavesLinkUntouched_syncStaysIncremental() = runTest {
        val linked = link(
            historicalLowerBound = Instant.ofEpochMilli(2_000L),
            watermark = Instant.ofEpochMilli(9_000L),
        )
        coEvery { senderRepo.findByAccountId(walletId) } returns listOf(linked).right()

        useCase(walletId, 2_000L).isRight() shouldBe true
        useCase(walletId, 5_000L).isRight() shouldBe true

        coVerify(exactly = 0) { senderRepo.upsert(any()) }
        // Nothing moved → cached match counts stay valid.
        verify(exactly = 0) { findMatching.invalidate() }
    }

    @Test
    fun noLinkedSender_succeedsWithoutWrites() = runTest {
        coEvery { senderRepo.findByAccountId(walletId) } returns
            emptyList<SenderAccountLink>().right()

        useCase(walletId, 2_000L).isRight() shouldBe true

        coVerify(exactly = 0) { senderRepo.upsert(any()) }
    }
}
