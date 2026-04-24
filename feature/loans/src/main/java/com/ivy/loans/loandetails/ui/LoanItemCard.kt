package com.ivy.loans.loandetails.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ivy.data.model.LoanItem
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.legacy.utils.format
import com.ivy.wallet.ui.theme.findContrastTextColor

@Composable
fun LoanItemCard(
    loanItem: LoanItem,
    baseCurrency: String,
    loanColor: Color,
    onToggleSettled: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (loanItem.isSettled) {
            loanColor.copy(alpha = 0.18f)
        } else {
            UI.colors.medium.copy(alpha = 0.35f)
        },
        label = "loanItemBg"
    )
    val titleColor by animateColorAsState(
        targetValue = if (loanItem.isSettled) {
            UI.colors.pureInverse.copy(alpha = 0.5f)
        } else {
            UI.colors.pureInverse
        },
        label = "loanItemTitle"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(UI.shapes.r4)
            .background(bgColor, UI.shapes.r4)
            .clickable { onToggleSettled(!loanItem.isSettled) }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SettledMark(
            isSettled = loanItem.isSettled,
            loanColor = loanColor,
            onClick = { onToggleSettled(!loanItem.isSettled) }
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp)
        ) {
            Text(
                text = loanItem.title,
                style = UI.typo.b1.style(
                    fontWeight = FontWeight.ExtraBold,
                    color = titleColor
                ).copy(
                    textDecoration = if (loanItem.isSettled) {
                        TextDecoration.LineThrough
                    } else {
                        TextDecoration.None
                    }
                )
            )
            Text(
                text = loanItem.amount.format(baseCurrency) + " " + baseCurrency,
                style = UI.typo.nC.style(
                    fontWeight = FontWeight.Bold,
                    color = titleColor
                ).copy(
                    textDecoration = if (loanItem.isSettled) {
                        TextDecoration.LineThrough
                    } else {
                        TextDecoration.None
                    }
                )
            )
        }

        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "Edit",
                tint = UI.colors.pureInverse.copy(alpha = 0.55f),
                modifier = Modifier.size(18.dp)
            )
        }

        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = "Delete",
                tint = com.ivy.wallet.ui.theme.Red.copy(alpha = 0.7f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun SettledMark(
    isSettled: Boolean,
    loanColor: Color,
    onClick: () -> Unit
) {
    val fill by animateColorAsState(
        targetValue = if (isSettled) loanColor else Color.Transparent,
        label = "settledFill"
    )
    val borderColor by animateColorAsState(
        targetValue = if (isSettled) loanColor else UI.colors.pureInverse.copy(alpha = 0.45f),
        label = "settledBorder"
    )

    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(fill, CircleShape)
            .border(BorderStroke(2.dp, borderColor), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isSettled) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Settled",
                tint = findContrastTextColor(loanColor),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
