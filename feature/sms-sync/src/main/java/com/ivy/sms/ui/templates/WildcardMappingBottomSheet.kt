package com.ivy.sms.ui.templates

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.ui.theme.colorForRole
import com.ivy.sms.ui.theme.iconForRole
import com.ivy.wallet.ui.theme.components.IvyOutlinedButton

/**
 * Role keypad for a single wildcard. Mirrors the project's transaction-type keypad
 * vibe: full-width tile rows with role-specific accent and a clear selected state.
 * The container is a custom bottom sheet (no Material3 ModalBottomSheet) so the
 * background, text colors, and radius all flow from `UI.colors.*` and follow the
 * app's LIGHT / DARK / AMOLED themes.
 */
@Composable
fun WildcardMappingBottomSheet(
    wildcardId: WildcardId,
    currentRoles: Map<WildcardId, WildcardRole>,
    onChoose: (WildcardRole) -> Unit,
    onDismiss: () -> Unit,
) {
    val selfRole = currentRoles[wildcardId] ?: WildcardRole.Unmapped

    val tiles: List<RoleTile> = listOf(
        RoleTile(WildcardRole.Income, "Income", "amount you received"),
        RoleTile(WildcardRole.Expense, "Expense", "amount you spent"),
        RoleTile(WildcardRole.Transfer, "Transfer", "amount moved between wallets"),
        RoleTile(WildcardRole.CurrentTotal, "Current Total", "running balance after this txn"),
        RoleTile(WildcardRole.TransactionFee, "Transaction Fee", "fee charged"),
        RoleTile(WildcardRole.DateFull, "Date + Time", "full date and time, e.g. 28/04/2026 10:30"),
        RoleTile(WildcardRole.DateOnly, "Date only", "date without time, e.g. 28/04/2026"),
        RoleTile(WildcardRole.TimeOnly, "Time only", "time without date, e.g. 10:30"),
        RoleTile(WildcardRole.Merchant, "Merchant", "free text added to the description"),
        RoleTile(WildcardRole.Ignored, "Ignored", "skip this part of the message"),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(UI.shapes.r2Top)
                .background(UI.colors.pure)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .clickable(enabled = false) { },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Drag handle for the standard sheet feel.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(UI.shapes.rFull)
                    .background(UI.colors.medium),
            )
            Spacer(Modifier.height(8.dp))

            Text(
                text = "What does this part represent?",
                style = UI.typo.h2.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.ExtraBold,
                ),
            )

            Spacer(Modifier.height(4.dp))

            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tiles.forEach { tile ->
                    RoleTileRow(
                        tile = tile,
                        selected = selfRole == tile.role,
                        onClick = {
                            onChoose(tile.role)
                            onDismiss()
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            IvyOutlinedButton(
                text = "Cancel",
                iconStart = null,
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss,
            )
        }
    }
}

private data class RoleTile(
    val role: WildcardRole,
    val title: String,
    val subtitle: String,
)

@Composable
private fun RoleTileRow(
    tile: RoleTile,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = colorForRole(tile.role)
    val bg = if (selected) accent else UI.colors.medium
    val fg = if (selected) Color.White else UI.colors.pureInverse
    val subFg = if (selected) Color.White.copy(alpha = 0.85f) else UI.colors.gray

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(UI.shapes.r4)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = iconForRole(tile.role),
            contentDescription = null,
            tint = if (selected) Color.White else accent,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(
                text = tile.title,
                style = UI.typo.b2.style(
                    color = fg,
                    fontWeight = FontWeight.ExtraBold,
                ),
            )
            Text(
                text = tile.subtitle,
                style = UI.typo.c.style(
                    color = subFg,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}
