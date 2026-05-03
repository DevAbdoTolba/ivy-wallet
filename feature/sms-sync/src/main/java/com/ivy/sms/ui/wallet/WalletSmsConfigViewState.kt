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
    /** False until the first DB read completes — prevents the screen from
     *  flashing the "No SMS chat is linked yet" empty card while the back-
     *  navigation reload is still in flight. */
    val loaded: Boolean = false,
    /** Set true after the user comes back from the sender picker so the
     *  screen pops the sync-period sheet immediately. The screen flips this
     *  back to false via [com.ivy.sms.ui.wallet.WalletSmsConfigViewModel.consumeAutoOpenSyncSheet]
     *  once the sheet is shown so a back-navigation refresh doesn't re-open it. */
    val autoOpenSyncSheet: Boolean = false,
)
