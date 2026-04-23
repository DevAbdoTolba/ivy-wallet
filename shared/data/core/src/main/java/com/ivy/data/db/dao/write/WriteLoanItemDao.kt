package com.ivy.data.db.dao.write

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.ivy.data.db.entity.LoanItemEntity
import java.util.UUID

@Dao
interface WriteLoanItemDao {
    @Upsert
    suspend fun save(value: LoanItemEntity)

    @Query("UPDATE loan_items SET isSettled = :isSettled WHERE id = :id")
    suspend fun updateSettledStatus(id: UUID, isSettled: Boolean)

    @Query("DELETE FROM loan_items WHERE id = :id")
    suspend fun deleteById(id: UUID)

    @Query("DELETE FROM loan_items WHERE contactId = :contactId")
    suspend fun deleteByContactId(contactId: UUID)
}
