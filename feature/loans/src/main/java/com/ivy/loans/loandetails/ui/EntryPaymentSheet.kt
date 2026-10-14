package com.ivy.loans.loandetails.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ivy.data.model.LoanType
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.legacy.datamodel.Account
import com.ivy.legacy.utils.format
import com.ivy.loans.loandetails.EntryPaymentSheetState
import com.ivy.wallet.ui.theme.Ivy
import com.ivy.wallet.ui.theme.Red
import com.ivy.wallet.ui.theme.modal.IvyModal
import com.ivy.wallet.ui.theme.modal.ModalAmountSection
import com.ivy.wallet.ui.theme.modal.ModalSet
import com.ivy.wallet.ui.theme.modal.ModalTitle
import com.ivy.wallet.ui.theme.modal.AccountsRow
import com.ivy.wallet.ui.theme.modal.edit.AmountModal
import kotlinx.collections.immutable.ImmutableList
import java.util.UUID

/**
 * The tap-circle quick payment sheet (design D5:B). Opens pre-filled with the
 * entry's REMAINING amount, the loan's default wallet, and "now" — the happy
 * path is two taps (circle → confirm). Editing the amount smaller IS the
 * partial-payment flow; there is nothing separate to learn (Q1/Q5 unified).
 */
@Composable
fun BoxWithConstraintsScope.EntryPaymentSheet(
    sheet: EntryPaymentSheetState?,
    baseCurrency: String,
    defaultCurrency: String,
    accounts: ImmutableList<Account>,
    loanType: LoanType?,
    onConfirm: (amount: Double, account: Account) -> Unit,
    onDismiss: () -> Unit,
) {
    val entry = sheet?.entry
    var amount by remember(entry?.loanItem?.id) {
        mutableStateOf(entry?.remaining ?: 0.0)
    }
    var selectedAccount by remember(entry?.loanItem?.id) {
        mutableStateOf(sheet?.defaultAccount)
    }
    var amountModalVisible by remember(entry?.loanItem?.id) { mutableStateOf(false) }
    val modalId = remember(entry?.loanItem?.id) { UUID.randomUUID() }

    val remaining = entry?.remaining ?: 0.0
    val paymentCurrency = selectedAccount?.currency ?: defaultCurrency
    val sameCurrency = paymentCurrency == baseCurrency
    val settlesEntry = entry != null && sameCurrency && amount >= remaining - 0.005
    val verb = if (loanType == LoanType.LEND) "Receive" else "Pay"
    val canConfirm = sheet != null && !sheet.paying &&
        amount.isFinite() && amount > 0.0 && (!sameCurrency || amount <= remaining + 0.005) && selectedAccount != null

    IvyModal(
        id = modalId,
        visible = sheet != null,
        dismiss = onDismiss,
        PrimaryAction = {
            ModalSet(
                label = when {
                    sheet?.paying == true -> "Saving…"
                    settlesEntry -> "$verb ${amount.format(paymentCurrency)} $paymentCurrency — settles entry"
                    else -> "$verb ${amount.format(paymentCurrency)} $paymentCurrency"
                },
                enabled = canConfirm,
            ) {
                selectedAccount?.let { onConfirm(amount, it) }
            }
        },
    ) {
        Spacer(Modifier.height(32.dp))

        ModalTitle(text = "$verb payment · ${entry?.loanItem?.title.orEmpty()}")

        Spacer(Modifier.height(8.dp))

        Text(
            modifier = Modifier.padding(horizontal = 32.dp),
            text = "Left on this entry: ${remaining.format(baseCurrency)} $baseCurrency" +
                if (entry?.partiallyPaid == true) {
                    " (paid ${entry.paidAmount.format(baseCurrency)} of " +
                        "${entry.loanItem.amount.format(baseCurrency)})"
                } else {
                    ""
                },
            style = UI.typo.c.style(
                color = UI.colors.gray,
                fontWeight = FontWeight.Bold,
            ),
        )

        Spacer(Modifier.height(16.dp))

        AccountsRow(
            accounts = accounts,
            selectedAccount = selectedAccount,
            onSelectedAccountChanged = { account ->
                if (sheet?.paying != true) {
                    val nextCurrency = account.currency ?: defaultCurrency
                    if (nextCurrency != paymentCurrency) {
                        amount = if (nextCurrency == baseCurrency) remaining else 0.0
                    }
                    selectedAccount = account
                }
            },
        )
        if (!sameCurrency) {
            Text(
                modifier = Modifier.padding(horizontal = 32.dp),
                text = "Enter the amount in $paymentCurrency. The converted payment must fit the remaining $baseCurrency balance.",
                style = UI.typo.c.style(color = UI.colors.gray),
            )
        }

        Spacer(Modifier.height(8.dp))

        ModalAmountSection(
            label = "PAYMENT AMOUNT (EDIT FOR PARTIAL)",
            currency = paymentCurrency,
            amount = amount,
            amountPaddingTop = 32.dp,
            amountPaddingBottom = 24.dp,
        ) {
            amountModalVisible = true
        }

        sheet?.error?.let { err ->
            Text(
                modifier = Modifier.padding(horizontal = 32.dp),
                text = err,
                style = UI.typo.c.style(
                    color = Red,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.height(8.dp))
        }

        if (sameCurrency && amount > remaining + 0.005) {
            Text(
                modifier = Modifier.padding(horizontal = 32.dp),
                text = "Only ${remaining.format(baseCurrency)} left on this entry — " +
                    "add the extra as a loan record instead",
                style = UI.typo.c.style(
                    color = Red,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.height(8.dp))
        }
    }

    val amountModalId = remember(entry?.loanItem?.id, amount) { UUID.randomUUID() }
    AmountModal(
        id = amountModalId,
        visible = amountModalVisible,
        currency = paymentCurrency,
        initialAmount = amount,
        dismiss = { amountModalVisible = false },
    ) { newAmount ->
        amount = newAmount
    }
}
