package com.ivy.sms.domain.usecase

import arrow.core.right
import com.ivy.sms.data.SmsInboxDataSource
import com.ivy.sms.data.SmsMessageMapper
import com.ivy.sms.data.SmsRow
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
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.UUID

class FindMatchingMessagesUseCaseTest {

    private val inbox = mockk<SmsInboxDataSource>()
    private val mapper = SmsMessageMapper()
    private val watermarks = mockk<SmsWatermarkPreferences>()

    private val findMatching = FindMatchingMessagesUseCase(inbox, mapper, watermarks)

    private fun template(
        id: SmsTemplateId = SmsTemplateId(UUID.randomUUID()),
        pattern: String = "Spent <*> EGP",
    ) = SmsTemplate(
        id = id,
        pattern = pattern,
        exampleBody = "Spent 10 EGP",
        wildcardSlots = listOf(
            WildcardSlot(WildcardId(UUID.randomUUID()), 1, "", "10", WildcardRole.Expense),
        ),
        state = TemplateState.ACTIVE,
        senderIdHint = "TestBank",
        firstSeen = Instant.EPOCH,
        lastSeen = Instant.EPOCH,
        matchCount = 1,
    )

    private fun stubInbox(vararg bodies: String) {
        coEvery { watermarks.scanLowerBound() } returns 0L.right()
        coEvery { inbox.read(any(), any(), any()) } returns
            bodies.mapIndexed { idx, body ->
                SmsRow(id = idx.toLong(), address = "TestBank", dateEpochMillis = 1000L + idx, body = body)
            }.right()
    }

    @Test
    fun countAndBodies_comeFromAlignment() = runTest {
        stubInbox("Spent 10 EGP", "unrelated promo text")

        val matches = findMatching(template()).getOrNull()!!

        matches.size shouldBe 1
        matches.single().body shouldBe "Spent 10 EGP"
    }

    @Test
    fun cachesPerTemplate_butNotAcrossPatternEdits() = runTest {
        stubInbox("Spent 10 EGP")
        val tpl = template()

        findMatching(tpl)
        findMatching(tpl)
        // Same id + same pattern → served from cache.
        coVerify(exactly = 1) { inbox.read(any(), any(), any()) }

        // Same template ID, edited pattern (MapTemplate upserts the SAME id):
        // the cache must NOT serve the pre-edit result.
        findMatching(tpl.copy(pattern = "Spent <*> EGP today"))
        coVerify(exactly = 2) { inbox.read(any(), any(), any()) }
    }

    @Test
    fun invalidateByTemplateId_dropsOnlyThatTemplate() = runTest {
        stubInbox("Spent 10 EGP")
        val tplA = template()
        val tplB = template()

        findMatching(tplA)
        findMatching(tplB)
        coVerify(exactly = 2) { inbox.read(any(), any(), any()) }

        findMatching.invalidate(tplA.id)

        findMatching(tplA) // re-reads — its entry was dropped
        findMatching(tplB) // still cached
        coVerify(exactly = 3) { inbox.read(any(), any(), any()) }
    }
}
