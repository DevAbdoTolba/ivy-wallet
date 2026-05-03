package com.ivy.sms.ui.templates

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.navigation.navigation
import com.ivy.wallet.ui.theme.Green
import com.ivy.wallet.ui.theme.Red
import com.ivy.wallet.ui.theme.components.BackButtonType
import com.ivy.wallet.ui.theme.components.IvyButton
import com.ivy.wallet.ui.theme.components.IvyOutlinedButton
import com.ivy.wallet.ui.theme.components.IvyToolbar

@Composable
fun LinkSenderToWalletScreen(
    senderId: String,
    onSaved: () -> Unit,
    viewModel: LinkSenderToWalletViewModel = viewModel(),
) {
    val state = viewModel.uiState()
    val nav = navigation()

    LaunchedEffect(senderId) { viewModel.load(senderId) }
    if (state.saved) {
        LaunchedEffect(Unit) { onSaved() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(UI.colors.pure)
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            IvyToolbar(
                onBack = { nav.back() },
                backButtonType = BackButtonType.BACK,
            ) {
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "Link wallet",
                        style = UI.typo.h2.style(
                            color = UI.colors.pureInverse,
                            fontWeight = FontWeight.ExtraBold,
                        ),
                    )
                    Text(
                        text = state.senderId,
                        style = UI.typo.c.style(
                            color = UI.colors.gray,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            state.error?.let { error ->
                Box(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .fillMaxWidth()
                        .clip(UI.shapes.r4)
                        .background(Red.copy(alpha = 0.16f))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = error,
                        style = UI.typo.c.style(
                            color = Red,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
                if (error.startsWith("LINK_CONFLICT")) {
                    Spacer(Modifier.height(8.dp))
                    IvyOutlinedButton(
                        text = "Remove existing link first",
                        iconStart = null,
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .fillMaxWidth(),
                        onClick = {
                            viewModel.onEvent(LinkSenderToWalletEvent.DeleteExistingLink)
                        },
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .navigationBarsPadding(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.wallets, key = { it.id.value.toString() }) { wallet ->
                    val selected = state.selected == wallet.id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(UI.shapes.r4)
                            .background(
                                if (selected) Green.copy(alpha = 0.18f) else UI.colors.medium,
                            )
                            .clickable {
                                viewModel.onEvent(LinkSenderToWalletEvent.Select(wallet.id))
                            }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(UI.shapes.rFull)
                                .background(if (selected) Green else UI.colors.pure),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(UI.shapes.rFull)
                                        .background(Color.White),
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Text(
                            text = wallet.name,
                            style = UI.typo.b2.style(
                                color = if (selected) Green else UI.colors.pureInverse,
                                fontWeight = FontWeight.ExtraBold,
                            ),
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .navigationBarsPadding()
                    .padding(16.dp),
            ) {
                IvyButton(
                    text = if (state.saving) "Saving…" else "Link wallet",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.selected != null && !state.saving,
                    onClick = { viewModel.onEvent(LinkSenderToWalletEvent.Save) },
                )
            }
        }
    }
}
