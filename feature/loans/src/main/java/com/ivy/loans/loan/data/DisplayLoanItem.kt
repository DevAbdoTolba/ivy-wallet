package com.ivy.loans.loan.data

import com.ivy.data.model.LoanItem

data class DisplayLoanItem(
    val loanItem: LoanItem,
    // Add any other fields needed for UI if necessary, 
    // for now following DisplayLoanRecord pattern but with LoanItem
)
