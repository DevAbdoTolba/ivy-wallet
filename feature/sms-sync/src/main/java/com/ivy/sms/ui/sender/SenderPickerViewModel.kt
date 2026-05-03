package com.ivy.sms.ui.sender

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.model.AccountId
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.data.SmsInboxDataSource
import com.ivy.sms.domain.JustLinkedSignal
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
    private val senderRepo: SenderAccountLinkRepository,
    private val justLinked: JustLinkedSignal,
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
                    // Bail early if the wallet is already linked. The picker
                    // would just produce a UNIQUE-constraint error and the user
                    // hits a confusing dead-end. The screen redirects to the
                    // config view on seeing alreadyLinked.
                    val existing = senderRepo.findByAccountId(walletId).getOrNull().orEmpty()
                    if (existing.isNotEmpty()) {
                        Timber.d("SmsLink load(): wallet already linked to ${existing.first().senderId}, redirecting")
                        state = state.copy(alreadyLinked = true)
                        return@runBlocking
                    }
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
            state = state.copy(error = "Type the sender's exact name as it appears in your inbox.")
            return
        }
        val walletId = state.walletId ?: run {
            Timber.w("SmsLink submit() walletId is null — load() never completed")
            state = state.copy(error = "Wallet hasn't loaded yet — try again in a moment.")
            return
        }
        // Validate against the inbox snapshot we already loaded. Free-text
        // sender names that don't actually exist in the inbox would silently
        // link to nothing and the wallet would look "linked" but never sync.
        val matchedFromInbox = state.allSenders.firstOrNull {
            it.senderId.equals(senderId, ignoreCase = true)
        }
        if (matchedFromInbox == null) {
            state = state.copy(
                error = "No SMS from \"$senderId\" in your inbox. Pick one of the suggestions above or check the spelling.",
            )
            return
        }
        // Use the canonical case from the inbox so case mismatches don't
        // produce two separate links for the same real sender.
        val resolvedSenderId = matchedFromInbox.senderId
        Timber.d("SmsLink submit() entering coroutine, walletId=${walletId.value}")
        state = state.copy(saving = true, error = null)
        // Same raw-thread escape hatch as load() — see comment above.
        Timber.d("SmsLink submit(): spawning raw thread")
        Thread {
            Timber.d("SmsLink submit(): raw thread running on ${Thread.currentThread().name}")
            try {
                kotlinx.coroutines.runBlocking {
                    val result = withTimeoutOrNull(10_000) {
                        Timber.d("SmsLink submit() calling linkSender(\"$resolvedSenderId\")")
                        val r = linkSender(resolvedSenderId, walletId)
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
                        { rawError ->
                            state = state.copy(
                                saving = false,
                                error = humanizeLinkError(rawError, resolvedSenderId),
                            )
                        },
                        {
                            // Tell the wallet config screen to auto-pop its
                            // sync-period sheet on first arrival post-link so
                            // the user gets carried straight into syncing.
                            justLinked.emit(walletId)
                            state = state.copy(saving = false, saved = true)
                        },
                    )
                }
            } catch (t: Throwable) {
                Timber.e(t, "SmsLink submit(): raw thread crashed")
                state = state.copy(saving = false, error = "submit crashed: ${t.message}")
            }
        }.start()
    }
}

/**
 * Maps the developer-facing error strings the repo returns into a sentence the
 * user can understand and act on.
 */
private fun humanizeLinkError(raw: String, senderId: String): String {
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
