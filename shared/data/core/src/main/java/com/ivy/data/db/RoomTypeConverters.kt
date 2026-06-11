package com.ivy.domain.db

import androidx.room.TypeConverter
import com.ivy.base.legacy.Theme
import com.ivy.base.legacy.epochMilliToDateTime
import com.ivy.base.legacy.toEpochMilli
import com.ivy.base.model.LoanRecordType
import com.ivy.base.model.TransactionType
import com.ivy.data.model.IntervalType
import com.ivy.data.model.LoanType
import java.time.Instant
import java.time.LocalDateTime
import java.util.*

@SuppressWarnings("unused")
class RoomTypeConverters {
    @TypeConverter
    fun saveDate(localDateTime: LocalDateTime?): Long? = localDateTime?.toEpochMilli()

    @TypeConverter
    fun parseDate(timestampMillis: Long?): LocalDateTime? = timestampMillis?.epochMilliToDateTime()

    @TypeConverter
    fun saveUUID(id: UUID?) = id?.toString()

    @TypeConverter
    fun parseUUID(id: String?) = id?.let { UUID.fromString(it.withUuidDashes()) }

    // Migration130to131 used to seed loan_items.id with 32-char dash-less hex
    // strings, which UUID.fromString rejects. Re-insert the dashes defensively.
    @Suppress("MagicNumber")
    private fun String.withUuidDashes(): String =
        if (length == 32 && '-' !in this) {
            "${substring(0, 8)}-${substring(8, 12)}-${substring(12, 16)}-" +
                "${substring(16, 20)}-${substring(20)}"
        } else {
            this
        }

    @TypeConverter
    fun saveTheme(value: Theme?) = value?.name

    @TypeConverter
    fun parseTheme(value: String?) = value?.let { Theme.valueOf(it) }

    @TypeConverter
    fun saveTransactionType(value: TransactionType?) = value?.name

    @TypeConverter
    fun parseTransactionType(value: String?) = value?.let { TransactionType.valueOf(it) }

    @TypeConverter
    fun saveRecurringIntervalType(value: IntervalType?) = value?.name

    @TypeConverter
    fun parseRecurringIntervalType(value: String?) =
        value?.let { IntervalType.valueOf(it) }

    @TypeConverter
    fun saveLoanType(value: LoanType?) = value?.name

    @TypeConverter
    fun parseLoanType(value: String?) = value?.let { LoanType.valueOf(it) }

    @TypeConverter
    fun saveInstant(value: Instant): Long = value.toEpochMilli()

    @TypeConverter
    fun parseInstant(value: Long): Instant = Instant.ofEpochMilli(value)

    @TypeConverter
    fun saveLoanRecordType(value: LoanRecordType?): String? = value?.name

    @TypeConverter
    fun parseLoanRecordType(value: String?): LoanRecordType? =
        value?.let { LoanRecordType.valueOf(it) }
}
