package com.ivy.sms.data

import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
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
            exampleBody = "Order 12.34 at Cafe",
            wildcardSlots = listOf(
                WildcardSlot(
                    id = WildcardId(UUID.fromString("00000000-0000-0000-0000-000000000002")),
                    positionInPattern = 1,
                    contextSnippet = "Order <*> at",
                    exampleValue = "12.34",
                    role = WildcardRole.Expense,
                ),
            ),
            state = TemplateState.ACTIVE,
            senderIdHint = "TestBank",
            firstSeen = Instant.ofEpochMilli(1_000_000),
            lastSeen = Instant.ofEpochMilli(2_000_000),
            matchCount = 5,
        )
        val entity = with(mapper) { original.toEntity() }
        val roundtrip = with(mapper) { entity.toDomain() }.getOrNull()!!
        roundtrip shouldBe original
    }

    @Test
    fun allRoles_roundTrip() = runTest {
        val roles = listOf(
            WildcardRole.Income,
            WildcardRole.Expense,
            WildcardRole.Transfer,
            WildcardRole.CurrentTotal,
            WildcardRole.TransactionFee,
            WildcardRole.DateFull,
            WildcardRole.DateOnly,
            WildcardRole.TimeOnly,
            WildcardRole.Merchant,
            WildcardRole.Ignored,
            WildcardRole.Unmapped,
        )
        for (role in roles) {
            val slot = WildcardSlot(
                id = WildcardId(UUID.randomUUID()),
                positionInPattern = 0,
                contextSnippet = "<*>",
                exampleValue = "x",
                role = role,
            )
            val template = SmsTemplate(
                id = SmsTemplateId(UUID.randomUUID()),
                pattern = "<*>",
                exampleBody = "x",
                wildcardSlots = listOf(slot),
                state = TemplateState.UNMAPPED,
                senderIdHint = "S",
                firstSeen = Instant.ofEpochMilli(0),
                lastSeen = Instant.ofEpochMilli(0),
                matchCount = 1,
            )
            val entity = with(mapper) { template.toEntity() }
            val back = with(mapper) { entity.toDomain() }.getOrNull()!!
            back.wildcardSlots.first().role shouldBe role
        }
    }
}
