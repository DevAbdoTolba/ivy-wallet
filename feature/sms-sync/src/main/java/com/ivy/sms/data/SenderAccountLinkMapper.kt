package com.ivy.sms.data

import arrow.core.Either
import arrow.core.raise.catch
import arrow.core.raise.either
import com.ivy.data.db.entity.SenderAccountLinkEntity
import com.ivy.data.model.AccountId
import com.ivy.sms.domain.model.SenderAccountLink
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class SenderAccountLinkMapper @Inject constructor() {
    suspend fun SenderAccountLinkEntity.toDomain(): Either<String, SenderAccountLink> = either {
        val accountUuid = catch({ UUID.fromString(accountId) }) {
            raise("ACCOUNT_ID_DECODE_ERROR: ${it.message}")
        }
        SenderAccountLink(
            senderId = senderId,
            accountId = AccountId(accountUuid),
            linkedAt = Instant.ofEpochMilli(linkedAtEpochMillis),
            historicalLowerBound = historicalLowerBoundEpochMillis?.let(Instant::ofEpochMilli),
            watermark = watermarkEpochMillis?.let(Instant::ofEpochMilli),
        )
    }

    fun SenderAccountLink.toEntity(): SenderAccountLinkEntity = SenderAccountLinkEntity(
        senderId = senderId,
        accountId = accountId.value.toString(),
        linkedAtEpochMillis = linkedAt.toEpochMilli(),
        historicalLowerBoundEpochMillis = historicalLowerBound?.toEpochMilli(),
        watermarkEpochMillis = watermark?.toEpochMilli(),
    )
}
