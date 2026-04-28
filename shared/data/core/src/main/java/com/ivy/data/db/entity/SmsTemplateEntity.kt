package com.ivy.data.db.entity

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.PrimaryKey

@Suppress("DataClassDefaultValues")
@Keep
@Entity(tableName = "sms_template")
data class SmsTemplateEntity(
    @PrimaryKey
    val id: String,
    val pattern: String,
    val wildcardSlotsJson: String,
    val state: String,
    val classification: String?,
    val senderIdHint: String,
    val firstSeenEpochMillis: Long,
    val lastSeenEpochMillis: Long,
    val matchCount: Int = 0,
)
