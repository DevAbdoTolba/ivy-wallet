package com.ivy.sms.data

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import com.ivy.data.db.entity.SmsTemplateEntity
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.model.TransactionClassification
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardMapping
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
    @SerialName("mapping") val mapping: String,
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
            wildcardSlots = slots.map { it.toDomain() },
            state = TemplateState.valueOf(state),
            classification = classification?.let(TransactionClassification::valueOf),
            senderIdHint = senderIdHint,
            firstSeen = Instant.ofEpochMilli(firstSeenEpochMillis),
            lastSeen = Instant.ofEpochMilli(lastSeenEpochMillis),
            matchCount = matchCount,
        )
    }

    fun SmsTemplate.toEntity(): SmsTemplateEntity {
        val jsonStr = json.encodeToString(wildcardSlots.map { it.toJson() })
        return SmsTemplateEntity(
            id = id.value.toString(),
            pattern = pattern,
            wildcardSlotsJson = jsonStr,
            state = state.name,
            classification = classification?.name,
            senderIdHint = senderIdHint,
            firstSeenEpochMillis = firstSeen.toEpochMilli(),
            lastSeenEpochMillis = lastSeen.toEpochMilli(),
            matchCount = matchCount,
        )
    }
}

internal fun WildcardSlotJson.toDomain(): WildcardSlot = WildcardSlot(
    id = WildcardId(UUID.fromString(id)),
    positionInPattern = positionInPattern,
    contextSnippet = contextSnippet,
    mapping = mappingFromString(mapping),
)

internal fun WildcardSlot.toJson(): WildcardSlotJson = WildcardSlotJson(
    id = id.value.toString(),
    positionInPattern = positionInPattern,
    contextSnippet = contextSnippet,
    mapping = mapping.asString(),
)

internal fun WildcardMapping.asString(): String = when (this) {
    WildcardMapping.Amount -> "Amount"
    WildcardMapping.DateTime -> "DateTime"
    WildcardMapping.Ignored -> "Ignored"
    WildcardMapping.Merchant -> "Merchant"
    WildcardMapping.Reference -> "Reference"
    WildcardMapping.Unmapped -> "Unmapped"
}

internal fun mappingFromString(value: String): WildcardMapping = when (value) {
    "Amount" -> WildcardMapping.Amount
    "DateTime" -> WildcardMapping.DateTime
    "Ignored" -> WildcardMapping.Ignored
    "Merchant" -> WildcardMapping.Merchant
    "Reference" -> WildcardMapping.Reference
    else -> WildcardMapping.Unmapped
}
