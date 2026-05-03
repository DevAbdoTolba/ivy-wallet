package com.ivy.sms.ui.wallet

import androidx.compose.runtime.Immutable

@Immutable
data class WalletSmsConfigViewState(
    val walletName: String = "",
    val linkedSender: String? = null,
    val lastSyncStatus: String = "Never synced",
    val syncing: Boolean = false,
    val pendingCount: Int = 0,
    val error: String? = null,
    /** Live progress while a sync is running. Null when idle. */
    val scanProgress: com.ivy.sms.domain.model.ScanProgress? = null,
)
