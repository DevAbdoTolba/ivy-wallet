package com.ivy.sms.ui.pending

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.legacy.utils.springBounce
import com.ivy.navigation.TemplateMappingScreen
import com.ivy.navigation.navigation
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.ui.directionFor
import com.ivy.sms.ui.theme.colorForRole
import com.ivy.wallet.ui.theme.Green
import com.ivy.wallet.ui.theme.Orange
import com.ivy.wallet.ui.theme.Red
import com.ivy.wallet.ui.theme.components.BackButtonType
import com.ivy.wallet.ui.theme.components.IvyButton
import com.ivy.wallet.ui.theme.components.IvyOutlinedButton
import com.ivy.wallet.ui.theme.components.IvyToolbar

@Composable
fun PendingReviewScreen(
    walletId: com.ivy.data.model.AccountId? = null,
    viewModel: PendingReviewViewModel = viewModel(),
) {
    LaunchedEffect(walletId) { viewModel.setWalletFilter(walletId) }
    val state = viewModel.uiState()
    val nav = navigation()
    // Persistent progress sourced entirely from DataStore counters so
    // back-navigation, process restart, AND blacklist/clear ops don't
    // shrink the denominator. discoveredTotal is monotonic on enqueue,
    // reviewedTotal monotonic on resolve. Fallback to current items.size
    // for legacy installs that haven't accumulated a discovered count yet.
    val resolved = state.reviewedTotal
    val totalEverQueued = maxOf(state.discoveredTotal, resolved + state.items.size)
    val templatesLeft = remember(state.items) {
        state.items.map { it.templateId.value }.toSet().size
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
                    text = if (state.scopedToWallet) "Review (this wallet)" else "Review (all)",
                    style = UI.typo.h2.style(
                        color = UI.colors.pureInverse,
                        fontWeight = FontWeight.ExtraBold,
                    ),
                )
            }

            // Progress hero — big remaining number above a slim bar.
            ProgressHero(
                remaining = state.items.size,
                total = totalEverQueued,
                templatesLeft = templatesLeft,
                templatesMappedTotal = state.templatesMappedTotal,
                scanProgress = state.scanProgress,
            )

            if (state.pendingIgnoreTemplateId != null) {
                UndoIgnoreRow(
                    hiddenCount = state.pendingIgnoreHiddenCount,
                    onUndo = { viewModel.onEvent(PendingReviewEvent.UndoIgnore) },
                )
            }

            if (state.items.isEmpty()) {
                EmptyState()
                return@Column
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(state.items, key = { it.id }) { row ->
                    PendingItemCard(
                        row = row,
                        onToggleExpand = {
                            viewModel.onEvent(PendingReviewEvent.ToggleExpand(row.id))
                        },
                        onMapTemplate = {
                            // Pass the tapped pending item's id so the
                            // mapping screen renders THIS message's body,
                            // not the cluster's first-ever sample (which
                            // the user reported as confusing — tapping
                            // "+40 EGP" was opening a "+89 EGP" sibling).
                            // Also pass the wallet scope when this queue
                            // is wallet-scoped, so the save reprocess
                            // doesn't silently route pending items from a
                            // different wallet.
                            nav.navigateTo(
                                TemplateMappingScreen(
                                    templateId = row.templateId.value.toString(),
                                    pendingItemId = row.id,
                                    walletId = walletId?.value?.toString(),
                                )
                            )
                        },
                        onIgnoreForever = {
                            viewModel.onEvent(PendingReviewEvent.IgnoreForever(row.templateId))
                        },
                        onDismiss = {
                            viewModel.onEvent(PendingReviewEvent.Dismiss(row.itemId))
                        },
                    )
                }
            }
        }
    }
}

/**
 * In-place undo affordance for a buffered "Ignore" — shown for
 * [IGNORE_UNDO_WINDOW_MILLIS] while the template's items are soft-hidden
 * and the destructive blacklist hasn't been persisted yet.
 */
