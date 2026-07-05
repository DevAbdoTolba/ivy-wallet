package com.ivy.loans.domain

import com.ivy.base.model.LoanRecordType
import com.ivy.data.model.LoanType
import com.ivy.wallet.domain.deprecated.logic.loantrasactions.inferLoanLinkDirection
import io.kotest.matchers.shouldBe
import org.junit.Test

/**
 * Exhaustive check of the 4-row direction table (spec US7 scenario 4) — the
 * single place linked-payment meaning is derived (FR-022).
 */
class LoanLinkDirectionTest {

    @Test
    fun `money out on a LEND loan means the user lent more - INCREASE`() {
        inferLoanLinkDirection(moneyOut = true, loanType = LoanType.LEND) shouldBe
            LoanRecordType.INCREASE
    }

    @Test
    fun `money out on a BORROW loan means the user repaid - DECREASE`() {
        inferLoanLinkDirection(moneyOut = true, loanType = LoanType.BORROW) shouldBe
            LoanRecordType.DECREASE
    }

    @Test
    fun `money in on a LEND loan means the counterpart repaid - DECREASE`() {
        inferLoanLinkDirection(moneyOut = false, loanType = LoanType.LEND) shouldBe
            LoanRecordType.DECREASE
    }

    @Test
    fun `money in on a BORROW loan means the user borrowed more - INCREASE`() {
        inferLoanLinkDirection(moneyOut = false, loanType = LoanType.BORROW) shouldBe
            LoanRecordType.INCREASE
    }
}
