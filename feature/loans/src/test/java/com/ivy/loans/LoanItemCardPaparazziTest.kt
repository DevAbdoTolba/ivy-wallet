package com.ivy.loans

import androidx.compose.runtime.Composable
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import com.ivy.base.legacy.Theme
import com.ivy.data.model.LoanId
import com.ivy.data.model.LoanItem
import com.ivy.design.l0_system.IvyTheme
import com.ivy.design.utils.defaultDesign
import com.ivy.loans.loandetails.ui.LoanItemCard
import com.ivy.ui.testing.PaparazziScreenshotTest
import com.ivy.ui.testing.PaparazziTheme
import com.ivy.wallet.ui.theme.Ivy
import org.junit.Test
import org.junit.runner.RunWith
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

    @Test
    fun `snapshot loanItemCard - unsettled`() {
        snapshot(theme) {
            OldDesignTheme {
                LoanItemCard(
                    loanItem = LoanItem(
                        contactId = LoanId(UUID.randomUUID()),
                        amount = 12.34,
                        title = "Coffee",
                        isSettled = false
                    ),
                    baseCurrency = "USD",
                    loanColor = Ivy,
                    onToggleSettled = {},
                    onEdit = {},
                    onDelete = {}
                )
            }
        }
    }

    @Test
    fun `snapshot loanItemCard - settled`() {
        snapshot(theme) {
            OldDesignTheme {
                LoanItemCard(
                    loanItem = LoanItem(
                        contactId = LoanId(UUID.randomUUID()),
                        amount = 12.34,
                        title = "Lunch",
                        isSettled = true
                    ),
                    baseCurrency = "USD",
                    loanColor = Ivy,
                    onToggleSettled = {},
                    onEdit = {},
                    onDelete = {}
                )
            }
        }
    }
}
