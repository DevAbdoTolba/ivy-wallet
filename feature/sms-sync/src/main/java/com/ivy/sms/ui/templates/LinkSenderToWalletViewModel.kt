package com.ivy.sms.ui.templates

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.data.model.AccountId
import com.ivy.data.repository.AccountRepository
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.domain.usecase.LinkSenderToWalletUseCase
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import javax.inject.Inject

@Stable
@HiltViewModel
class LinkSenderToWalletViewModel @Inject constructor(
    private val accountRepository: AccountRepository,
    private val senderRepo: SenderAccountLinkRepository,
    private val linkSender: LinkSenderToWalletUseCase,
) : ComposeViewModel<LinkSenderToWalletViewState, LinkSenderToWalletEvent>() {

    private var state by mutableStateOf(LinkSenderToWalletViewState())

    @Composable
    override fun uiState(): LinkSenderToWalletViewState = state

    fun load(senderId: String) {
        viewModelScope.launch {
            val accounts = accountRepository.findAll()
            state = state.copy(
                senderId = senderId,
                wallets = accounts.map { WalletOption(it.id, it.name.value) }.toImmutableList(),
            )
        }
    }

    override fun onEvent(event: LinkSenderToWalletEvent) {
        when (event) {
            is LinkSenderToWalletEvent.Select -> state = state.copy(selected = event.accountId)
            LinkSenderToWalletEvent.Save -> save()
            LinkSenderToWalletEvent.DeleteExistingLink -> deleteExisting()
        }
    }

    private fun save() {
        val selected = state.selected ?: run {
            state = state.copy(error = "Pick a wallet")
            return
        }
        state = state.copy(saving = true, error = null)
        viewModelScope.launch {
            linkSender(state.senderId, selected).fold(
                { state = state.copy(saving = false, error = it) },
                { state = state.copy(saving = false, saved = true) },
            )
        }
    }

    private fun deleteExisting() {
        viewModelScope.launch {
            senderRepo.delete(state.senderId)
            state = state.copy(error = null)
        }
    }
}
