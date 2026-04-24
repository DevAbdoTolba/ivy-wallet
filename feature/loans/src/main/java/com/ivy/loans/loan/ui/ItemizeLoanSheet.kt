package com.ivy.loans.loan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.legacy.utils.format
import com.ivy.loans.loan.ItemizeEntry
import com.ivy.loans.loan.ItemizeSheetData
import com.ivy.wallet.ui.theme.findContrastTextColor

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemizeLoanSheet(
    data: ItemizeSheetData?,
    onDismiss: () -> Unit,
    onSave: (List<ItemizeEntry>) -> Unit,
) {
    if (data == null) return

    val loanColor = Color(data.loanColorArgb)
    var showBuilder by remember(data.loanId) { mutableStateOf(false) }
    val entries = remember(data.loanId) { mutableStateOf<List<ItemizeEntry>>(emptyList()) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = UI.colors.pure,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .imePadding()
        ) {
            if (!showBuilder) {
                IntroPhase(
                    loanName = data.loanName,
                    loanAmount = data.loanAmount,
                    currencyCode = data.currencyCode,
                    loanColor = loanColor,
                    onSkip = onDismiss,
                    onAddItems = { showBuilder = true },
                )
            } else {
                BuilderPhase(
                    loanTotal = data.loanAmount,
                    currencyCode = data.currencyCode,
                    loanColor = loanColor,
                    entries = entries.value,
                    onEntriesChanged = { entries.value = it },
                    onSave = { onSave(entries.value) },
                    onCancel = onDismiss,
                )
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun IntroPhase(
    loanName: String,
    loanAmount: Double,
    currencyCode: String,
    loanColor: Color,
    onSkip: () -> Unit,
    onAddItems: () -> Unit,
) {
    Spacer(Modifier.height(8.dp))

    Text(
        text = "Loan created",
        style = UI.typo.nC.style(
            color = UI.colors.pureInverse.copy(alpha = 0.6f),
            fontWeight = FontWeight.Bold,
        ),
    )

    Spacer(Modifier.height(4.dp))

    Text(
        text = loanName,
        style = UI.typo.nH2.style(
            color = UI.colors.pureInverse,
            fontWeight = FontWeight.Black,
        ),
    )

    Spacer(Modifier.height(4.dp))

    Text(
        text = "${loanAmount.format(currencyCode)} $currencyCode",
        style = UI.typo.b2.style(
            color = loanColor,
            fontWeight = FontWeight.ExtraBold,
        ),
    )

    Spacer(Modifier.height(24.dp))

    Text(
        text = "Break it down into items?",
        style = UI.typo.nB1.style(
            color = UI.colors.pureInverse,
            fontWeight = FontWeight.Bold,
        ),
    )

    Spacer(Modifier.height(8.dp))

    Text(
        text = "Track this loan as a single total, or split it into individual items " +
            "(e.g. \"Coffee\", \"Dinner\") that you can tick off as they're settled.",
        style = UI.typo.b2.style(
            color = UI.colors.pureInverse.copy(alpha = 0.75f),
            fontWeight = FontWeight.Medium,
        ),
    )

    Spacer(Modifier.height(24.dp))

    Button(
        onClick = onAddItems,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = loanColor,
            contentColor = findContrastTextColor(loanColor),
        ),
        shape = UI.shapes.r4,
    ) {
        Text(
            text = "Add items",
            style = UI.typo.b1.style(
                color = findContrastTextColor(loanColor),
                fontWeight = FontWeight.Black,
            ),
        )
    }

    Spacer(Modifier.height(8.dp))

    TextButton(
        onClick = onSkip,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "Skip — track as a single total",
            style = UI.typo.b2.style(
                color = UI.colors.pureInverse.copy(alpha = 0.7f),
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

@Composable
private fun BuilderPhase(
    loanTotal: Double,
    currencyCode: String,
    loanColor: Color,
    entries: List<ItemizeEntry>,
    onEntriesChanged: (List<ItemizeEntry>) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }

    val running = entries.sumOf { it.amount }
    val remaining = loanTotal - running
    val canAdd = title.isNotBlank() &&
        (amountText.replace(",", ".").toDoubleOrNull() ?: 0.0) > 0.0
    val canSave = entries.isNotEmpty()

    Spacer(Modifier.height(8.dp))

    Text(
        text = "Add items",
        style = UI.typo.nH2.style(
            color = UI.colors.pureInverse,
            fontWeight = FontWeight.Black,
        ),
    )

    Spacer(Modifier.height(4.dp))

    Text(
        text = "Loan total: ${loanTotal.format(currencyCode)} $currencyCode",
        style = UI.typo.nC.style(
            color = UI.colors.pureInverse.copy(alpha = 0.7f),
            fontWeight = FontWeight.Bold,
        ),
    )

    Spacer(Modifier.height(16.dp))

    // Current items
    if (entries.isNotEmpty()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(entries) { entry ->
                EntryRow(
                    entry = entry,
                    currencyCode = currencyCode,
                    onRemove = {
                        onEntriesChanged(entries - entry)
                    },
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    // Running summary
    RunningSummary(
        running = running,
        remaining = remaining,
        currencyCode = currencyCode,
        loanColor = loanColor,
    )

    Spacer(Modifier.height(16.dp))

    // New item entry
    OutlinedTextField(
        value = title,
        onValueChange = { title = it },
        label = { Text("Item title") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = UI.colors.pureInverse,
            unfocusedTextColor = UI.colors.pureInverse,
            focusedBorderColor = loanColor,
            unfocusedBorderColor = UI.colors.medium,
        ),
    )

    Spacer(Modifier.height(8.dp))

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = amountText,
            onValueChange = { raw ->
                amountText = raw.filter { it.isDigit() || it == '.' || it == ',' }
            },
            label = { Text("Amount") },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                keyboardType = KeyboardType.Decimal
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = UI.colors.pureInverse,
                unfocusedTextColor = UI.colors.pureInverse,
                focusedBorderColor = loanColor,
                unfocusedBorderColor = UI.colors.medium,
            ),
        )

        Spacer(Modifier.width(8.dp))

        Button(
            onClick = {
                val amt = amountText.replace(",", ".").toDoubleOrNull() ?: 0.0
                if (title.isNotBlank() && amt > 0.0) {
                    onEntriesChanged(
                        entries + ItemizeEntry(title = title.trim(), amount = amt)
                    )
                    title = ""
                    amountText = ""
                }
            },
            enabled = canAdd,
            modifier = Modifier.height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = loanColor,
                contentColor = findContrastTextColor(loanColor),
                disabledContainerColor = UI.colors.medium.copy(alpha = 0.5f),
            ),
            shape = UI.shapes.r4,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add",
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Add",
                style = UI.typo.b2.style(fontWeight = FontWeight.Black),
            )
        }
    }

    Spacer(Modifier.height(20.dp))

    Button(
        onClick = onSave,
        enabled = canSave,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = loanColor,
            contentColor = findContrastTextColor(loanColor),
            disabledContainerColor = UI.colors.medium.copy(alpha = 0.5f),
        ),
        shape = UI.shapes.r4,
    ) {
        Text(
            text = if (entries.isEmpty()) "Add at least one item" else "Save items",
            style = UI.typo.b1.style(fontWeight = FontWeight.Black),
        )
    }

    Spacer(Modifier.height(8.dp))

    TextButton(
        onClick = onCancel,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = "Cancel",
            style = UI.typo.b2.style(
                color = UI.colors.pureInverse.copy(alpha = 0.6f),
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

@Composable
private fun EntryRow(
    entry: ItemizeEntry,
    currencyCode: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(UI.shapes.r4)
            .background(UI.colors.medium.copy(alpha = 0.3f), UI.shapes.r4)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = UI.typo.b2.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.ExtraBold,
                ),
            )
            Text(
                text = "${entry.amount.format(currencyCode)} $currencyCode",
                style = UI.typo.nC.style(
                    color = UI.colors.pureInverse.copy(alpha = 0.75f),
                    fontWeight = FontWeight.Bold,
                ),
            )
        }

        IconButton(onClick = onRemove) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove",
                tint = com.ivy.wallet.ui.theme.Red.copy(alpha = 0.8f),
            )
        }
    }
}

@Composable
private fun RunningSummary(
    running: Double,
    remaining: Double,
    currencyCode: String,
    loanColor: Color,
) {
    val overshoot = remaining < 0
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(UI.shapes.r4)
            .background(loanColor.copy(alpha = 0.12f), UI.shapes.r4)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = "Items total",
                style = UI.typo.nC.style(
                    color = UI.colors.pureInverse.copy(alpha = 0.6f),
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                text = "${running.format(currencyCode)} $currencyCode",
                style = UI.typo.b2.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.Black,
                ),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (overshoot) "Over loan total" else "Remaining",
                style = UI.typo.nC.style(
                    color = if (overshoot) {
                        com.ivy.wallet.ui.theme.Red
                    } else {
                        UI.colors.pureInverse.copy(alpha = 0.6f)
                    },
                    fontWeight = FontWeight.Bold,
                ),
            )
            Text(
                text = "${kotlin.math.abs(remaining).format(currencyCode)} $currencyCode",
                style = UI.typo.b2.style(
                    color = if (overshoot) com.ivy.wallet.ui.theme.Red else loanColor,
                    fontWeight = FontWeight.Black,
                ).copy(
                    textDecoration = TextDecoration.None,
                ),
            )
        }
    }
}
