package com.ivy.sms.data

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import com.ivy.data.db.entity.PendingReviewItemEntity
import com.ivy.sms.domain.model.PendingReviewItem
import com.ivy.sms.domain.model.PendingReviewItemId
import com.ivy.sms.domain.model.QuarantineReason
import com.ivy.sms.domain.model.SmsMessage
import com.ivy.sms.domain.model.SmsTemplate
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

/**
 * Body is stored as plain TEXT — no JSON round-trip on this column.
 * Templates are loaded by id from the SmsTemplateRepository in callers, not embedded here.
 */
class PendingReviewItemMapper @Inject constructor() {

    suspend fun PendingReviewItemEntity.toDomain(template: SmsTemplate): Either<String, PendingReviewItem> = either {
        val itemUuid = catch({ UUID.fromString(id) }) {
            raise("PENDING_ID_DECODE_ERROR: ${it.message}")
        }
        PendingReviewItem(
            id = PendingReviewItemId(itemUuid),
            sms = SmsMessage(
                dedupKey = dedupKey,
                senderId = senderId,
                body = body,
                timestamp = Instant.ofEpochMilli(messageEpochMillis),
            ),
            template = template,
            quarantineReason = QuarantineReason.valueOf(quarantineReason),
            enqueuedAt = Instant.ofEpochMilli(enqueuedAtEpochMillis),
        )
    }

    fun PendingReviewItem.toEntity(): PendingReviewItemEntity = PendingReviewItemEntity(
        id = id.value.toString(),
        dedupKey = sms.dedupKey,
        senderId = sms.senderId,
        body = sms.body,
        messageEpochMillis = sms.timestamp.toEpochMilli(),
        templateId = template.id.value.toString(),
        quarantineReason = quarantineReason.name,
        enqueuedAtEpochMillis = enqueuedAt.toEpochMilli(),
    )
}
