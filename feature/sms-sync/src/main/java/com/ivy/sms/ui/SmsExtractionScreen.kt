package com.ivy.sms.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.ivy.navigation.PendingReviewScreen
import com.ivy.navigation.TemplateMappingScreen
import com.ivy.navigation.WalletPendingReviewScreen
import com.ivy.navigation.navigation
import com.ivy.sms.data.SmsWatermarkPreferences
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.ui.period.PeriodPickerScreen
import com.ivy.sms.ui.permission.PermissionGate
import com.ivy.sms.ui.templates.TemplateListScreen
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SmsExtractionEntryPoint {
    fun watermarks(): SmsWatermarkPreferences
}

@Composable
fun SmsExtractionScreen(walletId: String? = null) {
    val context = LocalContext.current
    val watermarks = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            SmsExtractionEntryPoint::class.java,
        ).watermarks()
    }

    var hasPicked by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(watermarks) {
        hasPicked = watermarks.scanLowerBound().getOrNull() != null
    }

    val nav = navigation()
    val refreshHasPicked: () -> Unit = {
        hasPicked = true
    }

    PermissionGate(modifier = Modifier.fillMaxSize()) {
        when (hasPicked) {
            null -> { /* loading */ }
            false -> PeriodPickerScreen(onConfirmed = refreshHasPicked)
            true -> TemplateListScreen(
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
                onScanFurtherBack = {
                    hasPicked = false
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
}
