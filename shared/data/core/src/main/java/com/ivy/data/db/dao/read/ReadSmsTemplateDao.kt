package com.ivy.data.db.dao.read

import androidx.room.Dao
import androidx.room.Query
import com.ivy.data.db.entity.SmsTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadSmsTemplateDao {
    @Query("SELECT * FROM sms_template ORDER BY lastSeenEpochMillis DESC")
    suspend fun findAll(): List<SmsTemplateEntity>

    @Query("SELECT * FROM sms_template WHERE id = :id")
    suspend fun findById(id: String): SmsTemplateEntity?

    @Query("SELECT * FROM sms_template WHERE pattern = :pattern LIMIT 1")
    suspend fun findByPattern(pattern: String): SmsTemplateEntity?

    @Query("SELECT * FROM sms_template WHERE state = 'ACTIVE' ORDER BY lastSeenEpochMillis DESC")
    suspend fun findActive(): List<SmsTemplateEntity>

    @Query("SELECT * FROM sms_template ORDER BY lastSeenEpochMillis DESC")
    fun observeAll(): Flow<List<SmsTemplateEntity>>
}
