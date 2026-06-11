package com.ivy.sms.domain.usecase

import com.ivy.data.model.AccountId
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SmsWatermarkPreferences
import com.ivy.sms.domain.model.SmsMessage
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.domain.model.WildcardSlot
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.UUID

class RouteSmsBlacklistTest {

    private val createTransaction = mockk<CreateTransactionFromSmsUseCase>(relaxed = true)
    private val pendingRepo = mockk<PendingReviewItemRepository>(relaxed = true)
    private val prefs = mockk<SmsWatermarkPreferences>(relaxed = true)
    private val route = RouteSmsUseCase(createTransaction, pendingRepo, prefs)

    @Test
    fun blacklistedTemplate_neitherCreatesTransactionNorEnqueues() = runTest {
        val template = SmsTemplate(
            id = SmsTemplateId(UUID.randomUUID()),
            pattern = "OTP <*>",
            exampleBody = "OTP 12345",
            wildcardSlots = listOf(
                WildcardSlot(
                    id = WildcardId(UUID.randomUUID()),
                    positionInPattern = 1,
                    contextSnippet = "",
                    exampleValue = "12345",
                    role = WildcardRole.Unmapped,
                ),
            ),
            state = TemplateState.BLACKLISTED,
            senderIdHint = "OTPService",
            firstSeen = Instant.EPOCH,
            lastSeen = Instant.EPOCH,
            matchCount = 1,
        )
        val message = SmsMessage(
            dedupKey = "k",
            senderId = "OTPService",
            body = "OTP 12345",
            timestamp = Instant.EPOCH,
        )

        val outcome = route(message, template, mapOf("OTPService" to AccountId(UUID.randomUUID()))).getOrNull()

        outcome.shouldBeInstanceOf<RouteOutcome.Blacklisted>()
        coVerify(exactly = 0) { createTransaction(any(), any(), any()) }
        coVerify(exactly = 0) { pendingRepo.enqueue(any()) }
    }
}
