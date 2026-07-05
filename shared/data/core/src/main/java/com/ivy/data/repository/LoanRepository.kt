package com.ivy.data.repository

import arrow.core.Either
import arrow.core.raise.either
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.db.dao.read.LoanItemDao
import com.ivy.data.db.dao.read.LoanRecordDao
import com.ivy.data.db.dao.write.WriteLoanItemDao
import com.ivy.data.db.dao.write.WriteLoanRecordDao
import com.ivy.data.db.entity.LoanRecordEntity
import com.ivy.data.model.LoanId
import com.ivy.data.model.LoanItem
import com.ivy.data.model.LoanItemId
import com.ivy.data.repository.mapper.LoanItemMapper
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LoanRepository @Inject constructor(
    private val mapper: LoanItemMapper,
    private val loanItemDao: LoanItemDao,
    private val writeLoanItemDao: WriteLoanItemDao,
    private val loanRecordDao: LoanRecordDao,
    private val writeLoanRecordDao: WriteLoanRecordDao,
    private val dispatchersProvider: DispatchersProvider,
) {
    fun getLoanItems(contactId: LoanId): Flow<List<LoanItem>> {
        return loanItemDao.findAllByContactId(contactId.value).map { entities ->
            entities.mapNotNull { entity ->
                with(mapper) { entity.toDomain() }.getOrNull()
            }
        }
    }

    suspend fun saveLoanItem(item: LoanItem): Either<String, Unit> = either {
        withContext(dispatchersProvider.io) {
            writeLoanItemDao.save(with(mapper) { item.toEntity() })
        }
    }

    suspend fun updateSettledStatus(id: LoanItemId, isSettled: Boolean): Either<String, Unit> = either {
        withContext(dispatchersProvider.io) {
            writeLoanItemDao.updateSettledStatus(id.value, isSettled)
        }
    }

    suspend fun deleteLoanItem(id: LoanItemId): Either<String, Unit> = either {
        withContext(dispatchersProvider.io) {
            // FR-019: payments are money history — deleting an entry keeps its
            // records (and their transactions) as loan-level payments. Clear
            // the pointer BEFORE the row goes so no record ever dangles.
            writeLoanRecordDao.clearLoanItemId(id.value)
            writeLoanItemDao.deleteById(id.value)
        }
    }

    suspend fun getUnsettledSum(contactId: LoanId): Double = withContext(dispatchersProvider.io) {
        loanItemDao.calculateUnsettledSum(contactId.value) ?: 0.0
    }

    /** Amount paid toward one entry = sum of its non-interest DECREASE records.
     *  Single source of truth for the entry's derived paid/settled state. */
    suspend fun sumPaidForEntry(id: LoanItemId): Double = withContext(dispatchersProvider.io) {
        loanRecordDao.sumPaidByLoanItemId(id.value) ?: 0.0
    }

    /** Entity-level payment history of one entry (newest first). */
    suspend fun recordsForEntry(id: LoanItemId): List<LoanRecordEntity> =
        withContext(dispatchersProvider.io) {
            loanRecordDao.findAllByLoanItemId(id.value)
        }
}
