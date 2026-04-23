package com.ivy.loans.loandetails

import com.ivy.data.model.LoanItem
import com.ivy.legacy.datamodel.Account
import com.ivy.legacy.datamodel.Loan
import com.ivy.loans.loan.data.DisplayLoanItem
import com.ivy.loans.loan.data.DisplayLoanRecord
import com.ivy.wallet.ui.theme.modal.LoanModalData
import com.ivy.wallet.ui.theme.modal.LoanRecordModalData
import kotlinx.collections.immutable.ImmutableList
import java.time.Instant

data class LoanDetailsScreenState(
    val baseCurrency: String,
    val loan: Loan?,
    val displayLoanRecords: ImmutableList<DisplayLoanRecord>,
    val displayLoanItems: ImmutableList<DisplayLoanItem>,
    val loanTotalAmount: Double,
    val amountPaid: Double,
    val loanAmountPaid: Double,
    val accounts: ImmutableList<Account>,
    val selectedLoanAccount: Account?,
    val createLoanTransaction: Boolean,
    val loanModalData: LoanModalData?,
    val loanRecordModalData: LoanRecordModalData?,
    val loanItemModalVisible: Boolean = false,
    val selectedLoanItem: LoanItem? = null,
    val waitModalVisible: Boolean,
    val isDeleteModalVisible: Boolean,
    val dateTime: Instant
)
