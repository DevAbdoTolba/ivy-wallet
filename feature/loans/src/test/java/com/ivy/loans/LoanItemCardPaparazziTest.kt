package com.ivy.loans

import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import com.ivy.data.model.LoanId
import com.ivy.data.model.LoanItem
import com.ivy.loans.loandetails.ui.LoanItemCard
import com.ivy.ui.testing.PaparazziScreenshotTest
import com.ivy.ui.testing.PaparazziTheme
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(TestParameterInjector::class)
class LoanItemCardPaparazziTest(
    @TestParameter
    private val theme: PaparazziTheme,
) : PaparazziScreenshotTest() {
    @Test
    fun `snapshot loanItemCard - unsettled`() {
        snapshot(theme) {
            LoanItemCard(
                loanItem = LoanItem(
                    contactId = LoanId(UUID.randomUUID()),
                    amount = 12.34,
                    title = "Coffee",
                    isSettled = false
                ),
                baseCurrency = "USD",
                onToggleSettled = {}
            )
        }
    }

    @Test
    fun `snapshot loanItemCard - settled`() {
        snapshot(theme) {
            LoanItemCard(
                loanItem = LoanItem(
                    contactId = LoanId(UUID.randomUUID()),
                    amount = 12.34,
                    title = "Lunch",
                    isSettled = true
                ),
                baseCurrency = "USD",
                onToggleSettled = {}
            )
        }
    }
}
