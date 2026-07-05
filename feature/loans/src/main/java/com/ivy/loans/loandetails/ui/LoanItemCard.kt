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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.legacy.utils.format
import com.ivy.loans.loan.data.DisplayLoanItem
import com.ivy.ui.R
import com.ivy.wallet.ui.theme.Red
import com.ivy.wallet.ui.theme.components.IvyIcon
import com.ivy.wallet.ui.theme.findContrastTextColor
import java.util.UUID

// Single loan-color tint for settled/summary surfaces — ItemizeLoanSheet reuses it
// so checklist rows and the itemize summary stay visually consistent.
const val LOAN_SETTLED_TINT_ALPHA = 0.18f

/**
 * Checklist entry card with the four ledger states (US6):
 * unsettled / partial (half-fill circle + per-entry progress) / settled /
 * legacy-settled ("settled — no transaction"). Tapping the circle emits
 * [onCircleTap] — the ViewModel opens the payment sheet (unsettled/partial)
 * or the un-settle confirmation (settled). Entries with payments expand to a
 * per-payment history with transaction jump-links.
 */
@Composable
@Suppress("LongMethod")
fun LoanItemCard(
    display: DisplayLoanItem,
    baseCurrency: String,
    loanColor: Color,
    onCircleTap: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTransactionClick: (UUID) -> Unit,
    modifier: Modifier = Modifier
) {
    val loanItem = display.loanItem
    val settled = display.settledDerived
    var historyExpanded by remember(loanItem.id) { mutableStateOf(false) }

    val bgColor by animateColorAsState(
        targetValue = if (settled) {
            loanColor.copy(alpha = LOAN_SETTLED_TINT_ALPHA)
        } else {
            UI.colors.medium
        },
        label = "loanItemBg"
    )
    val titleColor by animateColorAsState(
        targetValue = if (settled) {
            UI.colors.pureInverse.copy(alpha = 0.5f)
        } else {
            UI.colors.pureInverse
        },
        label = "loanItemTitle"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(UI.shapes.r4)
            .background(bgColor, UI.shapes.r4)
            .clickable { onCircleTap() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SettledMark(
                settled = settled,
                partial = display.partiallyPaid,
                loanColor = loanColor,
                onClick = onCircleTap
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
                        textDecoration = if (settled) {
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
                        textDecoration = if (settled) {
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

        // Partial state: per-entry progress bar + "paid X · left Y" (US6.1).
        if (display.partiallyPaid) {
            Spacer(Modifier.height(8.dp))
            val fraction = (display.paidAmount / loanItem.amount)
                .toFloat()
                .coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = fraction,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(UI.shapes.rFull),
                color = loanColor,
                trackColor = UI.colors.pure,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "paid ${display.paidAmount.format(baseCurrency)} · " +
                    "left ${display.remaining.format(baseCurrency)}",
                style = UI.typo.c.style(
                    color = UI.colors.gray,
                    fontWeight = FontWeight.Bold,
                )
            )
        }

        // Legacy badge (D6): settled before the ledger existed — honest about
        // having no money movement behind it.
        if (display.legacySettled) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "settled — no transaction",
                style = UI.typo.c.style(
                    color = UI.colors.gray,
                    fontWeight = FontWeight.Bold,
                )
            )
        }

        // Expandable payment history with transaction jump-links (US6.2).
        if (display.payments.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (historyExpanded) {
                    "Hide payments"
                } else {
                    "${display.payments.size} payment${if (display.payments.size == 1) "" else "s"} ▾"
                },
                style = UI.typo.c.style(
                    color = UI.colors.gray,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.clickable { historyExpanded = !historyExpanded }
            )
            if (historyExpanded) {
                Spacer(Modifier.height(4.dp))
                for (payment in display.payments) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 44.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = payment.amount.format(baseCurrency) +
                                (payment.accountName?.let { " · $it" } ?: ""),
                            style = UI.typo.c.style(
                                color = UI.colors.pureInverse,
                                fontWeight = FontWeight.Bold,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = payment.dateLabel,
                            style = UI.typo.c.style(
                                color = UI.colors.gray,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                        payment.transactionId?.let { txId ->
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = "tx ↗",
                                style = UI.typo.c.style(
                                    color = loanColor,
                                    fontWeight = FontWeight.ExtraBold,
                                ),
                                modifier = Modifier.clickable { onTransactionClick(txId) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettledMark(
    settled: Boolean,
    partial: Boolean,
    loanColor: Color,
    onClick: () -> Unit
) {
    val fill by animateColorAsState(
        targetValue = when {
            settled -> loanColor
            partial -> loanColor.copy(alpha = 0.35f)
            else -> Color.Transparent
        },
        label = "settledFill"
    )
    val borderColor by animateColorAsState(
        targetValue = if (settled || partial) {
            loanColor
        } else {
            UI.colors.pureInverse.copy(alpha = 0.45f)
        },
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
        if (settled) {
            IvyIcon(
                modifier = Modifier.size(18.dp),
                icon = R.drawable.ic_check,
                tint = findContrastTextColor(loanColor),
                contentDescription = stringResource(R.string.settled)
            )
        }
    }
}
