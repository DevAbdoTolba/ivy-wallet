package com.ivy.data.db.entity

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Suppress("DataClassDefaultValues")
@Keep
@Entity(
    tableName = "sender_account_link",
    indices = [Index(value = ["accountId"])],
)
data class SenderAccountLinkEntity(
    @PrimaryKey
    val senderId: String,
    val accountId: String,
    val linkedAtEpochMillis: Long,
)
