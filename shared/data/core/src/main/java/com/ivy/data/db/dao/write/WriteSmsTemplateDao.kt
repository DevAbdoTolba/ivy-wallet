package com.ivy.data.db.dao.write

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.ivy.data.db.entity.SmsTemplateEntity

@Dao
interface WriteSmsTemplateDao {
    @Upsert
    suspend fun upsert(value: SmsTemplateEntity)

    @Query("DELETE FROM sms_template WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM sms_template")
    suspend fun deleteAll()

    @Upsert
    suspend fun upsertMany(values: List<SmsTemplateEntity>)

    @Transaction
    suspend fun replaceAll(items: List<SmsTemplateEntity>) {
        deleteAll()
        upsertMany(items)
    }
}
