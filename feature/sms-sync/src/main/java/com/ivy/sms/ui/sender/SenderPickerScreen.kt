package com.ivy.sms.ui.sender

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ivy.data.model.AccountId
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.legacy.utils.selectEndTextFieldValue
import com.ivy.navigation.navigation
import com.ivy.sms.ui.permission.PermissionGate
import com.ivy.sms.ui.permission.PermissionState
import com.ivy.wallet.ui.theme.Red
import com.ivy.wallet.ui.theme.components.BackButtonType
import com.ivy.wallet.ui.theme.components.IvyBasicTextField
import com.ivy.wallet.ui.theme.components.IvyButton
import com.ivy.wallet.ui.theme.components.IvyOutlinedButton
import com.ivy.wallet.ui.theme.components.IvyToolbar

@Composable
fun SenderPickerScreen(
    walletId: AccountId,
    onSaved: () -> Unit,
    viewModel: SenderPickerViewModel = viewModel(),
) {
    PermissionGate(
        onStateChanged = { state ->
            if (state == PermissionState.Granted) {
                viewModel.load(walletId)
            }
        },
    ) {
        SenderPickerContent(walletId, onSaved, viewModel)
    }
}

@Composable
private fun SenderPickerContent(
    walletId: AccountId,
    onSaved: () -> Unit,
    viewModel: SenderPickerViewModel,
) {
    val state = viewModel.uiState()
    val nav = navigation()

    LaunchedEffect(walletId) { viewModel.load(walletId) }
    if (state.saved) {
        LaunchedEffect(Unit) { onSaved() }
    }

    var typed by remember(state.typed) {
        mutableStateOf(selectEndTextFieldValue(state.typed))
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
                Text(
                    text = "Link SMS chat",
                    style = UI.typo.h2.style(
                        color = UI.colors.pureInverse,
                        fontWeight = FontWeight.ExtraBold,
                    ),
                )
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
            ) {
                Spacer(Modifier.height(8.dp))

                // ── 1. Free-text input on top ──────────────────────────────
                Text(
                    text = "TYPE A SENDER ID",
                    style = UI.typo.c.style(
                        color = UI.colors.gray,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(Modifier.height(8.dp))
                com.ivy.sms.ui.components.TappableInputBox(
                    value = typed,
                    hint = "e.g. ChaseAlerts, VF-Cash",
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { viewModel.onEvent(SenderPickerEvent.Submit) },
                    ),
                    onValueChanged = {
                        typed = it
                        viewModel.onEvent(SenderPickerEvent.TypedChanged(it.text))
                    },
                )
                Spacer(Modifier.height(12.dp))
                IvyButton(
                    text = if (state.saving) "Linking…" else "Link this sender",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.saving && state.typed.isNotBlank(),
                    onClick = { viewModel.onEvent(SenderPickerEvent.Submit) },
                )

                state.error?.let { msg ->
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = msg,
                        style = UI.typo.c.style(
                            color = Red,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }

                Spacer(Modifier.height(28.dp))

                // ── 2. Suggestions from inbox ──────────────────────────────
                Text(
                    text = "OR PICK FROM YOUR INBOX",
                    style = UI.typo.c.style(
                        color = UI.colors.gray,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Spacer(Modifier.height(12.dp))

                if (state.allSenders.isEmpty() && state.error == null) {
                    EmptyHint(text = "No senders detected in your inbox yet.")
                } else {
                    val visible = state.visibleSenders
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(UI.shapes.r4)
                            .background(UI.colors.medium),
                    ) {
                        visible.forEachIndexed { idx, row ->
                            // Hairline divider between every batch of 10 — almost
                            // invisible, just enough to mark "page" boundaries.
                            if (idx > 0 && idx % 10 == 0) {
                                BatchDivider()
                            }
                            SenderRow(
                                senderId = row.senderId,
                                count = row.messageCount,
                                onClick = {
                                    viewModel.onEvent(SenderPickerEvent.PickTop(row.senderId))
                                },
                            )
                        }
                    }

                    if (state.canLoadMore) {
                        Spacer(Modifier.height(12.dp))
                        IvyOutlinedButton(
                            text = "Load 10 more",
                            iconStart = null,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = { viewModel.onEvent(SenderPickerEvent.LoadMore) },
                        )
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun BatchDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(UI.colors.pureInverse.copy(alpha = 0.06f)),
    )
}

@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(UI.shapes.r4)
            .background(UI.colors.medium)
            .padding(20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = UI.typo.b2.style(
                color = UI.colors.gray,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

@Composable
private fun SenderRow(senderId: String, count: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = senderId,
            style = UI.typo.b2.style(
                color = UI.colors.pureInverse,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "$count msg${if (count == 1) "" else "s"}",
            style = UI.typo.c.style(
                color = UI.colors.gray,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}
