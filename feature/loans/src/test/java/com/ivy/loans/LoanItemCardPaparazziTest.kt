package com.ivy.loans

import androidx.compose.runtime.Composable
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import com.ivy.base.legacy.Theme
import com.ivy.data.model.LoanId
import com.ivy.data.model.LoanItem
import com.ivy.design.l0_system.IvyTheme
import com.ivy.design.utils.defaultDesign
import com.ivy.loans.loan.data.DisplayLoanItem
import com.ivy.loans.loan.data.EntryPaymentRow
import com.ivy.loans.loandetails.ui.LoanItemCard
import com.ivy.ui.testing.PaparazziScreenshotTest
import com.ivy.ui.testing.PaparazziTheme
import com.ivy.wallet.ui.theme.Ivy
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

@RunWith(TestParameterInjector::class)
class LoanItemCardPaparazziTest(
    @TestParameter
    private val theme: PaparazziTheme,
) : PaparazziScreenshotTest() {

    // LoanItemCard renders with the legacy design system (UI.colors/typo/shapes),
    // so the M3-only snapshot harness must additionally provide IvyTheme.
    @Composable
    private fun OldDesignTheme(content: @Composable () -> Unit) {
        IvyTheme(
            theme = if (theme == PaparazziTheme.Dark) Theme.DARK else Theme.LIGHT,
            design = defaultDesign(),
            isDarkTheme = theme == PaparazziTheme.Dark,
            content = content,
        )
    }

    private fun entry(amount: Double, title: String) = LoanItem(
        contactId = LoanId(UUID.randomUUID()),
        amount = amount,
        title = title,
        isSettled = false,
    )

    private fun payment(amount: Double, withTransaction: Boolean) = EntryPaymentRow(
        recordId = UUID.randomUUID(),
        amount = amount,
        accountName = "Bank",
        dateTime = Instant.EPOCH,
        dateLabel = "Jun 12",
        transactionId = if (withTransaction) UUID.randomUUID() else null,
    )

    @Composable
    private fun Card(display: DisplayLoanItem) {
        LoanItemCard(
            display = display,
            baseCurrency = "USD",
            loanColor = Ivy,
            onCircleTap = {},
            onEdit = {},
            onDelete = {},
            onTransactionClick = {},
        )
    }

    @Test
    fun `snapshot loanItemCard - unsettled`() {
        snapshot(theme) {
            OldDesignTheme {
                Card(DisplayLoanItem(loanItem = entry(12.34, "Coffee")))
            }
        }
    }

    @Test
    fun `snapshot loanItemCard - partially paid`() {
        snapshot(theme) {
            OldDesignTheme {
                Card(
                    DisplayLoanItem(
                        loanItem = entry(1200.0, "TV installment"),
                        paidAmount = 500.0,
                        payments = listOf(payment(500.0, withTransaction = true)),
                    )
                )
            }
        }
    }

    @Test
    fun `snapshot loanItemCard - settled`() {
        snapshot(theme) {
            OldDesignTheme {
                Card(
                    DisplayLoanItem(
                        loanItem = entry(12.34, "Lunch"),
                        paidAmount = 12.34,
                        payments = listOf(payment(12.34, withTransaction = true)),
                    )
                )
            }
        }
    }

    @Test
    fun `snapshot loanItemCard - legacy settled without transaction`() {
        snapshot(theme) {
            OldDesignTheme {
                Card(
                    DisplayLoanItem(
                        loanItem = entry(800.0, "School fees"),
                        paidAmount = 800.0,
                        payments = listOf(payment(800.0, withTransaction = false)),
                    )
                )
            }
        }
    }
}
