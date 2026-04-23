package com.ivy.data.db.entity

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ivy.base.kotlinxserilzation.KSerializerInstant
import com.ivy.base.kotlinxserilzation.KSerializerUUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Suppress("DataClassDefaultValues")
@Keep
@Serializable
@Entity(
    tableName = "loan_items",
    foreignKeys = [
        ForeignKey(
            entity = LoanEntity::class,
            parentColumns = ["id"],
            childColumns = ["contactId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["contactId"])]
)
data class LoanItemEntity(
    @SerialName("contactId")
    @Serializable(with = KSerializerUUID::class)
    val contactId: UUID,
    
    @SerialName("amount")
    val amount: Double,
    
    @SerialName("title")
    val title: String,
    
    @SerialName("isSettled")
    val isSettled: Boolean = false,
    
    @SerialName("createdAt")
    @Serializable(with = KSerializerInstant::class)
    val createdAt: Instant,

    @PrimaryKey
    @SerialName("id")
    @Serializable(with = KSerializerUUID::class)
    val id: UUID = UUID.randomUUID()
)
