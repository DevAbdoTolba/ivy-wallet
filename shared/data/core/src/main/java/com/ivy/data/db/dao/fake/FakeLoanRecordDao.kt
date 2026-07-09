package com.ivy.data.db.dao.fake

import com.ivy.base.model.LoanRecordType
import com.ivy.data.db.dao.read.LoanRecordDao
import com.ivy.data.db.dao.write.WriteLoanRecordDao
import com.ivy.data.db.entity.LoanRecordEntity
import org.jetbrains.annotations.VisibleForTesting
import java.util.UUID

@VisibleForTesting
class FakeLoanRecordDao : LoanRecordDao, WriteLoanRecordDao {
    private val items = mutableListOf<LoanRecordEntity>()

    override suspend fun findAll(): List<LoanRecordEntity> {
        return items
    }

    override suspend fun findByIsSyncedAndIsDeleted(
        synced: Boolean,
        deleted: Boolean
    ): List<LoanRecordEntity> {
        TODO("Not yet implemented")
    }

    override suspend fun findById(id: UUID): LoanRecordEntity? {
        return items.find { it.id == id }
    }

    override suspend fun findAllByLoanId(loanId: UUID): List<LoanRecordEntity> {
        TODO("Not yet implemented")
    }

    override suspend fun findAllByLoanItemId(loanItemId: UUID): List<LoanRecordEntity> {
        return items
            .filter { it.loanItemId == loanItemId && !it.isDeleted }
            .sortedByDescending { it.dateTime }
    }

    override suspend fun sumPaidByLoanItemId(loanItemId: UUID): Double? {
        val paid = items.filter {
            it.loanItemId == loanItemId &&
                !it.isDeleted &&
                !it.interest &&
                it.loanRecordType == LoanRecordType.DECREASE
        }
        return if (paid.isEmpty()) null else paid.sumOf { it.amount }
    }

    override suspend fun save(value: LoanRecordEntity) {
        items.add(value)
    }

    override suspend fun saveMany(values: List<LoanRecordEntity>) {
        values.forEach { save(it) }
    }

    override suspend fun deleteById(id: UUID) {
        items.removeIf { it.id == id }
    }

    override suspend fun deleteAllByLoanItemId(loanItemId: UUID) {
        items.removeIf { it.loanItemId == loanItemId }
    }

    override suspend fun clearLoanItemId(loanItemId: UUID) {
        val updated = items.map {
            if (it.loanItemId == loanItemId) it.copy(loanItemId = null) else it
        }
        items.clear()
        items.addAll(updated)
    }

    override suspend fun deleteAll() {
        items.clear()
    }
}