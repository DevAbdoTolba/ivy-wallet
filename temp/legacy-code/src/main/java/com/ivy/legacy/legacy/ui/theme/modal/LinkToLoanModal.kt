package com.ivy.wallet.ui.theme.modal

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ivy.base.model.LoanRecordType
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.legacy.datamodel.Loan
import com.ivy.legacy.utils.format
import com.ivy.wallet.domain.deprecated.logic.loantrasactions.inferLoanLinkDirection
import com.ivy.wallet.ui.theme.Ivy
import java.util.UUID

/**
 * "Link to loan" sheet (US7 / design D4, manual-only): step 1 pick the loan,
 * step 2 (repayments only) choose how the amount pours into checklist entries.
 * The CTA states the iron rule out loud — linking NEVER creates a second
 * transaction; the money already moved once.
 */
@Composable
@Suppress("LongMethod")
fun BoxScope.LinkToLoanModal(
    visible: Boolean,
    loans: List<Loan>,
    moneyOut: Boolean,
    amount: Double,
    baseCurrency: String,
    onLink: (loan: Loan, autoAllocateToEntries: Boolean) -> Unit,
    dismiss: () -> Unit,
) {
    var selectedLoan by remember(visible) { mutableStateOf<Loan?>(null) }
    var autoAllocate by remember(visible) { mutableStateOf(true) }
    val modalId = remember(visible) { UUID.randomUUID() }

    val direction = selectedLoan?.let { inferLoanLinkDirection(moneyOut, it.type) }

    IvyModal(
        id = modalId,
        visible = visible,
        dismiss = dismiss,
        PrimaryAction = {
            ModalSet(
                label = "Link — no new transaction",
                enabled = selectedLoan != null,
            ) {
                selectedLoan?.let { onLink(it, autoAllocate) }
            }
        },
    ) {
        Spacer(Modifier.height(32.dp))

        ModalTitle(text = "Link ${amount.format(baseCurrency)} $baseCurrency to a loan")

        Spacer(Modifier.height(16.dp))

        if (loans.isEmpty()) {
            Text(
                modifier = Modifier.padding(horizontal = 32.dp),
                text = "No loans yet — create one from the Loans screen first.",
                style = UI.typo.b2.style(
                    color = UI.colors.gray,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }

        for (loan in loans) {
            val selected = loan.id == selectedLoan?.id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp)
                    .border(
                        width = 2.dp,
                        color = if (selected) Ivy else UI.colors.medium,
                        shape = UI.shapes.r4,
                    )
                    .background(UI.colors.pure, UI.shapes.r4)
                    .clickable { selectedLoan = loan }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = loan.name,
                    style = UI.typo.b2.style(
                        color = UI.colors.pureInverse,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                    ),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = loan.humanReadableType(),
                    style = UI.typo.c.style(
                        color = UI.colors.gray,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }

        // What this link MEANS, from the 4-row direction table — the user
        // never has to classify anything themselves (FR-022).
        direction?.let { dir ->
            Spacer(Modifier.height(12.dp))
            Text(
                modifier = Modifier.padding(horizontal = 32.dp),
                text = when {
                    dir == LoanRecordType.INCREASE && moneyOut ->
                        "You lent more — the loan total owed to you grows."
                    dir == LoanRecordType.INCREASE ->
                        "You borrowed more — the loan total you owe grows."
                    moneyOut ->
                        "You paid back — the loan shrinks."
                    else ->
                        "They paid you back — the loan shrinks."
                },
                style = UI.typo.c.style(
                    color = UI.colors.gray,
                    fontWeight = FontWeight.Bold,
                ),
            )

            if (dir == LoanRecordType.DECREASE) {
                Spacer(Modifier.height(12.dp))
                AllocationChoice(
                    label = "Fill entries oldest-first",
                    selected = autoAllocate,
                ) { autoAllocate = true }
                Spacer(Modifier.height(6.dp))
                AllocationChoice(
                    label = "Just reduce the loan total",
                    selected = !autoAllocate,
                ) { autoAllocate = false }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AllocationChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (selected) "◉" else "○",
            style = UI.typo.b2.style(
                color = if (selected) Ivy else UI.colors.gray,
                fontWeight = FontWeight.ExtraBold,
            ),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = label,
            style = UI.typo.b2.style(
                color = UI.colors.pureInverse,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            ),
        )
    }
}

private fun Loan.humanReadableType(): String = when (type) {
    com.ivy.data.model.LoanType.LEND -> "Lent"
    com.ivy.data.model.LoanType.BORROW -> "Borrowed"
}
