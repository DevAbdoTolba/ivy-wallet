package com.ivy.sms.ui.nav

import androidx.compose.runtime.Composable
import com.ivy.data.model.AccountId
import com.ivy.navigation.PendingReviewScreen
import com.ivy.navigation.Screen
import com.ivy.navigation.SmsExtractionScreen
import com.ivy.navigation.SmsSourceLookupScreen
import com.ivy.navigation.TemplateMappingScreen
import com.ivy.navigation.WalletPendingReviewScreen
import com.ivy.navigation.navigation
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.ui.SmsExtractionScreen as SmsExtractionScreenImpl
import com.ivy.sms.ui.pending.PendingReviewScreen as PendingReviewScreenImpl
import com.ivy.sms.ui.templates.SmsSourceLookupScreen as SmsSourceLookupScreenImpl
import com.ivy.sms.ui.templates.TemplateMappingScreen as TemplateMappingScreenImpl
import java.util.UUID

/**
 * Bridges shared navigation routes to the feature-internal Compose screens.
 * Returns true if the screen was handled by feature:sms-sync.
 *
 * Setup/config no longer routes here — the SmsSetupSheet (IvyModal) is
 * rendered locally by the wallet-edit host screens (P1-4 redesign).
 */
@Composable
fun smsSyncDestination(screen: Screen?): Boolean {
    val nav = navigation()
    return when (screen) {
        is SmsExtractionScreen -> {
            SmsExtractionScreenImpl(walletId = screen.walletId)
            true
        }
        PendingReviewScreen -> {
            PendingReviewScreenImpl()
            true
        }
        is WalletPendingReviewScreen -> {
            val walletId = runCatching { AccountId(UUID.fromString(screen.walletId)) }.getOrNull()
            if (walletId != null) {
                PendingReviewScreenImpl(walletId = walletId)
            }
            true
        }
        is TemplateMappingScreen -> {
            val id = runCatching { SmsTemplateId(UUID.fromString(screen.templateId)) }.getOrNull()
            val walletScope = screen.walletId?.let {
                runCatching { AccountId(UUID.fromString(it)) }.getOrNull()
            }
            if (id != null) {
                TemplateMappingScreenImpl(
                    templateId = id,
                    pendingItemId = screen.pendingItemId,
                    walletScope = walletScope,
                    onSaved = { nav.back() },
                    onIgnoreForever = { nav.back() },
                )
            }
            true
        }
        is SmsSourceLookupScreen -> {
            SmsSourceLookupScreenImpl(transactionId = screen.transactionId)
            true
        }
        else -> false
    }
}
