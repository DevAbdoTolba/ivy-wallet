package com.ivy.loans.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.data.db.dao.write.WriteLoanRecordDao
import com.ivy.data.db.dao.read.LoanRecordDao
import com.ivy.data.db.DatabaseTransaction
import com.ivy.data.model.LoanItemId
import com.ivy.data.repository.LoanRepository
import com.ivy.data.repository.TransactionRepository
import com.ivy.legacy.utils.ioThread
import com.ivy.wallet.domain.deprecated.logic.loantrasactions.LoanTransactionsLogic
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/** Counts shown in the un-settle confirmation dialog so its copy is honest
 *  (FR-016): "Delete 2 payments and 2 transactions?" vs the legacy
 *  "…no transaction to remove". */
data class UnsettlePreflight(
    val recordCount: Int,
    val transactionCount: Int,
) {
    val legacyOnly: Boolean get() = recordCount > 0 && transactionCount == 0
}

data class UnsettleReceipt(
    val recordsDeleted: Int,
    val transactionsDeleted: Int,
)

/**
 * Un-settles a paid entry after ONE explicit confirmation: deletes the entry's
 * payment records AND their transactions together, restoring wallet balances
 * (FR-016). Legacy synthetic records (migration 136→137 backfill — no
 * transaction behind them) just disappear; the stored isSettled flag is
 * cleared in both cases so flag and derived state can never disagree.
 */
@Singleton
class UnsettleLoanEntryUseCase @Inject constructor(
    private val loanRepository: LoanRepository,
    private val writeLoanRecordDao: WriteLoanRecordDao,
    private val transactionRepository: TransactionRepository,
    private val loanTransactionsLogic: LoanTransactionsLogic,
    private val loanRecordDao: LoanRecordDao,
    private val databaseTransaction: DatabaseTransaction,
) {

    suspend fun preflight(entryId: LoanItemId): UnsettlePreflight {
        val records = loanRepository.recordsForEntry(entryId)
        val transactions = mutableSetOf<com.ivy.data.model.TransactionId>()
        val affectedRecords = records.map { it.id }.toMutableSet()
        for (record in records) {
            transactionRepository.findLoanRecordTransaction(record.id)?.let { transaction ->
                transactions.add(transaction.id)
                affectedRecords.addAll(loanRecordDao.findAllByTransactionId(transaction.id.value).map { it.id })
            }
        }
        return UnsettlePreflight(
            recordCount = affectedRecords.size,
            transactionCount = transactions.size,
        )
    }

    suspend operator fun invoke(entryId: LoanItemId): Either<String, UnsettleReceipt> {
        return try {
            databaseTransaction { unsettle(entryId) }.right()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            "STORAGE_ERROR:${error.message}".left()
        }
    }

    private suspend fun unsettle(entryId: LoanItemId): UnsettleReceipt {
        val counts = preflight(entryId)
        val records = loanRepository.recordsForEntry(entryId)
        var transactionsDeleted = 0
        for (record in records) {
            // Transaction first, then the record — the reverse order could
            // leave a transaction pointing at a deleted record if we die
            // between the two.
            if (transactionRepository.findLoanRecordTransaction(record.id) != null) {
                loanTransactionsLogic.LoanRecord.deleteAssociatedLoanRecordTransaction(
                    loanRecordId = record.id,
                )
                transactionsDeleted++
            }
            ioThread { writeLoanRecordDao.deleteById(record.id) }
        }
        // Clear the legacy flag too — after this the derived state (no
        // records ⇒ paid 0 ⇒ unsettled) and the stored flag agree.
        loanRepository.updateSettledStatus(entryId, false)
            .onLeft { error(it) }

        return UnsettleReceipt(
            recordsDeleted = counts.recordCount,
            transactionsDeleted = transactionsDeleted,
        )
    }
}
