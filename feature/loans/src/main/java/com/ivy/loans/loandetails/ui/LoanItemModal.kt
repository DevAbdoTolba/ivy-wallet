package com.ivy.loans.loandetails.ui

import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ivy.data.model.LoanItem
import com.ivy.legacy.legacy.ui.theme.modal.ModalNameInput
import com.ivy.legacy.utils.onScreenStart
import com.ivy.legacy.utils.selectEndTextFieldValue
import com.ivy.ui.R
import com.ivy.wallet.ui.theme.modal.IvyModal
import com.ivy.wallet.ui.theme.modal.ModalAddSave
import com.ivy.wallet.ui.theme.modal.ModalAmountSection
import com.ivy.wallet.ui.theme.modal.ModalTitle
import com.ivy.wallet.ui.theme.modal.edit.AmountModal
import java.util.UUID

@Composable
fun BoxWithConstraintsScope.LoanItemModal(
    visible: Boolean,
    loanItem: LoanItem?,
    baseCurrency: String,
    onSave: (title: String, amount: Double) -> Unit,
    onDismiss: () -> Unit
) {
    var titleTextFieldValue by remember(visible, loanItem) {
        mutableStateOf(selectEndTextFieldValue(loanItem?.title))
    }
    var amount by remember(visible, loanItem) {
        mutableStateOf(loanItem?.amount ?: 0.0)
    }
    var amountModalVisible by remember(visible) { mutableStateOf(false) }
    val modalId = remember(visible, loanItem) { UUID.randomUUID() }

    IvyModal(
        id = modalId,
        visible = visible,
        dismiss = onDismiss,
        PrimaryAction = {
            ModalAddSave(
                item = loanItem,
                enabled = titleTextFieldValue.text.isNotBlank() && amount > 0
            ) {
                onSave(titleTextFieldValue.text.trim(), amount)
            }
        }
    ) {
        onScreenStart {
            // Amount-first entry for new items — same flow as LoanRecordModal
            if (loanItem == null) {
                amountModalVisible = true
            }
        }

        Spacer(Modifier.height(32.dp))

        ModalTitle(
            text = if (loanItem != null) {
                stringResource(R.string.edit_item)
            } else {
                stringResource(R.string.add_item)
            }
        )

        Spacer(Modifier.height(24.dp))

        ModalNameInput(
            hint = stringResource(R.string.item_title),
            autoFocusKeyboard = false,
            textFieldValue = titleTextFieldValue,
            setTextFieldValue = {
                titleTextFieldValue = it
            }
        )

        Spacer(Modifier.height(24.dp))

        ModalAmountSection(
            label = stringResource(R.string.enter_item_amount_uppercase),
            currency = baseCurrency,
            amount = amount,
            amountPaddingTop = 40.dp,
            amountPaddingBottom = 40.dp,
        ) {
            amountModalVisible = true
        }
    }

    val amountModalId = remember(visible, loanItem, amount) {
        UUID.randomUUID()
    }
    AmountModal(
        id = amountModalId,
        visible = amountModalVisible,
        currency = baseCurrency,
        initialAmount = amount,
        dismiss = { amountModalVisible = false }
    ) { newAmount ->
        amount = newAmount
    }
}
