package com.ivy.data.repository

import arrow.core.Either
import arrow.core.raise.either
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.db.dao.read.LoanItemDao
import com.ivy.data.db.dao.write.WriteLoanItemDao
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
    private val dispatchersProvider: DispatchersProvider,
) {
    fun getLoanItems(contactId: LoanId): Flow<List<LoanItem>> {
        return loanItemDao.findAllByContactId(contactId.value).map { entities ->
            entities.map { entity ->
                with(mapper) { entity.toDomain() }.getOrNull()!!
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
            writeLoanItemDao.deleteById(id.value)
        }
    }

    suspend fun getUnsettledSum(contactId: LoanId): Double = withContext(dispatchersProvider.io) {
        loanItemDao.calculateUnsettledSum(contactId.value) ?: 0.0
    }
}
