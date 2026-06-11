package com.ivy.sms.data

import com.ivy.base.TestDispatchersProvider
import com.ivy.data.db.dao.read.ReadSenderAccountLinkDao
import com.ivy.data.db.dao.write.WriteSenderAccountLinkDao
import com.ivy.data.db.entity.SenderAccountLinkEntity
import com.ivy.data.model.Account
import com.ivy.data.model.AccountId
import com.ivy.data.model.primitive.AssetCode
import com.ivy.data.model.primitive.ColorInt
import com.ivy.data.model.primitive.NotBlankTrimmedString
import com.ivy.data.repository.AccountRepository
import com.ivy.sms.domain.model.SenderAccountLink
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.UUID

class SenderAccountLinkRepositoryTest {

    private val readDao = mockk<ReadSenderAccountLinkDao>()
    private val writeDao = mockk<WriteSenderAccountLinkDao>()
    private val accountRepo = mockk<AccountRepository>()
    private val mapper = SenderAccountLinkMapper()

    private val repo = SenderAccountLinkRepositoryImpl(
        readDao = readDao,
        writeDao = writeDao,
        mapper = mapper,
        accountRepository = accountRepo,
        dispatchers = TestDispatchersProvider,
    )

    @Test
    fun upsert_returnsLinkConflict_whenSenderAlreadyLinkedElsewhere() = runTest {
        val newAccountId = AccountId(UUID.randomUUID())
        val existingAccountUuid = UUID.randomUUID()
        val link = SenderAccountLink(
            senderId = "ChaseAlerts",
            accountId = newAccountId,
            linkedAt = Instant.ofEpochMilli(1_700_000_000_000L),
        )
        coEvery { readDao.findBySenderId("ChaseAlerts") } returns SenderAccountLinkEntity(
            senderId = "ChaseAlerts",
            accountId = existingAccountUuid.toString(),
            linkedAtEpochMillis = 1_000_000L,
            historicalLowerBoundEpochMillis = null,
            watermarkEpochMillis = null,
        )
        coEvery { accountRepo.findById(AccountId(existingAccountUuid)) } returns Account(
            id = AccountId(existingAccountUuid),
            name = NotBlankTrimmedString.from("Personal Checking").getOrNull()!!,
            asset = AssetCode.from("USD").getOrNull()!!,
            color = ColorInt(0xFF000000.toInt()),
            icon = null,
            includeInBalance = true,
            orderNum = 0.0,
        )

        val result = repo.upsert(link)

        val errorString = result.leftOrNull()
        errorString shouldBe errorString
        requireNotNull(errorString)
        errorString shouldStartWith "LINK_CONFLICT"
        errorString shouldContain "Personal Checking"
    }

    @Test
    fun upsert_succeeds_whenSenderUnlinked() = runTest {
        val accountId = AccountId(UUID.randomUUID())
        val link = SenderAccountLink(
            senderId = "FreshSender",
            accountId = accountId,
            linkedAt = Instant.ofEpochMilli(1_700_000_000_000L),
        )
        coEvery { readDao.findBySenderId("FreshSender") } returns null
        coJustRun { writeDao.delete("FreshSender") }
        coJustRun { writeDao.insert(any()) }

        val result = repo.upsert(link)

        result.isRight() shouldBe true
    }
}
