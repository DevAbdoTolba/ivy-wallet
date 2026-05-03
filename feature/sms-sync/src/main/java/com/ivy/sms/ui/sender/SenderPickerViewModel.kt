package com.ivy.sms.ui.sender

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.model.AccountId
import com.ivy.sms.data.SmsInboxDataSource
import com.ivy.sms.domain.usecase.LinkSenderToWalletUseCase
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject

private const val INITIAL_VISIBLE = 10
private const val LOAD_MORE_STEP = 10

@Stable
@HiltViewModel
class SenderPickerViewModel @Inject constructor(
    private val inbox: SmsInboxDataSource,
    private val linkSender: LinkSenderToWalletUseCase,
    private val dispatchers: DispatchersProvider,
) : ComposeViewModel<SenderPickerViewState, SenderPickerEvent>() {

    private var state by mutableStateOf(SenderPickerViewState())

    /**
     * Custom scope used INSTEAD of viewModelScope. The user's logcat showed launches
     * dispatched onto viewModelScope (and even on Dispatchers.IO via that scope) never
     * executing their bodies on this device — likely a Compose / lifecycle interaction
     * bug. A standalone SupervisorJob+IO scope sidesteps the framework entirely.
     * Cancelled in [onCleared] so coroutines don't leak.
     */
    private val workScope = CoroutineScope(SupervisorJob() + dispatchers.io)

    init {
        Timber.d("SmsLink VM init — dispatchers=$dispatchers, workScope=$workScope")
        // Sanity ping: prove workScope can run a body at all. If this doesn't fire,
        // something fundamental about the scope/dispatcher is broken.
        workScope.launch {
            Timber.d("SmsLink VM init: workScope ping ran on ${Thread.currentThread().name}")
        }
    }

    override fun onCleared() {
        workScope.cancel()
        super.onCleared()
    }

    @Composable
    override fun uiState(): SenderPickerViewState = state

    fun load(walletId: AccountId) {
        Timber.d("SmsLink load(walletId=${walletId.value}) allSenders=${state.allSenders.size}")
        if (state.walletId == walletId && state.allSenders.isNotEmpty()) {
            Timber.d("SmsLink load(): already loaded, skipping")
            return
        }
        state = state.copy(walletId = walletId)
        Timber.d("SmsLink load(): spawning raw thread")
        Thread {
            Timber.d("SmsLink load(): raw thread running on ${Thread.currentThread().name}")
            try {
                kotlinx.coroutines.runBlocking {
                    inbox.listSenders().fold(
                        ifLeft = {
                            Timber.w("SmsLink load(): listSenders Left=$it")
                            state = state.copy(error = it)
                        },
                        ifRight = { senders ->
                            Timber.d("SmsLink load(): got ${senders.size} senders")
                            state = state.copy(
                                allSenders = senders
                                    .map { SenderRowViewState(it.senderId, it.messageCount) }
                                    .toImmutableList(),
                                visibleCount = INITIAL_VISIBLE,
                            )
                        },
                    )
                }
            } catch (t: Throwable) {
                Timber.e(t, "SmsLink load(): raw thread crashed")
                state = state.copy(error = "load crashed: ${t.message}")
            }
        }.start()
    }

    override fun onEvent(event: SenderPickerEvent) {
        when (event) {
            is SenderPickerEvent.TypedChanged ->
                state = state.copy(typed = event.value, error = null)
            is SenderPickerEvent.PickTop -> submit(event.senderId)
            SenderPickerEvent.Submit -> submit(state.typed.trim())
            SenderPickerEvent.LoadMore -> {
                state = state.copy(
                    visibleCount = (state.visibleCount + LOAD_MORE_STEP)
                        .coerceAtMost(state.allSenders.size),
                )
            }
            SenderPickerEvent.DismissError -> state = state.copy(error = null)
        }
    }

    private fun submit(senderId: String) {
        Timber.d("SmsLink submit(senderId=$senderId)")
        if (senderId.isBlank()) {
            state = state.copy(error = "Pick or type a sender ID")
            return
        }
        val walletId = state.walletId ?: run {
            Timber.w("SmsLink submit() walletId is null — load() never completed")
            state = state.copy(error = "Wallet not loaded yet — try again")
            return
        }
        Timber.d("SmsLink submit() entering coroutine, walletId=${walletId.value}")
        state = state.copy(saving = true, error = null)
        // Same raw-thread escape hatch as load() — see comment above.
        Timber.d("SmsLink submit(): spawning raw thread")
        Thread {
            Timber.d("SmsLink submit(): raw thread running on ${Thread.currentThread().name}")
            try {
                kotlinx.coroutines.runBlocking {
                    val result = withTimeoutOrNull(10_000) {
                        Timber.d("SmsLink submit() calling linkSender")
                        val r = linkSender(senderId, walletId)
                        Timber.d("SmsLink submit() linkSender returned: $r")
                        r
                    }
                    Timber.d("SmsLink submit() timeout result: $result")
                    if (result == null) {
                        state = state.copy(
                            saving = false,
                            error = "Save timed out — wait a moment for the launch scan to finish, then retry.",
                        )
                        return@runBlocking
                    }
                    result.fold(
                        { state = state.copy(saving = false, error = it) },
                        { state = state.copy(saving = false, saved = true) },
                    )
                }
            } catch (t: Throwable) {
                Timber.e(t, "SmsLink submit(): raw thread crashed")
                state = state.copy(saving = false, error = "submit crashed: ${t.message}")
            }
        }.start()
    }
}
