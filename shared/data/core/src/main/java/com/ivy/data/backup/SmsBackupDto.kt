package com.ivy.data.backup

import androidx.annotation.Keep
import com.ivy.data.db.entity.PendingReviewItemEntity
import com.ivy.data.db.entity.SenderAccountLinkEntity
import com.ivy.data.db.entity.SmsTemplateEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Keep
@Serializable
data class SmsTemplateBackupDto(
    @SerialName("id") val id: String,
    @SerialName("pattern") val pattern: String,
    @SerialName("exampleBody") val exampleBody: String = "",
    @SerialName("wildcardSlotsJson") val wildcardSlotsJson: String,
    @SerialName("state") val state: String,
    @SerialName("senderIdHint") val senderIdHint: String,
    @SerialName("firstSeenEpochMillis") val firstSeenEpochMillis: Long,
    @SerialName("lastSeenEpochMillis") val lastSeenEpochMillis: Long,
    @SerialName("matchCount") val matchCount: Int = 0,
    @SerialName("name") val name: String? = null,
)

@Keep
@Serializable
data class SenderAccountLinkBackupDto(
    @SerialName("senderId") val senderId: String,
    @SerialName("accountId") val accountId: String,
    @SerialName("linkedAtEpochMillis") val linkedAtEpochMillis: Long,
    @SerialName("historicalLowerBoundEpochMillis") val historicalLowerBoundEpochMillis: Long? = null,
    @SerialName("watermarkEpochMillis") val watermarkEpochMillis: Long? = null,
)

internal fun SmsTemplateEntity.toBackupDto(): SmsTemplateBackupDto = SmsTemplateBackupDto(
    id = id,
    pattern = pattern,
    exampleBody = exampleBody,
    wildcardSlotsJson = wildcardSlotsJson,
    state = state,
    senderIdHint = senderIdHint,
    firstSeenEpochMillis = firstSeenEpochMillis,
    lastSeenEpochMillis = lastSeenEpochMillis,
    matchCount = matchCount,
    name = name,
)

internal fun SmsTemplateBackupDto.toEntity(): SmsTemplateEntity = SmsTemplateEntity(
    id = id,
    pattern = pattern,
    exampleBody = exampleBody,
    wildcardSlotsJson = wildcardSlotsJson,
    state = state,
    senderIdHint = senderIdHint,
    firstSeenEpochMillis = firstSeenEpochMillis,
    lastSeenEpochMillis = lastSeenEpochMillis,
    matchCount = matchCount,
    name = name,
)

internal fun SenderAccountLinkEntity.toBackupDto(): SenderAccountLinkBackupDto = SenderAccountLinkBackupDto(
    senderId = senderId,
    accountId = accountId,
    linkedAtEpochMillis = linkedAtEpochMillis,
    historicalLowerBoundEpochMillis = historicalLowerBoundEpochMillis,
    watermarkEpochMillis = watermarkEpochMillis,
)

internal fun SenderAccountLinkBackupDto.toEntity(): SenderAccountLinkEntity = SenderAccountLinkEntity(
    senderId = senderId,
    accountId = accountId,
    linkedAtEpochMillis = linkedAtEpochMillis,
    historicalLowerBoundEpochMillis = historicalLowerBoundEpochMillis,
    watermarkEpochMillis = watermarkEpochMillis,
)

@Suppress("UnusedReceiverParameter")
internal fun PendingReviewItemEntity.swallow(): Nothing? = null
