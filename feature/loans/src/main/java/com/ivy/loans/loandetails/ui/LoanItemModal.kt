package com.ivy.loans.loandetails.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ivy.data.model.LoanItem
import com.ivy.design.l0_system.UI

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoanItemModal(
    visible: Boolean,
    loanItem: LoanItem?,
    onSave: (title: String, amount: Double) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var title by remember(loanItem) { mutableStateOf(loanItem?.title ?: "") }
    var amount by remember(loanItem) { mutableStateOf(loanItem?.amount?.toString() ?: "") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = UI.colors.pure,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding()
        ) {
            Text(
                text = if (loanItem == null) "Add Loan Item" else "Edit Loan Item",
                style = MaterialTheme.typography.headlineSmall,
                color = UI.colors.pureInverse
            )

            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = amount,
                onValueChange = { amount = it },
                label = { Text("Amount") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    val amountDouble = amount.toDoubleOrNull() ?: 0.0
                    if (title.isNotBlank() && amountDouble > 0) {
                        onSave(title, amountDouble)
                    }
                },
                modifier = Modifier.align(Alignment.End),
                enabled = title.isNotBlank() && (amount.toDoubleOrNull() ?: 0.0) > 0
            ) {
                Text("Save")
            }
        }
    }
}
