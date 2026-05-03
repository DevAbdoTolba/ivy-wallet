package com.ivy.sms.ui.sender

import androidx.compose.runtime.Immutable
import com.ivy.data.model.AccountId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class SenderRowViewState(
    val senderId: String,
    val messageCount: Int,
)

@Immutable
data class SenderPickerViewState(
    val walletId: AccountId? = null,
    /** Every distinct sender on the device, ranked by descending msg count. */
    val allSenders: ImmutableList<SenderRowViewState> = persistentListOf(),
    /** How many of [allSenders] the screen currently shows. Bumped by 10 on
     *  every "Load more" tap, capped at allSenders.size. */
    val visibleCount: Int = 10,
    val typed: String = "",
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
    /** Set when the wallet already has a sender linked. The screen uses this to
     *  bounce the user out of the picker (no point re-linking). */
    val alreadyLinked: Boolean = false,
) {
    val visibleSenders: List<SenderRowViewState>
        get() = allSenders.take(visibleCount)

    val canLoadMore: Boolean
        get() = visibleCount < allSenders.size
}

sealed interface SenderPickerEvent {
    data class TypedChanged(val value: String) : SenderPickerEvent
    data class PickTop(val senderId: String) : SenderPickerEvent
    data object Submit : SenderPickerEvent
    data object LoadMore : SenderPickerEvent
    data object DismissError : SenderPickerEvent
}
