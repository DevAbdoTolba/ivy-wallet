package com.ivy.loans.loandetails.events

import com.ivy.data.model.LoanItem
import com.ivy.data.model.LoanItemId
import com.ivy.legacy.datamodel.Account
import com.ivy.loans.loan.data.DisplayLoanItem
import com.ivy.wallet.domain.deprecated.logic.model.CreateAccountData

sealed interface LoanDetailsScreenEvent {
    data object OnEditLoanClick : LoanDetailsScreenEvent
    data object OnAmountClick : LoanDetailsScreenEvent
    data object OnAddRecord : LoanDetailsScreenEvent
    data class OnCreateAccount(val data: CreateAccountData) : LoanDetailsScreenEvent

    /** Circle tap (D5:B): unsettled/partial entry → payment sheet;
     *  settled entry → un-settle confirmation (with preflight counts). */
    data class OnEntryCircleTap(val item: DisplayLoanItem) : LoanDetailsScreenEvent

    /** Confirm on the payment sheet — creates the record + real transaction. */
    data class OnConfirmEntryPayment(
        val amount: Double,
        val account: Account,
    ) : LoanDetailsScreenEvent

    data object OnDismissPaymentSheet : LoanDetailsScreenEvent

    /** Confirm on the un-settle dialog — cascade-deletes payments + txns. */
    data object OnConfirmUnsettle : LoanDetailsScreenEvent
    data object OnDismissUnsettle : LoanDetailsScreenEvent
    data object OnAddLoanItem : LoanDetailsScreenEvent
    data class OnSaveLoanItem(
        val title: String,
        val amount: Double,
        val editingItemId: LoanItemId? = null,
    ) : LoanDetailsScreenEvent
    data class OnDeleteLoanItem(val id: LoanItemId) : LoanDetailsScreenEvent
    data object OnConfirmDeleteLoanItem : LoanDetailsScreenEvent
    data object OnDismissDeleteLoanItem : LoanDetailsScreenEvent
    data class OnEditLoanItem(val loanItem: LoanItem) : LoanDetailsScreenEvent
    data object OnDismissLoanItemModal : LoanDetailsScreenEvent
}
