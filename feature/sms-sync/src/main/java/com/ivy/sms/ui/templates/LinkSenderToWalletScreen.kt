package com.ivy.sms.ui.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun LinkSenderToWalletScreen(
    senderId: String,
    onSaved: () -> Unit,
    viewModel: LinkSenderToWalletViewModel = viewModel(),
) {
    val state = viewModel.uiState()

    LaunchedEffect(senderId) { viewModel.load(senderId) }
    if (state.saved) {
        LaunchedEffect(Unit) { onSaved() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "Link sender '${state.senderId}' to a wallet")

        state.error?.let { error ->
            Text(text = error, color = Color(0xFFB00020))
            if (error.startsWith("LINK_CONFLICT")) {
                TextButton(onClick = { viewModel.onEvent(LinkSenderToWalletEvent.DeleteExistingLink) }) {
                    Text("Remove existing link first")
                }
            }
        }

        state.wallets.forEach { wallet ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = state.selected == wallet.id,
                        onClick = { viewModel.onEvent(LinkSenderToWalletEvent.Select(wallet.id)) },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.selected == wallet.id,
                    onClick = { viewModel.onEvent(LinkSenderToWalletEvent.Select(wallet.id)) },
                )
                Spacer(Modifier.height(0.dp))
                Text(text = wallet.name)
            }
        }

        Button(
            onClick = { viewModel.onEvent(LinkSenderToWalletEvent.Save) },
            enabled = state.selected != null && !state.saving,
        ) {
            Text(if (state.saving) "Saving…" else "Link wallet")
        }
    }
}
