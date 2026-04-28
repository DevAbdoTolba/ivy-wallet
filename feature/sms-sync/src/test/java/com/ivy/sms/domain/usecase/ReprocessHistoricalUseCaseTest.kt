package com.ivy.sms.domain.usecase

import arrow.core.right
import com.ivy.base.TestDispatchersProvider
import com.ivy.data.repository.TransactionRepository
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.data.SmsInboxDataSource
import com.ivy.sms.data.SmsMessageMapper
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.data.SmsWatermarkPreferences
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.model.TransactionClassification
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardMapping
import com.ivy.sms.domain.model.WildcardSlot
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.UUID

class ReprocessHistoricalUseCaseTest {

    private val templateRepo = mockk<SmsTemplateRepository>()
    private val inbox = mockk<SmsInboxDataSource>()
    private val watermarks = mockk<SmsWatermarkPreferences>()
    private val senderRepo = mockk<SenderAccountLinkRepository>()
    private val transactionRepo = mockk<TransactionRepository>()
    private val smsMessageMapper = SmsMessageMapper()
    private val route = mockk<RouteSmsUseCase>(relaxed = true)

    private val useCase = ReprocessHistoricalUseCase(
        templateRepo = templateRepo,
        inbox = inbox,
        watermarks = watermarks,
        senderRepo = senderRepo,
        transactionRepo = transactionRepo,
        smsMessageMapper = smsMessageMapper,
        route = route,
        dispatchers = TestDispatchersProvider,
    )

    @Test
    fun confirm_rejectsInvalidToken() = runTest {
        val tplId = SmsTemplateId(UUID.randomUUID())
        coEvery { templateRepo.findById(tplId) } returns activeTemplate(tplId).right()

        val result = useCase.confirm(tplId, "wrong-token")

        result.leftOrNull()!! shouldStartWith "VALIDATION"
    }

    @Test
    fun preview_excludesAlreadyImportedDedupKeys() = runTest {
        val tplId = SmsTemplateId(UUID.randomUUID())
        coEvery { templateRepo.findById(tplId) } returns activeTemplate(tplId).right()
        coEvery { watermarks.scanLowerBound() } returns 0L.right()
        coEvery { inbox.read(any(), any()) } returns emptyList<com.ivy.sms.data.SmsRow>().right()
        coEvery { transactionRepo.findAll() } returns emptyList()

        val result = useCase.preview(tplId).getOrNull()!!

        result.matchingMessages shouldBe 0
        result.newTransactionsToCreate shouldBe 0
    }

    private fun activeTemplate(id: SmsTemplateId): SmsTemplate = SmsTemplate(
        id = id,
        pattern = "test",
        wildcardSlots = listOf(WildcardSlot(WildcardId(UUID.randomUUID()), 0, "", WildcardMapping.Amount)),
        state = TemplateState.ACTIVE,
        classification = TransactionClassification.EXPENSE,
        senderIdHint = "TestBank",
        firstSeen = Instant.EPOCH,
        lastSeen = Instant.EPOCH,
        matchCount = 1,
    )
}
