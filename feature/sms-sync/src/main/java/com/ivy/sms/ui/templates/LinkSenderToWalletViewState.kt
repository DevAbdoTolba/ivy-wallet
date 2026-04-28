package com.ivy.sms.ui.templates

import androidx.compose.runtime.Immutable
import com.ivy.data.model.AccountId
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class WalletOption(
    val id: AccountId,
    val name: String,
)

@Immutable
data class LinkSenderToWalletViewState(
    val senderId: String = "",
    val wallets: ImmutableList<WalletOption> = persistentListOf(),
    val selected: AccountId? = null,
    val saving: Boolean = false,
    val error: String? = null,
    val saved: Boolean = false,
)

sealed interface LinkSenderToWalletEvent {
    data class Select(val accountId: AccountId) : LinkSenderToWalletEvent
    data object Save : LinkSenderToWalletEvent
    data object DeleteExistingLink : LinkSenderToWalletEvent
}
