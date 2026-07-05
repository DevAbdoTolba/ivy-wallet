package com.ivy.loans.domain

import arrow.core.Either
import com.ivy.base.model.LoanRecordType
import com.ivy.data.model.LoanId
import com.ivy.data.model.LoanItem
import com.ivy.data.repository.LoanRepository
import com.ivy.data.model.LoanType
import com.ivy.legacy.datamodel.Account
import com.ivy.legacy.datamodel.Loan
import com.ivy.wallet.domain.deprecated.logic.LoanRecordCreator
import com.ivy.wallet.domain.deprecated.logic.loantrasactions.LTLoanRecordMapper
import com.ivy.wallet.domain.deprecated.logic.loantrasactions.LoanTransactionsLogic
import com.ivy.wallet.domain.deprecated.logic.model.CreateLoanRecordData
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Instant
import java.util.UUID

class PayLoanEntryUseCaseTest {

    private val loanRepository = mockk<LoanRepository>()
    private val loanRecordCreator = mockk<LoanRecordCreator>()
    private val recordMapper = mockk<LTLoanRecordMapper>()
    private val loanTransactionsLogic = mockk<LoanTransactionsLogic> {
        every { LoanRecord } returns recordMapper
    }

    private val useCase = PayLoanEntryUseCase(
        loanRepository = loanRepository,
        loanRecordCreator = loanRecordCreator,
        loanTransactionsLogic = loanTransactionsLogic,
    )

    private val loan = Loan(
        name = "Ahmed",
        amount = 5_000.0,
        type = LoanType.LEND,
        accountId = UUID.randomUUID(),
    )
    private val entry = LoanItem(
        contactId = LoanId(loan.id),
        amount = 1_200.0,
        title = "TV installment",
    )
    private val wallet = mockk<Account> {
        every { id } returns UUID.randomUUID()
    }

    private fun request(amount: Double) = EntryPaymentRequest(
        entry = entry,
        loan = loan,
        amount = amount,
        account = wallet,
        dateTime = Instant.EPOCH,
    )

    @Test
    fun `rejects a zero or negative amount`() = runTest {
        useCase(request(0.0)).isLeft().shouldBeTrue()
        useCase(request(-5.0)).isLeft().shouldBeTrue()
    }

    @Test
    fun `rejects an amount above the entry's remaining (FR-015)`() = runTest {
        // 500 already paid of 1200 → remaining 700
        coEvery { loanRepository.sumPaidForEntry(entry.id) } returns 500.0

        val result = useCase(request(701.0))

        result.isLeft().shouldBeTrue()
        (result as Either.Left).value shouldContain "left on this entry"
        coVerify(exactly = 0) { loanRecordCreator.create(any(), any(), any()) }
    }

    @Test
    fun `creates a DECREASE record targeting the entry AND its real transaction`() = runTest {
        coEvery { loanRepository.sumPaidForEntry(entry.id) } returns 500.0
        coEvery { recordMapper.calculateConvertedAmount(any<CreateLoanRecordData>(), any()) } returns null
        val recordId = UUID.randomUUID()
        val dataSlot = slot<CreateLoanRecordData>()
        coEvery { loanRecordCreator.create(loan.id, capture(dataSlot), any()) } returns recordId
        coEvery {
            recordMapper.createAssociatedLoanRecordTransaction(any(), any(), any())
        } returns Unit

        val result = useCase(request(700.0))

        // Receipt: record created; 500 + 700 covers the 1200 entry → settled.
        result shouldBe Either.Right(PaymentReceipt(loanRecordId = recordId, entrySettled = true))
        with(dataSlot.captured) {
            loanItemId shouldBe entry.id.value
            loanRecordType shouldBe LoanRecordType.DECREASE
            interest shouldBe false
            // FR-013: the payment MUST book a real balance-affecting transaction.
            createLoanRecordTransaction shouldBe true
        }
        coVerify(exactly = 1) {
            recordMapper.createAssociatedLoanRecordTransaction(
                data = any(),
                loan = loan,
                loanRecordId = recordId,
            )
        }
    }

    @Test
    fun `a partial payment leaves the entry unsettled`() = runTest {
        coEvery { loanRepository.sumPaidForEntry(entry.id) } returns 0.0
        coEvery { recordMapper.calculateConvertedAmount(any<CreateLoanRecordData>(), any()) } returns null
        val recordId = UUID.randomUUID()
        coEvery { loanRecordCreator.create(loan.id, any(), any()) } returns recordId
        coEvery {
            recordMapper.createAssociatedLoanRecordTransaction(any(), any(), any())
        } returns Unit

        val result = useCase(request(500.0))

        result shouldBe Either.Right(PaymentReceipt(loanRecordId = recordId, entrySettled = false))
    }
}
