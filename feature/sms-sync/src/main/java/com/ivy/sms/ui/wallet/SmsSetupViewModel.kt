package com.ivy.sms.ui.wallet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.data.model.AccountId
import com.ivy.data.repository.AccountRepository
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.data.SmsInboxDataSource
import com.ivy.sms.data.SmsWatermarkPreferences
import com.ivy.sms.domain.model.SyncResult
import com.ivy.sms.domain.usecase.ApplySyncPeriodUseCase
import com.ivy.sms.domain.usecase.LinkSenderToWalletUseCase
import com.ivy.sms.domain.usecase.ScanInboxUseCase
import com.ivy.sms.startup.SmsSyncAppStartup
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/**
 * Backs the one-sheet SMS setup (SmsSetupSheet): sender + period + auto-route
 * picked in one place, one "Save & Sync now" CTA. Replaces the
 * WalletSmsConfig / SenderPicker / PeriodPicker screen trio.
 *
 * Threading mirrors the patterns proven on the user's device: reads run on a
 * raw Thread + runBlocking (viewModelScope launches were observed never
 * executing for load-style work — see the deleted SenderPickerViewModel's
 * notes), while the save path uses viewModelScope (the path PeriodPicker
 * proved working). The sync itself is fired through [SmsSyncAppStartup]'s
 * application scope so navigating away from the sheet can't cancel it.
 */
