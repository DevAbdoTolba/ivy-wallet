package com.ivy.wallet.domain.deprecated.logic.loantrasactions

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.base.model.LoanRecordType
import com.ivy.data.db.dao.write.WriteLoanRecordDao
import com.ivy.data.db.entity.LoanRecordEntity
import com.ivy.data.model.Expense
import com.ivy.data.model.Income
import com.ivy.data.model.LoanId
import com.ivy.data.model.LoanItemId
import com.ivy.data.model.LoanType
import com.ivy.data.model.Transaction
import com.ivy.data.model.Transfer
import com.ivy.data.repository.LoanRepository
import com.ivy.data.repository.TransactionRepository
import com.ivy.legacy.datamodel.Loan
import com.ivy.legacy.utils.ioThread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Currency-minor-unit tolerance for allocation math. */
private const val LINK_EPSILON = 0.005

/** How a linked repayment is poured into the loan's checklist entries. */
sealed interface LinkAllocation {
    /** Fill open entries oldest-first; any remainder becomes loan-level. */
    data object AutoOldestFirst : LinkAllocation

    /** Target one entry (capped at its remaining); remainder loan-level. */
    data class SingleEntry(val entryId: LoanItemId) : LinkAllocation

    /** Skip entries entirely — one loan-level record. */
    data object LoanLevelOnly : LinkAllocation
}

data class LinkReceipt(
    val recordIds: List<UUID>,
    val direction: LoanRecordType,
    /** Entry ids whose paid total reached their amount through this link. */
    val entriesSettled: List<LoanItemId>,
)

/**
 * The 4-row direction table (spec US7 scenario 4), kept as ONE pure function
 * so the money logic has exactly one place to be wrong — and one exhaustive
 * test. moneyOut = the transaction removes money from the user's wallet.
 *
 *  - money out on a LEND loan   → the user lent more     → INCREASE
 *  - money out on a BORROW loan → the user repaid        → DECREASE
 *  - money in  on a LEND loan   → the counterpart repaid → DECREASE
 *  - money in  on a BORROW loan → the user borrowed more → INCREASE
 */
fun inferLoanLinkDirection(moneyOut: Boolean, loanType: LoanType): LoanRecordType = when {
    moneyOut && loanType == LoanType.LEND -> LoanRecordType.INCREASE
    moneyOut && loanType == LoanType.BORROW -> LoanRecordType.DECREASE
    !moneyOut && loanType == LoanType.LEND -> LoanRecordType.DECREASE
    else -> LoanRecordType.INCREASE
}

/** True when the transaction takes money OUT of the user's wallet. */
fun Transaction.isMoneyOut(): Boolean = when (this) {
    is Expense, is Transfer -> true
    is Income -> false
}

/** The transaction's principal amount from the linking perspective. */
fun Transaction.linkAmount(): Double = when (this) {
    is Expense -> value.amount.value
    is Income -> value.amount.value
    is Transfer -> fromValue.amount.value
}

/**
 * Links an EXISTING transaction (SMS-created or manual) to a loan.
 *
 * THE IRON RULE (FR-021): one real-world payment = one transaction. The money
 * already moved when the transaction was created — linking creates loan
 * record(s) that ADOPT it (the transaction gains loanId/loanRecordId; records
 * are created with NO associated transaction of their own) and never touches
 * any wallet balance.
 *
 * DECREASE outcomes may split across entries per [LinkAllocation]; the parts
 * always sum exactly to the transaction amount, each part ≤ its entry's
 * remaining, remainder loan-level. INCREASE outcomes are always one
 * loan-level record (there is nothing to "pay off").
 *
 * Lives in the legacy loan-transactions package so BOTH hosting surfaces
 * (transaction detail, future review-card shortcut) reach it without new
 * feature→feature dependencies.
 *
 * convertedAmount is left null on linked records: the record's currency is
 * the transaction's wallet currency; cross-currency loans can adjust the
 * record afterwards like any manually created one.
 */
