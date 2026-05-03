package com.ivy.sms.domain

import com.ivy.data.model.AccountId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot signal raised by [com.ivy.sms.ui.sender.SenderPickerViewModel] when
 * the user successfully links a sender to a wallet. The wallet config screen
 * reads + clears this on first composition so it can immediately pop the sync
 * period sheet — keeping the user "in the flow" instead of dropping them on
 * a quiet config screen and making them tap "Sync now" themselves.
 */
@Singleton
class JustLinkedSignal @Inject constructor() {
    private val _signal = MutableStateFlow<AccountId?>(null)
    val signal: StateFlow<AccountId?> = _signal.asStateFlow()

    fun emit(walletId: AccountId) {
        _signal.value = walletId
    }

    /** Read-and-clear: returns the pending walletId, then resets to null. */
    fun consume(): AccountId? {
        val v = _signal.value
        _signal.value = null
        return v
    }
}
