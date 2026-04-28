package com.ivy.data.db.dao.read

import androidx.room.Dao
import androidx.room.Query
import com.ivy.data.db.entity.SenderAccountLinkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadSenderAccountLinkDao {
    @Query("SELECT * FROM sender_account_link")
    suspend fun findAll(): List<SenderAccountLinkEntity>

    @Query("SELECT * FROM sender_account_link WHERE senderId = :senderId LIMIT 1")
    suspend fun findBySenderId(senderId: String): SenderAccountLinkEntity?

    @Query("SELECT * FROM sender_account_link WHERE accountId = :accountId")
    suspend fun findByAccountId(accountId: String): List<SenderAccountLinkEntity>

    @Query("SELECT * FROM sender_account_link")
    fun observeAll(): Flow<List<SenderAccountLinkEntity>>
}