@Stable
@HiltViewModel
class SmsSetupViewModel @Inject constructor(
    private val accountRepo: AccountRepository,
    private val senderRepo: SenderAccountLinkRepository,
    private val pendingRepo: PendingReviewItemRepository,
    private val inbox: SmsInboxDataSource,
    private val linkSender: LinkSenderToWalletUseCase,
    private val applySyncPeriod: ApplySyncPeriodUseCase,
    private val appStartup: SmsSyncAppStartup,
    private val scanInbox: ScanInboxUseCase,
    private val prefs: SmsWatermarkPreferences,
) : ComposeViewModel<SmsSetupViewState, SmsSetupEvent>() {

    private var state by mutableStateOf(SmsSetupViewState())
    private var walletId: AccountId? = null

    init {
        // Mirror the global scan progress so the sheet (manage mode) can show
        // a live "Syncing…" hint while a scan runs.
        viewModelScope.launch {
            scanInbox.progress.collectLatest { p ->
                state = state.copy(scanProgress = p, syncing = p != null && state.syncing)
            }
        }
        // Terminal sync outcomes. The progress mirror above can NEVER clear
        // `syncing` for a sync that short-circuits before the scan starts
        // (permission missing, renormalization Left, thrown scan) — no
        // progress is emitted on those paths, so the primary button froze on
        // a disabled "Syncing…" forever. syncFinished fires once per finished
        // attempt, including those short-circuits, and also carries failures
        // so they surface instead of dying in logcat.
        viewModelScope.launch {
            appStartup.syncFinished.collect { result ->
                val wasSyncing = state.syncing
                val failure = when (result) {
                    is SyncResult.Failed -> "Sync failed — ${result.reason}"
                    is SyncResult.PermissionMissing ->
                        "Ivy needs SMS permission to sync. Allow it and try again."
                    else -> null
                }
                state = state.copy(
                    syncing = false,
                    error = when {
                        !wasSyncing -> state.error
                        failure != null -> failure
                        else -> null
                    },
                )
            }
        }
    }

    @Composable
    override fun uiState(): SmsSetupViewState = state

    fun load(walletId: AccountId) {
        if (this.walletId != walletId) {
            // Fresh wallet — drop any selection carried over from a previous
            // sheet session on another wallet.
            state = SmsSetupViewState()
        } else {
            // Same wallet, fresh sheet session: this VM survives across sheet
            // openings (the legacy hosts' ViewModelStore is never cleared),
            // so one-shot UI flags from the LAST session — an error banner or
            // a stuck "Syncing…" — must not replay on reopen.
            state = state.copy(error = null, syncing = false)
        }
        this.walletId = walletId
        Thread {
            try {
                runBlocking {
                    val account = accountRepo.findById(walletId) ?: return@runBlocking
                    val allLinks = senderRepo.findAll().getOrNull().orEmpty()
                    val link = allLinks.firstOrNull { it.accountId == walletId }
                    val linkedSenderIds = allLinks.map { it.senderId }.toSet()
                    val senders = if (link == null) {
                        inbox.listSenders().getOrNull().orEmpty()
                            .filter { it.senderId !in linkedSenderIds }
                            .map {
                                SenderOptionViewState(
                                    senderId = it.senderId,
                                    messageCount = it.messageCount,
                                    lastMessageEpochMillis = it.lastMessageEpochMillis,
                                )
                            }
                    } else {
                        emptyList()
                    }
                    val pendingCount = if (link == null) {
                        0
                    } else {
                        pendingRepo.findAll().getOrNull().orEmpty()
                            .count { it.sms.senderId == link.senderId }
                    }
                    val autoRoute = link?.let {
                        prefs.autoRouteEnabled(it.senderId).getOrNull()
                    }
                    state = state.copy(
                        walletName = account.name.value,
                        linkedSender = link?.senderId,
                        lastSyncStatus = formatLastSyncStatus(link?.watermark),
                        pendingCount = pendingCount,
                        senders = senders.toImmutableList(),
                        selectedSender = state.selectedSender ?: defaultSender(senders),
                        autoRoute = autoRoute ?: state.autoRoute,
                        loaded = true,
                    )
                }
            } catch (t: Throwable) {
                Timber.e(t, "SmsSetup load() crashed")
                state = state.copy(error = "load crashed: ${t.message}", loaded = true)
            }
        }.start()
    }

    override fun onEvent(event: SmsSetupEvent) {
        when (event) {
            is SmsSetupEvent.SelectSender ->
                state = state.copy(selectedSender = event.senderId, error = null)
            is SmsSetupEvent.SelectPeriod ->
                state = state.copy(selectedPeriod = event.option)
            is SmsSetupEvent.SetAutoRoute -> setAutoRoute(event.enabled)
            SmsSetupEvent.SaveAndSync -> saveAndSync()
            SmsSetupEvent.SyncNow -> syncNow()
            is SmsSetupEvent.ScanFurtherBack -> scanFurtherBack(event.option)
            SmsSetupEvent.Unlink -> unlink()
        }
    }

    /** Called by the sheet after it has handled the post-save navigation. */
    fun consumeSaved() {
        if (state.saved) state = state.copy(saved = false)
    }

    private fun setAutoRoute(enabled: Boolean) {
        state = state.copy(autoRoute = enabled)
        // Manage mode: the sender is known — persist immediately. Setup mode
        // persists on Save & Sync once the sender choice is final.
        val sender = state.linkedSender ?: return
        viewModelScope.launch {
            prefs.writeAutoRoute(sender, enabled)
                .onLeft { Timber.w("SmsSetup autoRoute persist failed: $it") }
        }
    }

    private fun saveAndSync() {
        val wallet = walletId ?: run {
            state = state.copy(error = "Wallet hasn't loaded yet — try again in a moment.")
            return
        }
        val sender = state.selectedSender ?: run {
            state = state.copy(error = "Pick a sender first.")
            return
        }
        state = state.copy(saving = true, error = null)
        viewModelScope.launch {
            try {
                linkSender(sender, wallet).fold(
                    ifLeft = { rawError ->
                        state = state.copy(saving = false, error = humanizeLinkError(rawError, sender))
                    },
                    ifRight = {
                        prefs.writeAutoRoute(sender, state.autoRoute)
                            .onLeft { Timber.w("SmsSetup autoRoute persist failed: $it") }
                        // Period lower bound lives PER SENDER on the link —
                        // never the legacy global key (fixes the re-ask +
                        // full-rescan friction of the old config screen).
                        applySyncPeriod(wallet, lowerBoundFor(state.selectedPeriod))
                            .onLeft { Timber.w("SmsSetup applySyncPeriod failed: $it") }
                        // Application-scoped fire-and-forget: the sheet
                        // dismisses and navigation to the review screen
                        // must not cancel the first scan.
                        appStartup.triggerManualSync()
                        state = state.copy(saving = false, saved = true)
                    },
                )
            } catch (t: Throwable) {
                Timber.e(t, "SmsSetup saveAndSync() crashed")
                state = state.copy(saving = false, error = "save crashed: ${t.message}")
            }
        }
    }

    /** Manage mode: incremental — reads only rows newer than the per-sender
     *  watermark. No lower-bound write, no watermark reset (FR-005a). */
    private fun syncNow() {
        state = state.copy(syncing = true, error = null)
        appStartup.triggerManualSync()
    }

    private fun scanFurtherBack(option: PeriodChipOption) {
        val wallet = walletId ?: return
        state = state.copy(syncing = true, error = null)
        viewModelScope.launch {
            try {
                // Only an explicitly LONGER period moves the link's lower
                // bound back (and clears its watermark for a gap rescan) —
                // ApplySyncPeriodUseCase owns those semantics.
                applySyncPeriod(wallet, lowerBoundFor(option))
                    .onLeft { Timber.w("SmsSetup scanFurtherBack failed: $it") }
                appStartup.triggerManualSync()
            } catch (t: Throwable) {
                Timber.e(t, "SmsSetup scanFurtherBack() crashed")
                state = state.copy(syncing = false, error = "scan crashed: ${t.message}")
            }
        }
    }

    private fun unlink() {
        val wallet = walletId ?: return
        val sender = state.linkedSender ?: return
        Thread {
            try {
                runBlocking {
                    // The link row carries the per-sender watermark and lower
                    // bound, so deleting it means a future re-link starts
                    // fresh and backfills.
                    senderRepo.delete(sender)
                }
                load(wallet)
            } catch (t: Throwable) {
                Timber.e(t, "SmsSetup unlink() crashed")
                state = state.copy(error = "unlink crashed: ${t.message}")
            }
        }.start()
    }

    private fun lowerBoundFor(option: PeriodChipOption): Long {
        val days = option.days ?: return 0L
        return Instant.now().minus(days.toLong(), ChronoUnit.DAYS).toEpochMilli()
    }

    private fun formatLastSyncStatus(watermark: Instant?): String {
        if (watermark == null) return "Never synced"
        val ago = Duration.between(watermark, Instant.now())
        val minutes = ago.toMinutes()
        return when {
            minutes < 1 -> "Last sync: just now"
            minutes < 60 -> "Last sync: $minutes min ago"
            minutes < 1440 -> "Last sync: ${minutes / 60} h ago"
            else -> "Last sync: ${minutes / 1440} d ago"
        }
    }
}

/**
 * Maps the developer-facing error strings the repo returns into a sentence the
 * user can understand and act on.
 */
internal fun humanizeLinkError(raw: String, senderId: String): String {
    val key = raw.substringBefore(':').trim()
    return when (key) {
        "LINK_CONFLICT" -> {
            // Repo's message includes the wallet name after the colon.
            val tail = raw.substringAfter(':', "").trim()
            if (tail.isNotBlank()) "\"$senderId\" is already feeding another wallet — $tail"
            else "\"$senderId\" is already linked to another wallet."
        }
        "STORAGE_ERROR" -> "Couldn't save the link. Try again, or restart the app if it keeps failing."
        "PERMISSION_DENIED" -> "Ivy needs SMS permission to read your inbox."
        else -> raw
    }
}
