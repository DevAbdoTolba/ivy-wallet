package com.ivy.loans.domain

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.base.model.LoanRecordType
import com.ivy.data.db.DatabaseTransaction
import com.ivy.data.model.LoanItem
import com.ivy.data.repository.LoanRepository
import com.ivy.data.repository.TransactionRepository
import kotlinx.coroutines.CancellationException
import com.ivy.legacy.datamodel.Account
import com.ivy.legacy.datamodel.Loan
import com.ivy.wallet.domain.deprecated.logic.LoanRecordCreator
import com.ivy.wallet.domain.deprecated.logic.loantrasactions.LoanTransactionsLogic
import com.ivy.wallet.domain.deprecated.logic.model.CreateLoanRecordData
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Currency-minor-unit tolerance for "paid covers the entry" comparisons. */
const val ENTRY_AMOUNT_EPSILON = 0.005

data class EntryPaymentRequest(
    val entry: LoanItem,
    val loan: Loan,
    val amount: Double,
    val account: Account,
    val dateTime: Instant,
)

data class PaymentReceipt(
    val loanRecordId: UUID,
    /** True when this payment brought the entry's paid total up to (or past)
     *  its amount — i.e. the entry is now settled (derived). */
    val entrySettled: Boolean,
)

/**
 * The ONE write path for paying a checklist entry (design D1:C + D5:B).
 * Creates a DECREASE loan record targeting the entry AND its real,
 * balance-affecting transaction through the existing LoanTransactionsCore
 * machinery (direction derives from the loan type: collecting a LEND books
 * income, repaying a BORROW books an expense).
 *
 * @Singleton + mutex: the remaining-amount cap is a read-then-write, so two
 * racing confirmations (double-tap, two surfaces) must serialize here — the
 * same pattern CreateTransactionFromSmsUseCase uses for its dedup guard.
 */
@Singleton
class PayLoanEntryUseCase @Inject constructor(
    private val loanRepository: LoanRepository,
    private val loanRecordCreator: LoanRecordCreator,
    private val loanTransactionsLogic: LoanTransactionsLogic,
    private val databaseTransaction: DatabaseTransaction,
    private val transactionRepository: TransactionRepository,
) {
    private val mutex = Mutex()

    suspend operator fun invoke(request: EntryPaymentRequest): Either<String, PaymentReceipt> =
        mutex.withLock {
            try {
                databaseTransaction { pay(request) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                "STORAGE_ERROR:${error.message}".left()
            }
        }

    private suspend fun pay(request: EntryPaymentRequest): Either<String, PaymentReceipt> {
            if (!request.amount.isFinite() || request.amount <= 0.0) {
                return "VALIDATION:enter an amount above zero".left()
            }
            val entry = loanRepository.findLoanItem(request.entry.id)
                ?: return "VALIDATION:entry no longer exists".left()
            if (entry.contactId.value != request.loan.id) {
                return "VALIDATION:entry belongs to another loan".left()
            }
            // Cap check inside the mutex so concurrent payments can't overshoot
            // the entry (FR-015). Overshoot is expressed as a separate
            // loan-level record, never a silent bigger entry payment.
            val paid = loanRepository.sumPaidForEntry(request.entry.id)
            val remaining = entry.amount - paid

            val data = CreateLoanRecordData(
                note = request.entry.title,
                amount = request.amount,
                dateTime = request.dateTime,
                interest = false,
                account = request.account,
                createLoanRecordTransaction = true,
                loanRecordType = LoanRecordType.DECREASE,
                loanItemId = request.entry.id.value,
            )
            val modified = data.copy(
                convertedAmount = loanTransactionsLogic.LoanRecord.calculateConvertedAmount(
                    data = data,
                    loanAccountId = request.loan.accountId,
                ),
            )
            val loanAmount = modified.convertedAmount ?: request.amount
            if (!loanAmount.isFinite() || loanAmount <= 0.0) {
                return "VALIDATION:invalid exchange rate".left()
            }
            if (remaining <= 0.0 || loanAmount > remaining + ENTRY_AMOUNT_EPSILON) {
                return "VALIDATION:only ${"%.2f".format(remaining)} left on this entry".left()
            }

            val recordId = loanRecordCreator.create(
                loanId = request.loan.id,
                data = modified,
            ) { /* screen refreshes reactively via the records reload */ }
                ?: error("couldn't save the payment")

            // The real, balance-affecting transaction (FR-013) — same
            // machinery the manual "Add record" flow uses.
            loanTransactionsLogic.LoanRecord.createAssociatedLoanRecordTransaction(
                data = modified,
                loan = request.loan,
                loanRecordId = recordId,
            )
            check(transactionRepository.findLoanRecordTransaction(recordId) != null) {
                "couldn't save the wallet transaction"
            }

            return PaymentReceipt(
                loanRecordId = recordId,
                entrySettled = paid + loanAmount >= entry.amount - ENTRY_AMOUNT_EPSILON,
            ).right()
        }
}
