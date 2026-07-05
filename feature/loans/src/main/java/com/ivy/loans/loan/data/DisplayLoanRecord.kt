package com.ivy.loans.loan.data

import com.ivy.legacy.datamodel.Account
import com.ivy.legacy.datamodel.LoanRecord

data class DisplayLoanRecord(
    val loanRecord: LoanRecord,
    val account: Account? = null,
    val loanRecordCurrencyCode: String = "",
    val loanCurrencyCode: String = "",
    val loanRecordTransaction: Boolean = false,
    /** Id of the record's associated transaction (jump-link target); null for
     *  records without one (e.g. legacy synthetic backfill records). */
    val loanRecordTransactionId: java.util.UUID? = null,
)
