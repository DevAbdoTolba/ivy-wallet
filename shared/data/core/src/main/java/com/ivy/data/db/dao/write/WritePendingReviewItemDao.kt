package com.ivy.data.db.dao.write

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.ivy.data.db.entity.PendingReviewItemEntity

@Dao
interface WritePendingReviewItemDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: PendingReviewItemEntity)

    @Query("DELETE FROM pending_review_item WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM pending_review_item WHERE templateId = :templateId")
    suspend fun deleteByTemplateId(templateId: String)

    @Query("DELETE FROM pending_review_item")
    suspend fun deleteAll()
}
