package com.ivy.sms.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.ivy.sms.domain.model.WildcardRole

/**
 * Visual identity for each wildcard role. Colors loosely match the project's
 * gradient palette (green = income, red = expense, ivy = transfer) so the role
 * a wildcard carries reads consistently across the template list, the mapping
 * screen, and the pending-review queue.
 */
fun colorForRole(role: WildcardRole): Color = when (role) {
    WildcardRole.Unmapped -> Color(0xFF1565C0)        // blue — "tap me"
    WildcardRole.Income -> Color(0xFF2E7D32)          // green
    WildcardRole.Expense -> Color(0xFFC62828)         // red
    WildcardRole.Transfer -> Color(0xFF6A1B9A)        // ivy purple
    WildcardRole.CurrentTotal -> Color(0xFF00838F)    // teal
    WildcardRole.TransactionFee -> Color(0xFFEF6C00)  // orange
    WildcardRole.DateFull -> Color(0xFF455A64)        // slate
    WildcardRole.DateOnly -> Color(0xFF607D8B)        // lighter slate
    WildcardRole.TimeOnly -> Color(0xFF78909C)        // even lighter slate
    WildcardRole.Merchant -> Color(0xFF6D4C41)        // brown — least-prominent role
    WildcardRole.Ignored -> Color(0xFF9E9E9E)         // grey
}

/**
 * Material icon per role — picker tiles render the icon next to the label so the
 * user can tell roles apart even without reading the colors. Easier than scanning
 * a row of color swatches.
 */
fun iconForRole(role: WildcardRole): ImageVector = when (role) {
    WildcardRole.Unmapped -> Icons.Default.HelpOutline
    WildcardRole.Income -> Icons.Default.ArrowDownward         // money flowing IN
    WildcardRole.Expense -> Icons.Default.ArrowUpward          // money flowing OUT
    WildcardRole.Transfer -> Icons.Default.SwapHoriz
    WildcardRole.CurrentTotal -> Icons.Default.AccountBalanceWallet
    WildcardRole.TransactionFee -> Icons.Default.Receipt
    WildcardRole.DateFull -> Icons.Default.CalendarMonth
    WildcardRole.DateOnly -> Icons.Default.CalendarToday
    WildcardRole.TimeOnly -> Icons.Default.AccessTime
    WildcardRole.Merchant -> Icons.Default.Storefront
    WildcardRole.Ignored -> Icons.Default.Block
}
