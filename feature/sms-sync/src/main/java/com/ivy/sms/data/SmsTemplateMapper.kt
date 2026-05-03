package com.ivy.sms.data

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import com.ivy.data.db.entity.SmsTemplateEntity
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.domain.model.WildcardSlot
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

@Serializable
internal data class WildcardSlotJson(
    @SerialName("id") val id: String,
    @SerialName("positionInPattern") val positionInPattern: Int,
    @SerialName("contextSnippet") val contextSnippet: String,
    @SerialName("exampleValue") val exampleValue: String = "",
    @SerialName("role") val role: String,
)

class SmsTemplateMapper @Inject constructor(
    private val json: Json,
) {
    suspend fun SmsTemplateEntity.toDomain(): Either<String, SmsTemplate> = either {
        val slots = catch({
            json.decodeFromString<List<WildcardSlotJson>>(wildcardSlotsJson)
        }) {
            raise("WILDCARD_DECODE_ERROR: ${it.message}")
        }

        SmsTemplate(
            id = SmsTemplateId(UUID.fromString(id)),
            pattern = pattern,
            exampleBody = exampleBody,
            wildcardSlots = slots.map { it.toDomain() },
            state = TemplateState.valueOf(state),
            senderIdHint = senderIdHint,
            firstSeen = Instant.ofEpochMilli(firstSeenEpochMillis),
            lastSeen = Instant.ofEpochMilli(lastSeenEpochMillis),
            matchCount = matchCount,
            name = name,
        )
    }

    fun SmsTemplate.toEntity(): SmsTemplateEntity {
        val jsonStr = json.encodeToString(wildcardSlots.map { it.toJson() })
        return SmsTemplateEntity(
            id = id.value.toString(),
            pattern = pattern,
            exampleBody = exampleBody,
            wildcardSlotsJson = jsonStr,
            state = state.name,
            senderIdHint = senderIdHint,
            firstSeenEpochMillis = firstSeen.toEpochMilli(),
            lastSeenEpochMillis = lastSeen.toEpochMilli(),
            matchCount = matchCount,
            name = name,
        )
    }
}

internal fun WildcardSlotJson.toDomain(): WildcardSlot = WildcardSlot(
    id = WildcardId(UUID.fromString(id)),
    positionInPattern = positionInPattern,
    contextSnippet = contextSnippet,
    exampleValue = exampleValue,
    role = roleFromString(role),
)

internal fun WildcardSlot.toJson(): WildcardSlotJson = WildcardSlotJson(
    id = id.value.toString(),
    positionInPattern = positionInPattern,
    contextSnippet = contextSnippet,
    exampleValue = exampleValue,
    role = role.asString(),
)

internal fun WildcardRole.asString(): String = when (this) {
    WildcardRole.Unmapped -> "Unmapped"
    WildcardRole.Income -> "Income"
    WildcardRole.Expense -> "Expense"
    WildcardRole.Transfer -> "Transfer"
    WildcardRole.CurrentTotal -> "CurrentTotal"
    WildcardRole.TransactionFee -> "TransactionFee"
    WildcardRole.DateFull -> "DateFull"
    WildcardRole.DateOnly -> "DateOnly"
    WildcardRole.TimeOnly -> "TimeOnly"
    WildcardRole.Merchant -> "Merchant"
    WildcardRole.Ignored -> "Ignored"
}

internal fun roleFromString(value: String): WildcardRole = when (value) {
    "Income" -> WildcardRole.Income
    "Expense" -> WildcardRole.Expense
    "Transfer" -> WildcardRole.Transfer
    "CurrentTotal" -> WildcardRole.CurrentTotal
    "TransactionFee" -> WildcardRole.TransactionFee
    "DateFull" -> WildcardRole.DateFull
    "DateOnly" -> WildcardRole.DateOnly
    "TimeOnly" -> WildcardRole.TimeOnly
    // Backward compat: legacy "Date" rows treated as full datetime.
    "Date" -> WildcardRole.DateFull
    "Merchant" -> WildcardRole.Merchant
    "Ignored" -> WildcardRole.Ignored
    else -> WildcardRole.Unmapped
}
