package com.ivy.loans.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.data.db.dao.write.WriteLoanRecordDao
import com.ivy.data.model.LoanItemId
import com.ivy.data.repository.LoanRepository
import com.ivy.data.repository.TransactionRepository
import com.ivy.legacy.utils.ioThread
import com.ivy.wallet.domain.deprecated.logic.loantrasactions.LoanTransactionsLogic
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
) {

    suspend fun preflight(entryId: LoanItemId): UnsettlePreflight {
        val records = loanRepository.recordsForEntry(entryId)
        var transactions = 0
        for (record in records) {
            if (transactionRepository.findLoanRecordTransaction(record.id) != null) {
                transactions++
            }
        }
        return UnsettlePreflight(
            recordCount = records.size,
            transactionCount = transactions,
        )
    }

    suspend operator fun invoke(entryId: LoanItemId): Either<String, UnsettleReceipt> {
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
            .onLeft { return it.left() }

        return UnsettleReceipt(
            recordsDeleted = records.size,
            transactionsDeleted = transactionsDeleted,
        ).right()
    }
}
