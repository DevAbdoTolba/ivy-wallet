package com.ivy.sms.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.ivy.navigation.PendingReviewScreen
import com.ivy.navigation.TemplateMappingScreen
import com.ivy.navigation.WalletPendingReviewScreen
import com.ivy.navigation.navigation
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.ui.permission.PermissionGate
import com.ivy.sms.ui.templates.TemplateListScreen

/**
 * Thin wrapper: permission gate + the template list. The old global
 * period-picker gate is gone — sync periods are per-sender now, picked in
 * the SMS setup sheet (and extendable via the list's "Scan further back").
 */
@Composable
fun SmsExtractionScreen(walletId: String? = null) {
    val nav = navigation()

    PermissionGate(modifier = Modifier.fillMaxSize()) {
        TemplateListScreen(
            walletId = walletId,
            onOpenTemplate = { id: SmsTemplateId ->
                // Carry the wallet scope into the mapper so its save
                // reprocess only touches THIS wallet's pending items.
                nav.navigateTo(
                    TemplateMappingScreen(
                        templateId = id.value.toString(),
                        walletId = walletId,
                    )
                )
            },
            onOpenPendingReview = {
                // Wallet-scoped review when we have a wallet, global
                // review otherwise.
                nav.navigateTo(
                    if (walletId != null) {
                        WalletPendingReviewScreen(walletId)
                    } else {
                        PendingReviewScreen
                    }
                )
            },
        )
    }
}
