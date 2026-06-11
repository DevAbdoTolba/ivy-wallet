package com.ivy.loans.loandetails.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ivy.data.model.LoanItem
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.legacy.utils.format
import com.ivy.ui.R
import com.ivy.wallet.ui.theme.Red
import com.ivy.wallet.ui.theme.components.IvyIcon
import com.ivy.wallet.ui.theme.findContrastTextColor

// Single loan-color tint for settled/summary surfaces — ItemizeLoanSheet reuses it
// so checklist rows and the itemize summary stay visually consistent.
const val LOAN_SETTLED_TINT_ALPHA = 0.18f

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
            loanColor.copy(alpha = LOAN_SETTLED_TINT_ALPHA)
        } else {
            UI.colors.medium
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
            IvyIcon(
                modifier = Modifier.size(18.dp),
                icon = R.drawable.ic_edit,
                tint = UI.colors.pureInverse.copy(alpha = 0.55f),
                contentDescription = stringResource(R.string.edit)
            )
        }

        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            IvyIcon(
                modifier = Modifier.size(18.dp),
                icon = R.drawable.ic_delete,
                tint = Red.copy(alpha = 0.7f),
                contentDescription = stringResource(R.string.delete)
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
            IvyIcon(
                modifier = Modifier.size(18.dp),
                icon = R.drawable.ic_check,
                tint = findContrastTextColor(loanColor),
                contentDescription = stringResource(R.string.settled)
            )
        }
    }
}
