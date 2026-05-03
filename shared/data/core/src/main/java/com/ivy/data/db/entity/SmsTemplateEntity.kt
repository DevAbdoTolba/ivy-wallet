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
    val exampleBody: String,
    val wildcardSlotsJson: String,
    val state: String,
    val senderIdHint: String,
    val firstSeenEpochMillis: Long,
    val lastSeenEpochMillis: Long,
    val matchCount: Int = 0,
    /** User-supplied label, used as the resulting transaction's title (FR-024,
     *  added 2026-05-02). Null until the user names this template. */
    val name: String? = null,
)
