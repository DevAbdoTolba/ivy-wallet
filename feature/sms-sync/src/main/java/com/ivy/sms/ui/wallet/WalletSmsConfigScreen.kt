package com.ivy.sms.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ivy.data.model.AccountId
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.legacy.utils.selectEndTextFieldValue
import com.ivy.navigation.PendingReviewScreen
import com.ivy.navigation.SmsExtractionScreen
import com.ivy.navigation.WalletSmsLinkScreen
import com.ivy.navigation.navigation
import com.ivy.sms.ui.permission.PermissionGate
import com.ivy.wallet.ui.theme.GradientRed
import com.ivy.wallet.ui.theme.Red
import com.ivy.wallet.ui.theme.components.BackButtonType
import com.ivy.wallet.ui.theme.components.IvyBasicTextField
import com.ivy.wallet.ui.theme.components.IvyButton
import com.ivy.wallet.ui.theme.components.IvyOutlinedButton
import com.ivy.wallet.ui.theme.components.IvyToolbar
import com.ivy.wallet.ui.theme.modal.IvyModal
import com.ivy.wallet.ui.theme.modal.ModalTitle
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Composable
fun WalletSmsConfigScreen(
    walletId: AccountId,
    viewModel: WalletSmsConfigViewModel = viewModel(),
) {
    PermissionGate {
        WalletSmsConfigContent(walletId, viewModel)
    }
}

@Composable
private fun WalletSmsConfigContent(
    walletId: AccountId,
    viewModel: WalletSmsConfigViewModel,
) {
    val state = viewModel.uiState()
    val nav = navigation()

    LaunchedEffect(walletId) { viewModel.load(walletId) }

    // Refresh on every screen-resume so coming back from the sender picker
    // shows the freshly-linked sender. The single LaunchedEffect above only
    // runs when walletId changes, which it doesn't on a back-navigation pop.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, walletId) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.load(walletId)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    var showPeriodPicker by remember { mutableStateOf(false) }
    var showUnlinkConfirm by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(UI.colors.pure),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
        ) {
            IvyToolbar(
                onBack = { nav.back() },
                backButtonType = BackButtonType.CLOSE,
            ) {
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(
                        text = "SMS sync",
                        style = UI.typo.h2.style(
                            color = UI.colors.pureInverse,
                            fontWeight = FontWeight.ExtraBold,
                        ),
                    )
                    Text(
                        text = state.walletName.ifBlank { "this wallet" },
                        style = UI.typo.c.style(
                            color = UI.colors.gray,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (state.linkedSender == null) {
                    EmptyLinkCard(
                        onLink = {
                            nav.navigateTo(WalletSmsLinkScreen(walletId.value.toString()))
                        },
                    )
                } else {
                    LinkedSenderCard(
                        state = state,
                        onSyncNow = { showPeriodPicker = true },
                    )

                    if (state.syncing) {
                        SyncProgressCard(state = state)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        IvyOutlinedButton(
                            text = "Templates",
                            iconStart = null,
                            modifier = Modifier.weight(1f),
                            onClick = { nav.navigateTo(SmsExtractionScreen) },
                        )
                        IvyOutlinedButton(
                            text = if (state.pendingCount > 0) {
                                "Review (${state.pendingCount})"
                            } else {
                                "Review"
                            },
                            iconStart = null,
                            modifier = Modifier.weight(1f),
                            onClick = { nav.navigateTo(PendingReviewScreen) },
                        )
                    }

                    Spacer(Modifier.height(4.dp))

                    IvyOutlinedButton(
                        text = "Unlink sender",
                        iconStart = null,
                        borderColor = Red,
                        textColor = Red,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { showUnlinkConfirm = true },
                    )
                }

                state.error?.let { msg ->
                    Text(
                        text = msg,
                        style = UI.typo.c.style(
                            color = Red,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }

                Spacer(Modifier.height(16.dp))
            }
        }

        // BoxScope-level overlays — IvyModal needs to live here, not at the
        // top of the @Composable function, because its receiver is BoxScope.
        if (showPeriodPicker) {
            SyncPeriodSheet(
                onDismiss = { showPeriodPicker = false },
                onPick = { lowerBoundMs ->
                    showPeriodPicker = false
                    viewModel.syncNow(lowerBoundMs)
                },
            )
        }

        UnlinkConfirmModal(
            visible = showUnlinkConfirm && state.linkedSender != null,
            walletName = state.walletName.ifBlank { "this wallet" },
            senderId = state.linkedSender ?: "",
            dismiss = { showUnlinkConfirm = false },
            onConfirm = {
                showUnlinkConfirm = false
                viewModel.unlink()
            },
        )
    }
}

@Composable
private fun EmptyLinkCard(onLink: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(UI.shapes.r4)
            .background(UI.colors.medium)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "No SMS chat is linked yet",
            style = UI.typo.b1.style(
                color = UI.colors.pureInverse,
                fontWeight = FontWeight.ExtraBold,
            ),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Pick a sender from your inbox so Ivy can turn its messages into transactions for this wallet.",
            style = UI.typo.b2.style(
                color = UI.colors.gray,
                fontWeight = FontWeight.Medium,
            ),
        )
        Spacer(Modifier.height(20.dp))
        IvyButton(
            text = "Link SMS chat",
            modifier = Modifier.fillMaxWidth(),
            onClick = onLink,
        )
    }
}

