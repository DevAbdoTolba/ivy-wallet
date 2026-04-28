package com.ivy.data.db.dao.read

import androidx.room.Dao
import androidx.room.Query
import com.ivy.data.db.entity.PendingReviewItemEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ReadPendingReviewItemDao {
    @Query("SELECT * FROM pending_review_item ORDER BY enqueuedAtEpochMillis DESC")
    suspend fun findAll(): List<PendingReviewItemEntity>

    @Query("SELECT * FROM pending_review_item WHERE templateId = :templateId")
    suspend fun findByTemplateId(templateId: String): List<PendingReviewItemEntity>

    @Query("SELECT * FROM pending_review_item WHERE dedupKey = :key LIMIT 1")
    suspend fun findByDedupKey(key: String): PendingReviewItemEntity?

    @Query("SELECT COUNT(*) FROM pending_review_item")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM pending_review_item")
    fun observeCount(): Flow<Int>

    @Query("SELECT * FROM pending_review_item ORDER BY enqueuedAtEpochMillis DESC")
    fun observeAll(): Flow<List<PendingReviewItemEntity>>
}
