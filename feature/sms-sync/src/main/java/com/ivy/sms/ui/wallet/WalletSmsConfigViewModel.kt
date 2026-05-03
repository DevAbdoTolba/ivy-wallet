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
                    state = state.copy(
                        walletName = account.name.value,
                        linkedSender = link?.senderId,
                        pendingCount = pending,
                        lastSyncStatus = formatLastSyncStatus(link?.watermark),
                    )
                    Timber.d("WalletSmsConfig load(): state updated, linkedSender=${state.linkedSender}")
                }
            } catch (t: Throwable) {
                Timber.e(t, "WalletSmsConfig load() crashed")
                state = state.copy(error = "load crashed: ${t.message}")
            }
        }.start()
    }

    fun syncNow(lowerBoundEpochMillis: Long? = null) {
        val id = walletId ?: return
        state = state.copy(syncing = true, error = null, scanProgress = null)
        Thread {
            try {
                runBlocking {
                    if (lowerBoundEpochMillis != null) {
                        watermarks.writeLowerBound(lowerBoundEpochMillis)
                        // Reset the watermark too so the scan reads from lowerBound forward.
                        watermarks.write(0L)
                    }
                    syncSms(SyncTrigger.MANUAL_MENU).fold(
                        ifLeft = {
                            state = state.copy(
                                syncing = false,
                                error = it,
                                lastSyncStatus = "Last sync failed: $it",
                                scanProgress = null,
                            )
                        },
                        ifRight = { result ->
                            val msg = when (result) {
                                is SyncResult.Completed -> "Last sync: just now (${result.transactionsCreated} new, ${result.itemsQuarantined} to review)"
                                is SyncResult.PartiallyCompleted -> "Last sync: partial — ${result.transactionsCreated} new, ${result.itemsQuarantined} to review"
                                SyncResult.PermissionMissing -> "Last sync: permission missing"
                            }
                            state = state.copy(
                                syncing = false,
                                lastSyncStatus = msg,
                                scanProgress = null,
                            )
                        },
                    )
                }
                load(id)
            } catch (t: Throwable) {
                Timber.e(t, "WalletSmsConfig syncNow() crashed")
                state = state.copy(syncing = false, error = "sync crashed: ${t.message}")
            }
        }.start()
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
