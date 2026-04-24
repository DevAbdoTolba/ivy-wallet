package com.ivy.loans.loan

import com.ivy.loans.loan.data.DisplayLoan
import com.ivy.wallet.domain.deprecated.logic.model.CreateAccountData
import com.ivy.wallet.domain.deprecated.logic.model.CreateLoanData
import java.util.UUID

sealed interface LoanScreenEvent {
    data class OnLoanCreate(val createLoanData: CreateLoanData) : LoanScreenEvent
    data class OnReordered(val reorderedList: List<DisplayLoan>) : LoanScreenEvent
    data class OnCreateAccount(val accountData: CreateAccountData) : LoanScreenEvent
    data class OnReOrderModalShow(val show: Boolean) : LoanScreenEvent
    data class OnTabChanged(val tab: LoanTab) : LoanScreenEvent
    data object OnAddLoan : LoanScreenEvent
    data object OnLoanModalDismiss : LoanScreenEvent
    data object OnChangeDate : LoanScreenEvent
    data object OnChangeTime : LoanScreenEvent

    /** User dismissed the post-create itemize sheet (without saving). */
    data object OnDismissItemizeSheet : LoanScreenEvent

    /** User confirmed the itemized items they built for the freshly created loan. */
    data class OnSaveItemizedLoan(
        val loanId: UUID,
        val items: List<ItemizeEntry>,
    ) : LoanScreenEvent

    /** Toggles paid off loans visibility */
    data object OnTogglePaidOffLoanVisibility : LoanScreenEvent
}

data class ItemizeEntry(val title: String, val amount: Double)
