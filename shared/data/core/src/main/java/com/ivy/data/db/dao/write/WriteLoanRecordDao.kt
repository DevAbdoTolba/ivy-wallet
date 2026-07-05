package com.ivy.data.db.dao.write

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.ivy.data.db.entity.LoanRecordEntity
import java.util.UUID

@Dao
interface WriteLoanRecordDao {
    @Upsert
    suspend fun save(value: LoanRecordEntity)

    @Upsert
    suspend fun saveMany(value: List<LoanRecordEntity>)

    @Query("DELETE FROM loan_records WHERE id = :id")
    suspend fun deleteById(id: UUID)

    /** Un-settle cascade: removes every payment targeting the entry. The
     *  caller (UnsettleLoanEntryUseCase) deletes the records' transactions
     *  alongside — never call this without handling those. */
    @Query("DELETE FROM loan_records WHERE loanItemId = :loanItemId")
    suspend fun deleteAllByLoanItemId(loanItemId: UUID)

    /** Entry deletion (FR-019): payments are money history and survive as
     *  loan-level records — only the entry pointer is cleared. */
    @Query("UPDATE loan_records SET loanItemId = NULL WHERE loanItemId = :loanItemId")
    suspend fun clearLoanItemId(loanItemId: UUID)

    @Query("DELETE FROM loan_records")
    suspend fun deleteAll()
}
