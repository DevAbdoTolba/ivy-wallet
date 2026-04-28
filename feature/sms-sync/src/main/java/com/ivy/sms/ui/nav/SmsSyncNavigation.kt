package com.ivy.sms.ui.nav

import androidx.compose.runtime.Composable
import com.ivy.navigation.LinkSenderToWalletScreen
import com.ivy.navigation.PendingReviewScreen
import com.ivy.navigation.Screen
import com.ivy.navigation.SmsExtractionScreen
import com.ivy.navigation.SmsSourceLookupScreen
import com.ivy.navigation.TemplateMappingScreen
import com.ivy.navigation.navigation
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.ui.SmsExtractionScreen as SmsExtractionScreenImpl
import com.ivy.sms.ui.pending.PendingReviewScreen as PendingReviewScreenImpl
import com.ivy.sms.ui.templates.LinkSenderToWalletScreen as LinkSenderToWalletScreenImpl
import com.ivy.sms.ui.templates.SmsSourceLookupScreen as SmsSourceLookupScreenImpl
import com.ivy.sms.ui.templates.TemplateMappingScreen as TemplateMappingScreenImpl
import java.util.UUID

/**
 * Bridges shared navigation routes to the feature-internal Compose screens.
 * Returns true if the screen was handled by feature:sms-sync.
 */
@Composable
fun smsSyncDestination(screen: Screen?): Boolean {
    val nav = navigation()
    return when (screen) {
        SmsExtractionScreen -> {
            SmsExtractionScreenImpl()
            true
        }
        PendingReviewScreen -> {
            PendingReviewScreenImpl()
            true
        }
        is TemplateMappingScreen -> {
            val id = runCatching { SmsTemplateId(UUID.fromString(screen.templateId)) }.getOrNull()
            if (id != null) {
                TemplateMappingScreenImpl(
                    templateId = id,
                    onSaved = { nav.back() },
                )
            }
            true
        }
        is LinkSenderToWalletScreen -> {
            LinkSenderToWalletScreenImpl(
                senderId = screen.senderId,
                onSaved = { nav.back() },
            )
            true
        }
        is SmsSourceLookupScreen -> {
            SmsSourceLookupScreenImpl(transactionId = screen.transactionId)
            true
        }
        else -> false
    }
}
