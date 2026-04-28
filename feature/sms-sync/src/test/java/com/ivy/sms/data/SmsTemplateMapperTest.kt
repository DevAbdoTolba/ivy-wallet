package com.ivy.sms.data

import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.model.TransactionClassification
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardMapping
import com.ivy.sms.domain.model.WildcardSlot
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Test
import java.time.Instant
import java.util.UUID

class SmsTemplateMapperTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val mapper = SmsTemplateMapper(json)

    @Test
    fun roundTrip_preservesAllFields() = runTest {
        val original = SmsTemplate(
            id = SmsTemplateId(UUID.fromString("00000000-0000-0000-0000-000000000001")),
            pattern = "Order <*> at Cafe",
            wildcardSlots = listOf(
                WildcardSlot(
                    id = WildcardId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
                    positionInPattern = 1,
                    contextSnippet = "Order <*> at",
                    mapping = WildcardMapping.Amount,
                ),
            ),
            state = TemplateState.ACTIVE,
            classification = TransactionClassification.EXPENSE,
            senderIdHint = "TestBank",
            firstSeen = Instant.ofEpochMilli(1_000_000),
            lastSeen = Instant.ofEpochMilli(2_000_000),
            matchCount = 5,
        )
        val entity = with(mapper) { original.toEntity() }
        val roundtrip = with(mapper) { entity.toDomain() }.getOrNull()!!
        roundtrip shouldBe original
    }
}
