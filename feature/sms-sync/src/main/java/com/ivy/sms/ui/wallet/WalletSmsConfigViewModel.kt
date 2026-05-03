package com.ivy.sms.ui.wallet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ivy.data.model.AccountId
import com.ivy.data.repository.AccountRepository
import androidx.lifecycle.viewModelScope
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.data.SmsWatermarkPreferences
import com.ivy.sms.domain.JustLinkedSignal
import com.ivy.sms.domain.model.SyncResult
import com.ivy.sms.domain.model.SyncTrigger
import com.ivy.sms.domain.usecase.ScanInboxUseCase
import com.ivy.sms.domain.usecase.SyncSmsUseCase
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

@Stable
@HiltViewModel
class WalletSmsConfigViewModel @Inject constructor(
    private val accountRepo: AccountRepository,
    private val senderRepo: SenderAccountLinkRepository,
    private val pendingRepo: PendingReviewItemRepository,
    private val watermarks: SmsWatermarkPreferences,
    private val syncSms: SyncSmsUseCase,
    private val scanInbox: ScanInboxUseCase,
    private val justLinked: JustLinkedSignal,
) : ComposeViewModel<WalletSmsConfigViewState, Unit>() {

    private var state by mutableStateOf(WalletSmsConfigViewState())
    private var walletId: AccountId? = null

    init {
        // Mirror the global scan progress flow into the screen state so the user
        // sees a live "X of Y messages processed" bar while a sync is running.
        viewModelScope.launch {
            scanInbox.progress.collectLatest { p ->
                state = state.copy(scanProgress = p)
            }
        }
    }

    @Composable
    override fun uiState(): WalletSmsConfigViewState = state

    override fun onEvent(event: Unit) = Unit

    fun load(walletId: AccountId) {
        Timber.d("WalletSmsConfig load(walletId=${walletId.value})")
        this.walletId = walletId
        Thread {
            try {
                Timber.d("WalletSmsConfig load(): raw thread on ${Thread.currentThread().name}")
                runBlocking {
                    val account = accountRepo.findById(walletId)
                    Timber.d("WalletSmsConfig load(): accountRepo returned $account")
                    if (account == null) return@runBlocking
                    val link = senderRepo.findByAccountId(walletId).getOrNull()?.firstOrNull()
                    Timber.d("WalletSmsConfig load(): link=$link")
                    val pending = pendingRepo.count().getOrNull() ?: 0
                    // One-shot: if the user just finished linking THIS wallet,
                    // pop the sync-period sheet on first composition so they
                    // don't have to dig for the Sync now button. Consumes the
                    // signal so refreshing the screen later doesn't re-pop.
                    val popSheet = justLinked.signal.value == walletId
                    if (popSheet) justLinked.consume()
                    state = state.copy(
                        walletName = account.name.value,
                        linkedSender = link?.senderId,
                        pendingCount = pending,
                        lastSyncStatus = formatLastSyncStatus(link?.watermark),
                        loaded = true,
                        autoOpenSyncSheet = state.autoOpenSyncSheet || popSheet,
                    )
                    Timber.d("WalletSmsConfig load(): state updated, linkedSender=${state.linkedSender}")
                }
            } catch (t: Throwable) {
                Timber.e(t, "WalletSmsConfig load() crashed")
                state = state.copy(error = "load crashed: ${t.message}")
            }
        }.start()
    }

    /**
     * Sync now from the wallet config screen. Takes [explicitWalletId] so the
     * VM doesn't have to rely on the field being set — the previous version
     * silently returned when `this.walletId` was null because of a VM-store
     * reset race, which manifested as "the period sheet closes and nothing
     * happens". The picker also calls this through [viewModelScope.launch]
     * (proven working in [PeriodPickerViewModel] on the same device) instead
     * of the raw `Thread { runBlocking }` dance which was causing the silent
     * failure.
     */
    fun syncNow(explicitWalletId: AccountId, lowerBoundEpochMillis: Long? = null) {
        Timber.d(
            "WalletSmsConfig syncNow(walletId=${explicitWalletId.value}, lowerBound=$lowerBoundEpochMillis)",
        )
        this.walletId = explicitWalletId
        state = state.copy(syncing = true, error = null, scanProgress = null)
        viewModelScope.launch {
            try {
                if (lowerBoundEpochMillis != null) {
                    Timber.d("WalletSmsConfig syncNow: writing lowerBound=$lowerBoundEpochMillis")
                    watermarks.writeLowerBound(lowerBoundEpochMillis)
                    // Reset the watermark too so the scan reads from lowerBound forward.
                    watermarks.write(0L)
                }
                Timber.d("WalletSmsConfig syncNow: invoking syncSms")
                val result = syncSms(SyncTrigger.MANUAL_MENU)
                Timber.d("WalletSmsConfig syncNow: syncSms returned $result")
                result.fold(
                    ifLeft = {
                        state = state.copy(
                            syncing = false,
                            error = it,
                            lastSyncStatus = "Last sync failed: $it",
                            scanProgress = null,
                        )
                    },
                    ifRight = { syncResult ->
                        val msg = when (syncResult) {
                            is SyncResult.Completed -> "Last sync: just now (${syncResult.transactionsCreated} new, ${syncResult.itemsQuarantined} to review)"
                            is SyncResult.PartiallyCompleted -> "Last sync: partial — ${syncResult.transactionsCreated} new, ${syncResult.itemsQuarantined} to review"
                            SyncResult.PermissionMissing -> "Last sync: permission missing"
                        }
                        state = state.copy(
                            syncing = false,
                            lastSyncStatus = msg,
                            scanProgress = null,
                        )
                    },
                )
                load(explicitWalletId)
            } catch (t: Throwable) {
                Timber.e(t, "WalletSmsConfig syncNow() crashed")
                state = state.copy(syncing = false, error = "sync crashed: ${t.message}")
            }
        }
    }

    /** Called by the screen once it has popped the sync-period sheet so
     *  follow-up state changes don't re-open it on every recomposition. */
    fun consumeAutoOpenSyncSheet() {
        if (state.autoOpenSyncSheet) state = state.copy(autoOpenSyncSheet = false)
    }

    fun unlink() {
        val id = walletId ?: return
        val link = state.linkedSender ?: return
        Thread {
            try {
                runBlocking {
                    senderRepo.delete(link)
                    // Clear the legacy global watermark so a re-link rescans.
                    watermarks.write(0L)
                }
                load(id)
            } catch (t: Throwable) {
                Timber.e(t, "WalletSmsConfig unlink() crashed")
                state = state.copy(error = "unlink crashed: ${t.message}")
            }
        }.start()
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
