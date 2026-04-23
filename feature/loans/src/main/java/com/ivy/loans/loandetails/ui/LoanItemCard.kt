package com.ivy.loans.loandetails.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ivy.data.model.LoanItem
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.legacy.utils.format

@Composable
fun LoanItemCard(
    loanItem: LoanItem,
    baseCurrency: String,
    onToggleSettled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(UI.shapes.r4)
            .background(UI.colors.medium.copy(alpha = 0.5f), UI.shapes.r4)
            .clickable { onEdit() }
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = loanItem.isSettled,
            onCheckedChange = onToggleSettled,
            colors = CheckboxDefaults.colors(
                checkedColor = UI.colors.pureInverse,
                uncheckedColor = UI.colors.pureInverse.copy(alpha = 0.6f)
            )
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp)
        ) {
            Text(
                text = loanItem.title,
                style = UI.typo.b1.style(
                    fontWeight = FontWeight.ExtraBold,
                    color = UI.colors.pureInverse
                )
            )
        }

        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = loanItem.amount.format(baseCurrency),
                style = UI.typo.b1.style(
                    fontWeight = FontWeight.Black,
                    color = if (loanItem.isSettled) UI.colors.pureInverse.copy(alpha = 0.5f) else UI.colors.pureInverse
                )
            )
            
            Row {
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "Edit",
                        tint = UI.colors.pureInverse.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }

                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = com.ivy.wallet.ui.theme.Red.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}
