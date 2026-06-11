package com.ivy.sms.ui.wallet

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ivy.data.model.AccountId
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.legacy.utils.selectEndTextFieldValue
import com.ivy.navigation.SmsExtractionScreen
import com.ivy.navigation.WalletPendingReviewScreen
import com.ivy.navigation.navigation
import com.ivy.sms.ui.components.TappableInputBox
import com.ivy.sms.ui.permission.PermissionState
import com.ivy.sms.ui.permission.rememberSmsPermission
import com.ivy.wallet.ui.theme.GradientRed
import com.ivy.wallet.ui.theme.Green
import com.ivy.wallet.ui.theme.Ivy
import com.ivy.wallet.ui.theme.Orange
import com.ivy.wallet.ui.theme.Red
import com.ivy.wallet.ui.theme.components.IvyButton
import com.ivy.wallet.ui.theme.components.IvyCheckboxWithText
import com.ivy.wallet.ui.theme.components.IvyOutlinedButton
import com.ivy.wallet.ui.theme.modal.IvyModal
import com.ivy.wallet.ui.theme.modal.ModalTitle
import java.util.UUID

private const val SENDER_LIST_MAX_VISIBLE = 30

/**
 * One-sheet SMS setup (P1-4 redesign): sender + period + auto-route in a
 * single IvyModal with one "Save & Sync now" CTA. Hosted by the wallet edit
 * call sites (MainScreen / TransactionsScreen) right next to AccountModal —
 * temp/legacy-code can't depend on this module, so the hosts render it.
 *
 * Manage mode (wallet already linked): locked sender row + last-sync caption,
 * incremental "Sync now" as the primary action, "Scan further back…",
 * Templates / Review shortcuts, and a one-tap destructive unlink confirm.
 */
