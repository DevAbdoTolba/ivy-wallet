package com.ivy.data.db.dao.write

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.ivy.data.db.entity.SenderAccountLinkEntity

@Dao
interface WriteSenderAccountLinkDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: SenderAccountLinkEntity)

    @Query("DELETE FROM sender_account_link WHERE senderId = :senderId")
    suspend fun delete(senderId: String)

    @Query("DELETE FROM sender_account_link")
    suspend fun deleteAll()

    @Upsert
    suspend fun upsertMany(values: List<SenderAccountLinkEntity>)

    @Transaction
    suspend fun replaceAll(items: List<SenderAccountLinkEntity>) {
        deleteAll()
        upsertMany(items)
    }
}
