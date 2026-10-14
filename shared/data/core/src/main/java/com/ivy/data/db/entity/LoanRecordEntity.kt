package com.ivy.data.db.entity

import androidx.annotation.Keep
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.ivy.base.kotlinxserilzation.KSerializerInstant
import com.ivy.base.kotlinxserilzation.KSerializerUUID
import com.ivy.base.model.LoanRecordType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.UUID

@Suppress("DataClassDefaultValues")
@Keep
@Serializable
@Entity(
    tableName = "loan_records",
    indices = [Index(value = ["loanItemId"]), Index(value = ["transactionId"])],
)
data class LoanRecordEntity(
    @SerialName("loanId")
    @Serializable(with = KSerializerUUID::class)
    val loanId: UUID,
    @SerialName("amount")
    val amount: Double,
    @SerialName("note")
    val note: String? = null,
    @SerialName("dateTime")
    @Serializable(with = KSerializerInstant::class)
    val dateTime: Instant,
    @SerialName("interest")
    val interest: Boolean = false,
    @SerialName("accountId")
    @Serializable(with = KSerializerUUID::class)
    val accountId: UUID? = null,
    // This is used store the converted amount for currencies which are different from the loan account currency
    @SerialName("convertedAmount")
    val convertedAmount: Double? = null,
    // In order to keep backups valid, loanRecordType is by default DECREASE.
    // This is because before issue 2740 all records were of this type implicitly.
    @SerialName("loanRecordType")
    val loanRecordType: LoanRecordType = LoanRecordType.DECREASE,
    /** Checklist entry (`loan_items` row) this payment targets. NULL =
     *  loan-level payment (historical semantics). An entry's paid amount and
     *  settled state derive from the sum of its records (migration 136→137). */
    @SerialName("loanItemId")
    @Serializable(with = KSerializerUUID::class)
    val loanItemId: UUID? = null,

    @SerialName("transactionId")
    @Serializable(with = KSerializerUUID::class)
    val transactionId: UUID? = null,

    @Deprecated("Obsolete field used for cloud sync. Can't be deleted because of backwards compatibility")
    @SerialName("isSynced")
    val isSynced: Boolean = false,
    @Deprecated("Obsolete field used for cloud sync. Can't be deleted because of backwards compatibility")
    @SerialName("isDeleted")
    val isDeleted: Boolean = false,

    @PrimaryKey
    @SerialName("id")
    @Serializable(with = KSerializerUUID::class)
    val id: UUID = UUID.randomUUID()
)