@Composable
fun BoxScope.SmsSetupSheet(
    visible: Boolean,
    walletId: UUID?,
    walletName: String,
    dismiss: () -> Unit,
) {
    // Latch the last opened wallet: the host clears its state on dismiss,
    // and without this the sheet would unmount mid exit-animation. Also
    // skips ViewModel creation entirely until the sheet is first opened.
    var latchedWalletId by remember { mutableStateOf(walletId) }
    var latchedWalletName by remember { mutableStateOf(walletName) }
    if (walletId != null) {
        latchedWalletId = walletId
        latchedWalletName = walletName
    }
    val activeWalletId = latchedWalletId ?: return

    val viewModel: SmsSetupViewModel = viewModel()
    val state = viewModel.uiState()
    val nav = navigation()
    val modalId = remember(activeWalletId) { UUID.randomUUID() }

    val permission = rememberSmsPermission()
    // Fire the system READ_SMS dialog inline, once per sheet-open.
    var autoAsked by remember(visible) { mutableStateOf(false) }
    LaunchedEffect(visible, permission.state) {
        if (visible && permission.state != PermissionState.Granted && !autoAsked) {
            autoAsked = true
            permission.request()
        }
    }

    LaunchedEffect(visible, activeWalletId, permission.state) {
        if (visible && permission.state == PermissionState.Granted) {
            viewModel.load(AccountId(activeWalletId))
        }
    }

    // Save & Sync done → carry the user straight to where the messages land.
    LaunchedEffect(state.saved) {
        if (state.saved) {
            viewModel.consumeSaved()
            dismiss()
            nav.navigateTo(WalletPendingReviewScreen(activeWalletId.toString()))
        }
    }

    var showUnlinkConfirm by remember(visible) { mutableStateOf(false) }

    val manageMode = state.linkedSender != null
    val displayWalletName = state.walletName.ifBlank { latchedWalletName }

    IvyModal(
        id = modalId,
        visible = visible,
        dismiss = dismiss,
        PrimaryAction = {
            if (manageMode) {
                IvyButton(
                    text = if (state.syncing) "Syncing…" else "Sync now",
                    enabled = !state.syncing,
                    onClick = { viewModel.onEvent(SmsSetupEvent.SyncNow) },
                )
            } else {
                IvyButton(
                    text = if (state.saving) "Saving…" else "Save & Sync now",
                    enabled = !state.saving &&
                        state.selectedSender != null &&
                        permission.state == PermissionState.Granted,
                    onClick = { viewModel.onEvent(SmsSetupEvent.SaveAndSync) },
                )
            }
        },
    ) {
        Spacer(Modifier.height(32.dp))

        ModalTitle(text = "SMS sync")
        Spacer(Modifier.height(4.dp))
        Text(
            modifier = Modifier.padding(horizontal = 32.dp),
            text = displayWalletName.ifBlank { "this wallet" },
            style = UI.typo.c.style(
                color = UI.colors.gray,
                fontWeight = FontWeight.SemiBold,
            ),
        )

        Spacer(Modifier.height(24.dp))

        if (permission.state != PermissionState.Granted) {
            PermissionInlineCard(onGrant = permission.request)
            Spacer(Modifier.height(16.dp))
        }

        if (manageMode) {
            ManageContent(
                state = state,
                onAutoRouteChange = { viewModel.onEvent(SmsSetupEvent.SetAutoRoute(it)) },
                onScanFurtherBack = { viewModel.onEvent(SmsSetupEvent.ScanFurtherBack(it)) },
                onOpenTemplates = {
                    dismiss()
                    nav.navigateTo(SmsExtractionScreen(activeWalletId.toString()))
                },
                onOpenReview = {
                    dismiss()
                    nav.navigateTo(WalletPendingReviewScreen(activeWalletId.toString()))
                },
                onUnlink = { showUnlinkConfirm = true },
            )
        } else {
            SetupContent(
                state = state,
                permissionGranted = permission.state == PermissionState.Granted,
                onSelectSender = { viewModel.onEvent(SmsSetupEvent.SelectSender(it)) },
                onSelectPeriod = { viewModel.onEvent(SmsSetupEvent.SelectPeriod(it)) },
                onAutoRouteChange = { viewModel.onEvent(SmsSetupEvent.SetAutoRoute(it)) },
            )
        }

        state.error?.let { msg ->
            Spacer(Modifier.height(12.dp))
            Text(
                modifier = Modifier.padding(horizontal = 24.dp),
                text = msg,
                style = UI.typo.c.style(
                    color = Red,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }

        Spacer(Modifier.height(24.dp))
    }

    UnlinkConfirmModal(
        visible = showUnlinkConfirm && state.linkedSender != null,
        senderId = state.linkedSender.orEmpty(),
        dismiss = { showUnlinkConfirm = false },
        onConfirm = {
            showUnlinkConfirm = false
            viewModel.onEvent(SmsSetupEvent.Unlink)
        },
    )
}

@Composable
private fun PermissionInlineCard(onGrant: () -> Unit) {
    Column(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth()
            .clip(UI.shapes.r4)
            .background(Orange.copy(alpha = 0.18f))
            .padding(16.dp),
    ) {
        Text(
            text = "Ivy needs SMS access to read this sender's messages.",
            style = UI.typo.b2.style(
                color = UI.colors.pureInverse,
                fontWeight = FontWeight.Medium,
            ),
        )
        Spacer(Modifier.height(12.dp))
        IvyButton(
            text = "Allow SMS access",
            modifier = Modifier.fillMaxWidth(),
            onClick = onGrant,
        )
    }
}

@Composable
private fun SetupContent(
    state: SmsSetupViewState,
    permissionGranted: Boolean,
    onSelectSender: (String) -> Unit,
    onSelectPeriod: (PeriodChipOption) -> Unit,
    onAutoRouteChange: (Boolean) -> Unit,
) {
    var senderListExpanded by remember { mutableStateOf(false) }

    SectionLabel(text = "SENDER")
    Spacer(Modifier.height(8.dp))

    val selected = state.selectedSender
    Row(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth()
            .clip(UI.shapes.r4)
            .background(UI.colors.medium)
            .clickable { senderListExpanded = !senderListExpanded }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                selected != null -> selected
                !permissionGranted -> "Allow SMS access to pick a sender"
                !state.loaded -> "Loading senders…"
                else -> "No unlinked senders found"
            },
            style = UI.typo.b2.style(
                color = if (selected != null) UI.colors.pureInverse else UI.colors.gray,
                fontWeight = FontWeight.SemiBold,
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (senderListExpanded) "Close" else "Change",
            style = UI.typo.c.style(
                color = Ivy,
                fontWeight = FontWeight.Bold,
            ),
        )
    }

    if (senderListExpanded && state.senders.isNotEmpty()) {
        Spacer(Modifier.height(8.dp))
        var filter by remember { mutableStateOf(selectEndTextFieldValue("")) }
        TappableInputBox(
            modifier = Modifier.padding(horizontal = 24.dp),
            value = filter,
            hint = "Filter senders…",
            onValueChanged = { filter = it },
        )
        Spacer(Modifier.height(8.dp))
        val visibleSenders = state.senders
            .filter { filter.text.isBlank() || it.senderId.contains(filter.text.trim(), ignoreCase = true) }
            .take(SENDER_LIST_MAX_VISIBLE)
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .fillMaxWidth()
                .clip(UI.shapes.r4)
                .background(UI.colors.medium),
        ) {
            visibleSenders.forEach { sender ->
                val isSelected = sender.senderId == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelectSender(sender.senderId)
                            senderListExpanded = false
                        }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = sender.senderId,
                        style = UI.typo.b2.style(
                            color = if (isSelected) Green else UI.colors.pureInverse,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${sender.messageCount} msg${if (sender.messageCount == 1) "" else "s"}",
                        style = UI.typo.c.style(
                            color = UI.colors.gray,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }
            }
        }
    }

    Spacer(Modifier.height(20.dp))

    SectionLabel(text = "SYNC PERIOD")
    Spacer(Modifier.height(8.dp))
    PeriodChipRow(
        selected = state.selectedPeriod,
        onSelect = onSelectPeriod,
    )

    Spacer(Modifier.height(20.dp))

    IvyCheckboxWithText(
        modifier = Modifier.padding(start = 16.dp),
        text = "Create transactions automatically",
        checked = state.autoRoute,
        onCheckedChange = onAutoRouteChange,
    )
}

