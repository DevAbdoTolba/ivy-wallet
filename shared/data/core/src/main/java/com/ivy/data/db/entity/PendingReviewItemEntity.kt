package com.ivy.data.db.entity

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Suppress("DataClassDefaultValues")
@Keep
@Entity(
    tableName = "pending_review_item",
    indices = [
        Index(value = ["dedupKey"], unique = true),
        Index(value = ["templateId"]),
    ],
)
data class PendingReviewItemEntity(
    @PrimaryKey
    val id: String,
    val dedupKey: String,
    val senderId: String,
    val body: String,
    val messageEpochMillis: Long,
    val templateId: String,
    val quarantineReason: String,
    val enqueuedAtEpochMillis: Long,
)