@Singleton
class LinkTransactionToLoanUseCase @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val loanRepository: LoanRepository,
    private val writeLoanRecordDao: WriteLoanRecordDao,
) {
    private val mutex = Mutex()

    @Suppress("ReturnCount", "LongMethod", "CyclomaticComplexMethod")
    suspend operator fun invoke(
        transaction: Transaction,
        loan: Loan,
        allocation: LinkAllocation,
    ): Either<String, LinkReceipt> = mutex.withLock {
        if (transaction.metadata.loanRecordId != null || transaction.metadata.loanId != null) {
            return "ALREADY_LINKED:this transaction already belongs to a loan".left()
        }

        val moneyOut = transaction.isMoneyOut()
        val amount = transaction.linkAmount()
        if (amount <= 0.0) return "VALIDATION:transaction amount must be positive".left()

        val direction = inferLoanLinkDirection(moneyOut, loan.type)
        val accountId = when (transaction) {
            is Expense -> transaction.account.value
            is Income -> transaction.account.value
            is Transfer -> transaction.fromAccount.value
        }
        val note = transaction.title?.value ?: "Linked payment"

        // Build the record splits. Entry allocation only applies to DECREASE
        // (a repayment can settle entries; lending more cannot).
        data class Split(val entryId: LoanItemId?, val amount: Double)

        val splits: List<Split> = if (direction != LoanRecordType.DECREASE) {
            listOf(Split(entryId = null, amount = amount))
        } else {
            when (allocation) {
                LinkAllocation.LoanLevelOnly -> listOf(Split(null, amount))
                is LinkAllocation.SingleEntry -> {
                    val paid = loanRepository.sumPaidForEntry(allocation.entryId)
                    val entries = loanRepository.getLoanItems(LoanId(loan.id)).first()
                    val entry = entries.firstOrNull { it.id == allocation.entryId }
                        ?: return "VALIDATION:entry not found on this loan".left()
                    val remaining = (entry.amount - paid).coerceAtLeast(0.0)
                    val toEntry = minOf(amount, remaining)
                    buildList {
                        if (toEntry > 0.0) add(Split(entry.id, toEntry))
                        val rest = amount - toEntry
                        if (rest > LINK_EPSILON) add(Split(null, rest))
                    }
                }
                LinkAllocation.AutoOldestFirst -> {
                    val entries = loanRepository.getLoanItems(LoanId(loan.id)).first()
                        .sortedBy { it.createdAt }
                    var left = amount
                    buildList {
                        for (entry in entries) {
                            if (left <= LINK_EPSILON) break
                            val paid = loanRepository.sumPaidForEntry(entry.id)
                            val remaining = (entry.amount - paid).coerceAtLeast(0.0)
                            if (remaining <= LINK_EPSILON) continue
                            val part = minOf(left, remaining)
                            add(Split(entry.id, part))
                            left -= part
                        }
                        if (left > LINK_EPSILON) add(Split(null, left))
                    }
                }
            }
        }
        if (splits.isEmpty()) {
            return "VALIDATION:nothing to allocate".left()
        }

        // Persist the records — plain rows, NO transaction creation anywhere.
        val recordIds = mutableListOf<UUID>()
        for (split in splits) {
            val entity = LoanRecordEntity(
                loanId = loan.id,
                amount = split.amount,
                note = note,
                dateTime = transaction.time,
                interest = false,
                accountId = accountId,
                convertedAmount = null,
                loanRecordType = direction,
                loanItemId = split.entryId?.value,
            )
            ioThread { writeLoanRecordDao.save(entity) }
            recordIds.add(entity.id)
        }

        // Adopt: the transaction gains the loan link (primary = first record).
        val updatedMetadata = transaction.metadata.copy(
            loanId = loan.id,
            loanRecordId = recordIds.first(),
        )
        val updated: Transaction = when (transaction) {
            is Expense -> transaction.copy(metadata = updatedMetadata)
            is Income -> transaction.copy(metadata = updatedMetadata)
            is Transfer -> transaction.copy(metadata = updatedMetadata)
        }
        runCatching { transactionRepository.save(updated) }
            .onFailure { return "STORAGE_ERROR:${it.message}".left() }

        val entriesAfter = loanRepository.getLoanItems(LoanId(loan.id)).first()
        val entriesSettled = splits.mapNotNull { it.entryId }.distinct().filter { entryId ->
            val entry = entriesAfter.firstOrNull { it.id == entryId } ?: return@filter false
            loanRepository.sumPaidForEntry(entryId) >= entry.amount - LINK_EPSILON
        }

        LinkReceipt(
            recordIds = recordIds,
            direction = direction,
            entriesSettled = entriesSettled,
        ).right()
    }
}