@Composable
private fun ManageContent(
    state: SmsSetupViewState,
    onAutoRouteChange: (Boolean) -> Unit,
    onScanFurtherBack: (PeriodChipOption) -> Unit,
    onOpenTemplates: () -> Unit,
    onOpenReview: () -> Unit,
    onUnlink: () -> Unit,
) {
    var scanBackExpanded by remember { mutableStateOf(false) }

    SectionLabel(text = "LINKED SENDER")
    Spacer(Modifier.height(8.dp))
    Column(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth()
            .clip(UI.shapes.r4)
            .background(UI.colors.medium)
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Text(
            text = state.linkedSender.orEmpty(),
            style = UI.typo.b1.style(
                color = UI.colors.pureInverse,
                fontWeight = FontWeight.ExtraBold,
            ),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = state.lastSyncStatus,
            style = UI.typo.c.style(
                color = UI.colors.gray,
                fontWeight = FontWeight.SemiBold,
            ),
        )
        state.scanProgress?.let { p ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Syncing… ${p.processed} / ${p.total}",
                style = UI.typo.c.style(
                    color = Green,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }

    Spacer(Modifier.height(16.dp))

    IvyCheckboxWithText(
        modifier = Modifier.padding(start = 16.dp),
        text = "Create transactions automatically",
        checked = state.autoRoute,
        onCheckedChange = onAutoRouteChange,
    )

    Spacer(Modifier.height(16.dp))

    // "Scan further back…" — period chips appear on demand; picking one runs
    // a gap-only rescan (FR-005c). Plain "Sync now" stays incremental.
    Text(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .clickable { scanBackExpanded = !scanBackExpanded },
        text = if (scanBackExpanded) "Scan further back — pick how far:" else "Scan further back…",
        style = UI.typo.b2.style(
            color = Ivy,
            fontWeight = FontWeight.Bold,
        ),
    )
    if (scanBackExpanded) {
        Spacer(Modifier.height(8.dp))
        PeriodChipRow(
            selected = null,
            onSelect = {
                scanBackExpanded = false
                onScanFurtherBack(it)
            },
        )
    }

    Spacer(Modifier.height(20.dp))

    Row(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IvyOutlinedButton(
            text = "Templates",
            iconStart = null,
            modifier = Modifier.weight(1f),
            onClick = onOpenTemplates,
        )
        IvyOutlinedButton(
            text = if (state.pendingCount > 0) "Review (${state.pendingCount})" else "Review",
            iconStart = null,
            modifier = Modifier.weight(1f),
            onClick = onOpenReview,
        )
    }

    Spacer(Modifier.height(12.dp))

    IvyOutlinedButton(
        text = "Unlink sender",
        iconStart = null,
        borderColor = Red,
        textColor = Red,
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth(),
        onClick = onUnlink,
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        modifier = Modifier.padding(horizontal = 24.dp),
        text = text,
        style = UI.typo.c.style(
            color = UI.colors.gray,
            fontWeight = FontWeight.Bold,
        ),
    )
}

@Composable
private fun PeriodChipRow(
    selected: PeriodChipOption?,
    onSelect: (PeriodChipOption) -> Unit,
) {
    Row(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SETUP_PERIOD_CHIPS.forEach { option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(UI.shapes.rFull)
                    .background(if (isSelected) Green else UI.colors.medium)
                    .clickable { onSelect(option) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option.label,
                    style = UI.typo.c.style(
                        color = if (isSelected) Color.White else UI.colors.pureInverse,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                    ),
                )
            }
        }
    }
}

/**
 * One-tap destructive confirm — standard Ivy idiom (red gradient primary,
 * no type-the-name ceremony; the unlink is recoverable: existing
 * transactions are kept and a re-link backfills).
 */
@Composable
private fun BoxScope.UnlinkConfirmModal(
    visible: Boolean,
    senderId: String,
    dismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val modalId = remember(senderId) { UUID.randomUUID() }
    IvyModal(
        id = modalId,
        visible = visible,
        dismiss = dismiss,
        PrimaryAction = {
            IvyButton(
                text = "Unlink",
                backgroundGradient = GradientRed,
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
        Spacer(Modifier.height(24.dp))
    }
}