@Composable
private fun UndoIgnoreRow(
    hiddenCount: Int,
    onUndo: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(UI.shapes.r4)
            .background(Orange.copy(alpha = 0.18f))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (hiddenCount > 0) {
                "Template ignored — $hiddenCount message${if (hiddenCount == 1) "" else "s"} hidden"
            } else {
                "Template ignored"
            },
            style = UI.typo.c.style(
                color = Orange,
                fontWeight = FontWeight.Bold,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Undo",
            style = UI.typo.b2.style(
                color = UI.colors.pureInverse,
                fontWeight = FontWeight.ExtraBold,
            ),
            modifier = Modifier.clickable(onClick = onUndo),
        )
    }
}

@Composable
private fun ProgressHero(
    remaining: Int,
    total: Int,
    templatesLeft: Int,
    templatesMappedTotal: Int,
    scanProgress: com.ivy.sms.domain.model.ScanProgress? = null,
) {
    val resolved = (total - remaining).coerceAtLeast(0)
    val target = if (total == 0) 0f else resolved.toFloat() / total.toFloat()
    val progress by animateFloatAsState(
        targetValue = target,
        animationSpec = springBounce(),
        label = "reviewProgress",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = "$remaining",
                style = UI.typo.h1.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.ExtraBold,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                modifier = Modifier.padding(bottom = 6.dp),
                text = if (remaining == 1) "message left" else "messages left",
                style = UI.typo.b2.style(
                    color = UI.colors.gray,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
            Spacer(Modifier.weight(1f))
            if (templatesLeft > 0) {
                // Tiny hint — most pending items repeat across only a handful of
                // templates, so this number is usually much smaller than the
                // message count and tells the user "you only need to map N
                // shapes to clear the queue".
                Text(
                    modifier = Modifier.padding(bottom = 6.dp),
                    text = if (templatesLeft == 1) "1 template" else "$templatesLeft templates",
                    style = UI.typo.c.style(
                        color = Green,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }
        if (total > 0) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "$resolved of $total reviewed" +
                    if (templatesMappedTotal > 0) " · $templatesMappedTotal templates mapped" else "",
                style = UI.typo.c.style(
                    color = UI.colors.gray,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
        Spacer(Modifier.height(10.dp))
        // Slim animated progress bar.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(UI.shapes.rFull)
                .background(UI.colors.medium),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(UI.shapes.rFull)
                    .background(Green),
            )
        }
        // Live scan line — the first sync after "Save & Sync now" lands here,
        // so the user watches messages arrive where they'll act on them.
        scanProgress?.let { p ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Scanning inbox… ${p.processed} / ${p.total}" +
                    if (p.itemsQuarantined > 0) " · ${p.itemsQuarantined} to review" else "",
                style = UI.typo.c.style(
                    color = Green,
                    fontWeight = FontWeight.Bold,
                ),
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "All caught up",
                style = UI.typo.h2.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.ExtraBold,
                ),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Nothing left to review. New messages that need your input will land here.",
                style = UI.typo.b2.style(
                    color = UI.colors.gray,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}

@Composable
private fun PendingItemCard(
    row: PendingItemRowViewState,
    onToggleExpand: () -> Unit,
    onMapTemplate: () -> Unit,
    onIgnoreForever: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(UI.shapes.r4)
            .background(UI.colors.medium)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SenderChip(text = row.senderId)
            Spacer(Modifier.width(8.dp))
            Text(
                text = humanizeReason(row.reason, row.templateActive),
                style = UI.typo.c.style(
                    color = Orange,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(10.dp))

        CompositionLocalProvider(LocalLayoutDirection provides directionFor(row.body)) {
            val visible = if (row.expanded || row.body.length <= 160) {
                row.body
            } else {
                row.body.take(160) + "…"
            }
            Text(
                text = annotatePendingBody(
                    body = visible,
                    pattern = row.templatePattern,
                    rolesByPosition = row.wildcardRolesByPosition,
                ),
                style = UI.typo.b2.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.Medium,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (row.body.length > 160) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (row.expanded) "Collapse" else "Show full SMS",
                style = UI.typo.c.style(
                    color = UI.colors.gray,
                    fontWeight = FontWeight.Bold,
                ),
                modifier = Modifier.clickable(onClick = onToggleExpand),
            )
        }

        Spacer(Modifier.height(16.dp))

        // Two actions on the same row so the user's finger always lands on the
        // same horizontal positions: Map (primary, left) and Ignore (destructive,
        // right).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IvyButton(
                modifier = Modifier.weight(1f),
                text = "Map",
                onClick = onMapTemplate,
            )
            IvyOutlinedButton(
                modifier = Modifier.weight(1f),
                text = "Ignore",
                iconStart = null,
                borderColor = Red,
                textColor = Red,
                onClick = onIgnoreForever,
            )
        }

        Spacer(Modifier.height(10.dp))

        // Per-message dismiss (FR-026(b)): clears just THIS message without
        // blacklisting its whole template — the only way to drop one stray
        // promo SMS that happens to share a shape with real messages.
        Text(
            text = "Dismiss just this message",
            style = UI.typo.c.style(
                color = UI.colors.gray,
                fontWeight = FontWeight.Bold,
            ),
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clickable(onClick = onDismiss)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun SenderChip(text: String) {
    Box(
        modifier = Modifier
            .clip(UI.shapes.rFull)
            .background(UI.colors.pure)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = UI.typo.c.style(
                color = UI.colors.pureInverse,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

/**
 * Maps the developer-facing quarantine reason to a sentence the user can act on.
 * Reasons are stored as enum names (e.g. "AMOUNT_NOT_PARSEABLE:specific:detail")
 * so we strip everything after the first colon before matching.
 *
 * [templateActive]: items stuck under an ACTIVE template keep their original
 * quarantine reason (the dedup'd row is never updated), so "Needs roles
 * assigned" lies after the user already mapped the roles — those rows render
 * "Partially mapped — couldn't align" instead. Sender/currency reasons stay
 * as-is: they're accurate regardless of template state.
 */
internal fun humanizeReason(reason: String, templateActive: Boolean = false): String {
    val key = reason.substringBefore(':').trim()
    if (templateActive && (key == "TEMPLATE_NOT_MAPPED" || key == "AMOUNT_NOT_PARSEABLE")) {
        return "Partially mapped — couldn't align"
    }
    return when (key) {
        "TEMPLATE_NOT_MAPPED" -> "Needs roles assigned"
        "AMOUNT_NOT_PARSEABLE" -> "Amount unclear — please map"
        "SENDER_NOT_LINKED" -> "Sender not linked to a wallet"
        "CURRENCY_MISMATCH" -> "Currency doesn't match the wallet"
        "AUTO_ROUTE_DISABLED" -> "Held for approval — auto-import is off"
        else -> "Needs your attention"
    }
}

private fun annotatePendingBody(
    body: String,
    pattern: String,
    rolesByPosition: Map<Int, WildcardRole>,
): AnnotatedString {
    val patternTokens = pattern.split(Regex("\\s+")).filter { it.isNotBlank() }
    val bodyTokens = body.split(Regex("\\s+")).filter { it.isNotBlank() }
    return buildAnnotatedString {
        for ((idx, bodyTok) in bodyTokens.withIndex()) {
            if (idx > 0) append(' ')
            val isWildcard = patternTokens.getOrNull(idx) == com.ivy.sms.data.WILDCARD_TOKEN
            val role = rolesByPosition[idx] ?: WildcardRole.Unmapped
            if (isWildcard) {
                withStyle(
                    SpanStyle(
                        color = colorForRole(role),
                        fontWeight = FontWeight.Bold,
                    ),
                ) {
                    append(bodyTok)
                }
            } else {
                append(bodyTok)
            }
        }
    }
}
