package com.ivy.loans.loandetails.events

import com.ivy.data.model.LoanItem
import com.ivy.data.model.LoanItemId
import com.ivy.wallet.domain.deprecated.logic.model.CreateAccountData

sealed interface LoanDetailsScreenEvent {
    data object OnEditLoanClick : LoanDetailsScreenEvent
    data object OnAmountClick : LoanDetailsScreenEvent
    data object OnAddRecord : LoanDetailsScreenEvent
    data class OnCreateAccount(val data: CreateAccountData) : LoanDetailsScreenEvent

    data class OnToggleLoanItemSettled(val id: LoanItemId, val isSettled: Boolean) : LoanDetailsScreenEvent
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
