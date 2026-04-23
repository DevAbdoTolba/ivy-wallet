package com.ivy.loans.loandetails.ui

import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ivy.data.model.LoanItem
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.design.utils.hideKeyboard
import com.ivy.legacy.utils.formatInputAmount
import com.ivy.legacy.utils.localDecimalSeparator
import com.ivy.ui.R
import com.ivy.wallet.ui.theme.Red

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoanItemModal(
    visible: Boolean,
    loanItem: LoanItem?,
    baseCurrency: String,
    onSave: (title: String, amount: Double) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    var title by remember(loanItem) { mutableStateOf(loanItem?.title ?: "") }
    var amount by remember(loanItem) { mutableStateOf(loanItem?.amount?.toString() ?: "") }
    
    val view = LocalView.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = UI.colors.pure,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding()
                .imePadding()
        ) {
            Text(
                text = if (loanItem == null) "Add Loan Item" else "Edit Loan Item",
                style = UI.typo.nH2.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.Bold
                )
            )

            Spacer(Modifier.height(24.dp))

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = UI.colors.pureInverse,
                    unfocusedTextColor = UI.colors.pureInverse,
                    focusedBorderColor = UI.colors.pureInverse,
                    unfocusedBorderColor = UI.colors.medium
                )
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = amount.ifEmpty { "0" },
                style = UI.typo.nH1.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp)
            )

            NumericKeypad(
                onNumberPressed = { num ->
                    val formatted = formatInputAmount(baseCurrency, amount, num, 2)
                    if (formatted != null) {
                        amount = formatted
                    }
                },
                onDecimalPoint = {
                    val separator = localDecimalSeparator()
                    if (!amount.contains(separator)) {
                        amount += separator
                    }
                },
                onBackspace = {
                    if (amount.isNotEmpty()) {
                        amount = amount.dropLast(1)
                    }
                }
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = {
                    val amountDouble = amount.replace(",", ".").replace(" ", "").replace("\u00A0", "").toDoubleOrNull() ?: 0.0
                    if (title.isNotBlank() && amountDouble > 0) {
                        view.hideKeyboard()
                        onSave(title, amountDouble)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                enabled = title.isNotBlank() && (amount.replace(",", ".").replace(" ", "").replace("\u00A0", "").toDoubleOrNull() ?: 0.0) > 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = UI.colors.pureInverse,
                    contentColor = UI.colors.pure
                ),
                shape = UI.shapes.r4
            ) {
                Text(
                    text = "SAVE",
                    style = UI.typo.b1.style(fontWeight = FontWeight.Black)
                )
            }
            
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun NumericKeypad(
    onNumberPressed: (String) -> Unit,
    onDecimalPoint: () -> Unit,
    onBackspace: () -> Unit
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9")
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                row.forEach { num ->
                    KeypadButton(text = num, onClick = { onNumberPressed(num) })
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            KeypadButton(text = localDecimalSeparator(), onClick = onDecimalPoint)
            KeypadButton(text = "0", onClick = { onNumberPressed("0") })
            IconButton(
                onClick = onBackspace,
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(UI.colors.medium.copy(alpha = 0.1f))
            ) {
                Icon(
                    painter = androidx.compose.ui.res.painterResource(com.ivy.ui.R.drawable.ic_backspace),
                    contentDescription = "Backspace",
                    tint = Red,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun KeypadButton(
    text: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .background(UI.colors.medium.copy(alpha = 0.1f)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = UI.typo.nH2.style(
                color = UI.colors.pureInverse,
                fontWeight = FontWeight.Bold
            )
        )
    }
}
