package com.ivy.sms.ui.wallet

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

/** Single-tap period chip. [days] == null means "All time" (lower bound 0). */
@Immutable
data class PeriodChipOption(
    val label: String,
    val days: Int?,
)

/** 7d / 30d / 90d / 1y / All — same bounds the old SyncPeriodSheet offered. */
val SETUP_PERIOD_CHIPS: List<PeriodChipOption> = listOf(
    PeriodChipOption("7d", 7),
    PeriodChipOption("30d", 30),
    PeriodChipOption("90d", 90),
    PeriodChipOption("1y", 365),
    PeriodChipOption("All", null),
)

val DEFAULT_PERIOD_CHIP: PeriodChipOption = SETUP_PERIOD_CHIPS[1] // 30d

@Immutable
data class SenderOptionViewState(
    val senderId: String,
    val messageCount: Int,
    val lastMessageEpochMillis: Long,
)

/**
 * The setup default: most recent unlinked sender. [senders] must already be
 * recency-ranked (SmsInboxDataSource.listSenders) and exclude linked senders.
 */
internal fun defaultSender(senders: List<SenderOptionViewState>): String? =
    senders.firstOrNull()?.senderId

@Immutable
data class SmsSetupViewState(
    val walletName: String = "",
    /** False until the first DB/inbox read completes. */
    val loaded: Boolean = false,
    /** Non-null = manage mode: this wallet already has a linked sender. */
    val linkedSender: String? = null,
    val lastSyncStatus: String = "Never synced",
    /** Pending review items whose sender is linked to THIS wallet. */
    val pendingCount: Int = 0,
    /** Unlinked senders, recency-ranked. Empty in manage mode. */
    val senders: ImmutableList<SenderOptionViewState> = persistentListOf(),
    val selectedSender: String? = null,
    val selectedPeriod: PeriodChipOption = DEFAULT_PERIOD_CHIP,
    /** "Create transactions automatically" — persisted per sender on save. */
    val autoRoute: Boolean = true,
    val saving: Boolean = false,
    val syncing: Boolean = false,
    /** Live progress while a sync is running. Null when idle. */
    val scanProgress: com.ivy.sms.domain.model.ScanProgress? = null,
    val error: String? = null,
    /** One-shot: set after a successful Save & Sync — the sheet navigates to
     *  the wallet-scoped review and dismisses, then consumes this flag. */
    val saved: Boolean = false,
)

sealed interface SmsSetupEvent {
    data class SelectSender(val senderId: String) : SmsSetupEvent
    data class SelectPeriod(val option: PeriodChipOption) : SmsSetupEvent
    data class SetAutoRoute(val enabled: Boolean) : SmsSetupEvent
    data object SaveAndSync : SmsSetupEvent

    /** Manage mode: incremental sync — no lower bound, no watermark reset. */
    data object SyncNow : SmsSetupEvent

    /** Manage mode: gap-only rescan from the picked period's lower bound. */
    data class ScanFurtherBack(val option: PeriodChipOption) : SmsSetupEvent
    data object Unlink : SmsSetupEvent
}
