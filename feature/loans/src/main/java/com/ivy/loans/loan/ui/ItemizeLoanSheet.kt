package com.ivy.loans.loan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.legacy.utils.format
import com.ivy.loans.loan.ItemizeEntry
import com.ivy.loans.loan.ItemizeSheetData
import com.ivy.loans.loandetails.ui.LOAN_SETTLED_TINT_ALPHA
import com.ivy.loans.loandetails.ui.LoanItemModal
import com.ivy.ui.R
import com.ivy.wallet.ui.theme.Red
import com.ivy.wallet.ui.theme.components.IvyIcon
import com.ivy.wallet.ui.theme.components.IvyOutlinedButton
import com.ivy.wallet.ui.theme.modal.IvyModal
import com.ivy.wallet.ui.theme.modal.ModalSave
import com.ivy.wallet.ui.theme.modal.ModalSkip
import com.ivy.wallet.ui.theme.modal.ModalTitle
import java.util.UUID
import kotlin.math.abs

@Composable
fun BoxWithConstraintsScope.ItemizeLoanSheet(
    data: ItemizeSheetData?,
    onDismiss: () -> Unit,
    onSave: (List<ItemizeEntry>) -> Unit,
) {
    if (data == null) return

    val loanColor = Color(data.loanColorArgb)
    var entries by remember(data.loanId) { mutableStateOf<List<ItemizeEntry>>(emptyList()) }
    var addItemModalVisible by remember(data.loanId) { mutableStateOf(false) }
    val modalId = remember(data.loanId) { UUID.randomUUID() }

    IvyModal(
        id = modalId,
        visible = true,
        dismiss = onDismiss,
        SecondaryActions = {
            Spacer(Modifier.width(16.dp))

            ModalSkip {
                onDismiss()
            }
        },
        PrimaryAction = {
            ModalSave(enabled = entries.isNotEmpty()) {
                onSave(entries)
            }
        }
    ) {
        Spacer(Modifier.height(32.dp))

        Text(
            modifier = Modifier.padding(horizontal = 32.dp),
            text = stringResource(R.string.loan_created),
            style = UI.typo.c.style(
                color = UI.colors.gray,
                fontWeight = FontWeight.SemiBold
            )
        )

        Spacer(Modifier.height(4.dp))

        ModalTitle(text = data.loanName)

        Spacer(Modifier.height(4.dp))

        Text(
            modifier = Modifier.padding(horizontal = 32.dp),
            text = "${data.loanAmount.format(data.currencyCode)} ${data.currencyCode}",
            style = UI.typo.nB2.style(
                color = loanColor,
                fontWeight = FontWeight.ExtraBold
            )
        )

        Spacer(Modifier.height(24.dp))

        Text(
            modifier = Modifier.padding(horizontal = 32.dp),
            text = stringResource(R.string.break_loan_into_items),
            style = UI.typo.b2.style(
                color = UI.colors.pureInverse,
                fontWeight = FontWeight.ExtraBold
            )
        )

        Spacer(Modifier.height(8.dp))

        Text(
            modifier = Modifier.padding(horizontal = 32.dp),
            text = stringResource(R.string.itemize_loan_description),
            style = UI.typo.c.style(
                color = UI.colors.gray,
                fontWeight = FontWeight.Medium
            )
        )

        Spacer(Modifier.height(24.dp))

        entries.forEach { entry ->
            EntryRow(
                entry = entry,
                currencyCode = data.currencyCode,
                onRemove = {
                    entries = entries - entry
                }
            )

            Spacer(Modifier.height(8.dp))
        }

        if (entries.isNotEmpty()) {
            RunningSummary(
                running = entries.sumOf { it.amount },
                remaining = data.loanAmount - entries.sumOf { it.amount },
                currencyCode = data.currencyCode,
                loanColor = loanColor
            )

            Spacer(Modifier.height(16.dp))
        }

        IvyOutlinedButton(
            modifier = Modifier.padding(horizontal = 16.dp),
            text = stringResource(R.string.add_item),
            iconStart = R.drawable.ic_plus
        ) {
            addItemModalVisible = true
        }

        Spacer(Modifier.height(24.dp))
    }

    LoanItemModal(
        visible = addItemModalVisible,
        loanItem = null,
        baseCurrency = data.currencyCode,
        onSave = { title, amount ->
            entries = entries + ItemizeEntry(title = title, amount = amount)
            addItemModalVisible = false
        },
        onDismiss = {
            addItemModalVisible = false
        }
    )
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
            .padding(horizontal = 16.dp)
            .clip(UI.shapes.r4)
            .background(UI.colors.medium, UI.shapes.r4)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.title,
                style = UI.typo.b2.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.ExtraBold
                )
            )
            Text(
                text = "${entry.amount.format(currencyCode)} $currencyCode",
                style = UI.typo.nC.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        IvyIcon(
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onRemove)
                .padding(8.dp),
            icon = R.drawable.ic_dismiss,
            tint = Red,
            contentDescription = stringResource(R.string.remove)
        )
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
            .padding(horizontal = 16.dp)
            .clip(UI.shapes.r4)
            .background(loanColor.copy(alpha = LOAN_SETTLED_TINT_ALPHA), UI.shapes.r4)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = stringResource(R.string.items_total),
                style = UI.typo.c.style(
                    color = UI.colors.gray,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text = "${running.format(currencyCode)} $currencyCode",
                style = UI.typo.nB2.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.ExtraBold
                )
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = if (overshoot) {
                    stringResource(R.string.over_loan_total)
                } else {
                    stringResource(R.string.remaining)
                },
                style = UI.typo.c.style(
                    color = if (overshoot) Red else UI.colors.gray,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text = "${abs(remaining).format(currencyCode)} $currencyCode",
                style = UI.typo.nB2.style(
                    color = if (overshoot) Red else loanColor,
                    fontWeight = FontWeight.ExtraBold
                )
            )
        }
    }
}
