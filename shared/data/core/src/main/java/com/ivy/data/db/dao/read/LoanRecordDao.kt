package com.ivy.data.db.dao.read

import androidx.room.Dao
import androidx.room.Query
import com.ivy.data.db.entity.LoanRecordEntity
import java.util.*

@Dao
interface LoanRecordDao {
    @Query("SELECT * FROM loan_records WHERE isDeleted = 0 ORDER BY dateTime DESC")
    suspend fun findAll(): List<LoanRecordEntity>

    @Query("SELECT * FROM loan_records WHERE isSynced = :synced AND isDeleted = :deleted")
    suspend fun findByIsSyncedAndIsDeleted(
        synced: Boolean,
        deleted: Boolean = false
    ): List<LoanRecordEntity>

    @Query("SELECT * FROM loan_records WHERE id = :id")
    suspend fun findById(id: UUID): LoanRecordEntity?

    @Query("SELECT * FROM loan_records WHERE loanId = :loanId AND isDeleted = 0 ORDER BY dateTime DESC")
    suspend fun findAllByLoanId(loanId: UUID): List<LoanRecordEntity>

    @Query("SELECT * FROM loan_records WHERE loanItemId = :loanItemId AND isDeleted = 0 ORDER BY dateTime DESC")
    suspend fun findAllByLoanItemId(loanItemId: UUID): List<LoanRecordEntity>

    /**
     * Amount paid toward one checklist entry: the sum of its non-interest
     * DECREASE records. Single source of truth for the entry's derived
     * paid/settled state (loanRecordType is stored as TEXT).
     */
    @Query(
        "SELECT SUM(amount) FROM loan_records WHERE loanItemId = :loanItemId " +
            "AND isDeleted = 0 AND interest = 0 AND loanRecordType = 'DECREASE'"
    )
    suspend fun sumPaidByLoanItemId(loanItemId: UUID): Double?
}