@Composable
private fun LinkedSenderCard(
    state: WalletSmsConfigViewState,
    onSyncNow: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(UI.shapes.r4)
            .background(UI.colors.medium)
            .padding(20.dp),
    ) {
        Text(
            text = "LINKED SENDER",
            style = UI.typo.c.style(
                color = UI.colors.gray,
                fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.linkedSender.orEmpty(),
            style = UI.typo.h2.style(
                color = UI.colors.pureInverse,
                fontWeight = FontWeight.ExtraBold,
            ),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = state.lastSyncStatus,
            style = UI.typo.c.style(
                color = UI.colors.gray,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        Spacer(Modifier.height(20.dp))
        IvyButton(
            text = if (state.syncing) "Syncing…" else "Sync now",
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.syncing,
            onClick = onSyncNow,
        )
    }
}

private const val SYNC_PROGRESS_THRESHOLD = 100

@Composable
private fun SyncProgressCard(state: WalletSmsConfigViewState) {
    val p = state.scanProgress
    val total = p?.total ?: 0
    val showRealBar = total >= SYNC_PROGRESS_THRESHOLD

    val rawRatio = if (p != null && total > 0) {
        p.processed.toFloat() / total.toFloat()
    } else {
        0f
    }
    val quantized = (rawRatio * 20f).toInt() / 20f
    val animatedRatio by androidx.compose.animation.core.animateFloatAsState(
        targetValue = quantized.coerceIn(0f, 1f),
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 600,
            easing = androidx.compose.animation.core.LinearEasing,
        ),
        label = "syncProgressBar",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(UI.shapes.r4)
            .background(UI.colors.medium)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (p == null) "Preparing…" else "Syncing…",
                style = UI.typo.b1.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.ExtraBold,
                ),
            )
            Spacer(Modifier.weight(1f))
            if (showRealBar && p != null) {
                Text(
                    text = "${p.processed} / ${p.total}",
                    style = UI.typo.c.style(
                        color = UI.colors.gray,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            } else if (p != null) {
                Text(
                    text = "${p.total} SMS",
                    style = UI.typo.c.style(
                        color = UI.colors.gray,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (showRealBar) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(UI.shapes.rFull)
                    .background(UI.colors.pure),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction = animatedRatio)
                        .height(6.dp)
                        .clip(UI.shapes.rFull)
                        .background(com.ivy.wallet.ui.theme.Green),
                )
            }
        } else {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(UI.shapes.rFull),
                color = com.ivy.wallet.ui.theme.Green,
                trackColor = UI.colors.pure,
            )
        }
        if (p != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = buildString {
                    append("${p.transactionsCreated} new")
                    if (p.itemsQuarantined > 0) append(" · ${p.itemsQuarantined} to review")
                    if (p.newTemplatesDiscovered > 0) append(" · ${p.newTemplatesDiscovered} new templates")
                },
                style = UI.typo.c.style(
                    color = UI.colors.gray,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }
}

@Composable
private fun SyncPeriodSheet(
    onDismiss: () -> Unit,
    onPick: (Long) -> Unit,
) {
    val now = Instant.now()
    val options = listOf(
        "Last week" to now.minus(7, ChronoUnit.DAYS).toEpochMilli(),
        "Last month" to now.minus(30, ChronoUnit.DAYS).toEpochMilli(),
        "Last 3 months" to now.minus(90, ChronoUnit.DAYS).toEpochMilli(),
        "Last year" to now.minus(365, ChronoUnit.DAYS).toEpochMilli(),
        "All time" to 0L,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .clip(UI.shapes.r2Top)
                .background(UI.colors.pure)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 24.dp)
                .clickable(enabled = false) { },
        ) {
            Text(
                text = "Sync how far back?",
                style = UI.typo.h2.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.ExtraBold,
                ),
            )
            Spacer(Modifier.height(16.dp))
            options.forEach { (label, lowerBound) ->
                PeriodOption(label = label, onClick = { onPick(lowerBound) })
                Spacer(Modifier.height(8.dp))
            }
            Spacer(Modifier.height(8.dp))
            IvyOutlinedButton(
                text = "Cancel",
                iconStart = null,
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun PeriodOption(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(UI.shapes.r4)
            .background(UI.colors.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text(
            text = label,
            style = UI.typo.b2.style(
                color = UI.colors.pureInverse,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

/**
 * Unlink confirmation drawer — uses the project's standard `IvyModal` so it
 * inherits the same slide-in animation, scrim, keyboard offset, and back-button
 * dismiss the rest of the app uses (account/category modals, etc). The user
 * has to type the wallet name to confirm; the chip directly above the field
 * shows the name so they don't have to remember it.
 */
@Composable
private fun BoxScope.UnlinkConfirmModal(
    visible: Boolean,
    walletName: String,
    senderId: String,
    dismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val modalId = remember(senderId, visible) { UUID.randomUUID() }
    var typed by remember(modalId) {
        mutableStateOf(selectEndTextFieldValue(""))
    }
    val matches = typed.text.trim().equals(walletName, ignoreCase = true)

    IvyModal(
        id = modalId,
        visible = visible,
        dismiss = dismiss,
        PrimaryAction = {
            IvyButton(
                text = "Unlink",
                backgroundGradient = GradientRed,
                hasGlow = matches,
                enabled = matches,
                onClick = onConfirm,
            )
        },
    ) {
        Spacer(Modifier.height(32.dp))

        ModalTitle(text = "Unlink $senderId?")

        Spacer(Modifier.height(16.dp))

        Text(
            modifier = Modifier.padding(horizontal = 32.dp),
            text = "This wallet will stop importing SMS from this sender. Existing transactions are kept.",
            style = UI.typo.b2.style(
                color = UI.colors.gray,
                fontWeight = FontWeight.Medium,
            ),
        )

        Spacer(Modifier.height(20.dp))

        Text(
            modifier = Modifier.padding(horizontal = 32.dp),
            text = "Type this wallet's name to confirm:",
            style = UI.typo.c.style(
                color = UI.colors.gray,
                fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(Modifier.height(8.dp))

        // Wallet-name hint chip — read-only, shown so the user doesn't have to
        // remember the name from the previous screen.
        Box(
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .clip(UI.shapes.rFull)
                .background(Red.copy(alpha = 0.14f))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(
                text = walletName,
                style = UI.typo.c.style(
                    color = Red,
                    fontWeight = FontWeight.ExtraBold,
                ),
            )
        }

        Spacer(Modifier.height(12.dp))

        com.ivy.sms.ui.components.TappableInputBox(
            modifier = Modifier.padding(horizontal = 32.dp),
            value = typed,
            hint = walletName,
            onValueChanged = { typed = it },
        )

        Spacer(Modifier.height(24.dp))
    }
}

