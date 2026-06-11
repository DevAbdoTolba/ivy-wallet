package com.ivy.data.db.dao.read

import androidx.room.Dao
import androidx.room.Query
import com.ivy.data.db.entity.LoanItemEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

@Dao
interface LoanItemDao {
    @Query("SELECT * FROM loan_items WHERE contactId = :contactId ORDER BY createdAt DESC, id")
    fun findAllByContactId(contactId: UUID): Flow<List<LoanItemEntity>>

    @Query("SELECT * FROM loan_items WHERE id = :id")
    suspend fun findById(id: UUID): LoanItemEntity?

    @Query("SELECT SUM(amount) FROM loan_items WHERE contactId = :contactId AND isSettled = 0")
    suspend fun calculateUnsettledSum(contactId: UUID): Double?
}
