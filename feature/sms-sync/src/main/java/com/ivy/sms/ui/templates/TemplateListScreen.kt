package com.ivy.sms.ui.templates

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
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
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.ui.directionFor
import com.ivy.sms.ui.theme.colorForRole
import com.ivy.wallet.ui.theme.Blue
import com.ivy.wallet.ui.theme.Gray
import com.ivy.wallet.ui.theme.Green
import com.ivy.wallet.ui.theme.Orange
import com.ivy.wallet.ui.theme.Red
import com.ivy.wallet.ui.theme.components.BackButtonType
import com.ivy.wallet.ui.theme.components.IvyButton
import com.ivy.wallet.ui.theme.components.IvyOutlinedButton
import com.ivy.wallet.ui.theme.components.IvyToolbar
import com.ivy.wallet.ui.theme.modal.IvyModal
import com.ivy.wallet.ui.theme.modal.ModalTitle
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import java.util.UUID

@Composable
fun TemplateListScreen(
    onOpenTemplate: (SmsTemplateId) -> Unit,
    onScanFurtherBack: () -> Unit,
    onOpenPendingReview: () -> Unit,
    viewModel: TemplateListViewModel = viewModel(),
) {
    val state = viewModel.uiState()
    viewModel.setNavigators(
        onTemplate = onOpenTemplate,
        onScanFurther = onScanFurtherBack,
        onPending = onOpenPendingReview,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(UI.colors.pure)
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenToolbar(
                pendingCount = state.pendingReviewCount,
                onScanFurther = { viewModel.onEvent(TemplateListEvent.ScanFurtherBack) },
                onOpenPending = { viewModel.onEvent(TemplateListEvent.OpenPendingReview) },
            )

            state.scanProgress?.let { p ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = "Scanning… ${p.processed} / ${p.total}",
                        style = UI.typo.c.style(
                            color = UI.colors.gray,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    Spacer(Modifier.height(6.dp))
                    LinearProgressIndicator(
                        progress = if (p.total > 0) p.processed.toFloat() / p.total else 0f,
                        modifier = Modifier.fillMaxWidth(),
                        color = Green,
                        trackColor = UI.colors.medium,
                    )
                }
            }

            if (state.templates.isEmpty()) {
                EmptyState()
            } else {
                val grouped = remember(state.templates) { groupTemplates(state.templates) }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .navigationBarsPadding(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    grouped.forEach { (group, rows) ->
                        val isOpen = state.expandedGroup == group
                        item(key = "header-${group.name}") {
                            GroupHeader(
                                group = group,
                                count = rows.size,
                                expanded = isOpen,
                                onToggle = {
                                    viewModel.onEvent(TemplateListEvent.ToggleGroup(group))
                                },
                            )
                        }
                        if (isOpen) {
                            items(rows, key = { it.id.value }) { row ->
                                // Kick off a Jaccard preload on first compose so the row
                                // can show the accurate count next to "Show messages"
                                // without the user needing to tap it.
                                LaunchedEffect(row.id) {
                                    viewModel.onEvent(TemplateListEvent.PreloadMatching(row.id))
                                }
                                TemplateRow(
                                    row = row,
                                    displayCount = state.displayCountByTemplate[row.id] ?: row.matchCount,
                                    onShowMatching = {
                                        viewModel.onEvent(TemplateListEvent.OpenMatching(row.id))
                                    },
                                    onClick = {
                                        viewModel.onEvent(TemplateListEvent.TemplateClicked(row.id))
                                    },
                                    onIgnoreForever = {
                                        viewModel.onEvent(TemplateListEvent.ToggleBlacklist(row.id))
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        // Modal popup for "Show N messages". Lives in BoxScope so it can
        // overlay the screen with the project's standard modal animation.
        val activeId = state.matchingModalTemplate
        val activeRow = activeId?.let { id -> state.templates.firstOrNull { it.id == id } }
        if (activeRow != null) {
            val all = activeRow.matchingMessages
            val capped = all.take(state.matchingModalLimit).toImmutableList()
            val canLoadMore = all.size > state.matchingModalLimit
            MatchingMessagesModal(
                visible = true,
                templateName = activeRow.name?.takeIf { it.isNotBlank() },
                bodies = capped,
                totalCount = all.size,
                loading = activeRow.matchingMessagesLoading,
                canLoadMore = canLoadMore,
                onLoadMore = {
                    viewModel.onEvent(TemplateListEvent.LoadMoreMatching)
                },
                dismiss = {
                    viewModel.onEvent(TemplateListEvent.CloseMatching)
                },
            )
        }
    }
}

@Composable
private fun ScreenToolbar(
    pendingCount: Int,
    onScanFurther: () -> Unit,
    onOpenPending: () -> Unit,
) {
    val nav = com.ivy.navigation.navigation()
    Column {
        IvyToolbar(
            onBack = { nav.back() },
            backButtonType = BackButtonType.BACK,
        ) {
            Spacer(Modifier.width(16.dp))
            Text(
                text = "SMS templates",
                style = UI.typo.h2.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.ExtraBold,
                ),
            )
            Spacer(Modifier.weight(1f))
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(UI.shapes.rFull)
                        .background(UI.colors.medium)
                        .clickable { menuOpen = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More",
                        tint = UI.colors.pureInverse,
                    )
                }
                if (menuOpen) {
                    AlertDialog(
                        onDismissRequest = { menuOpen = false },
                        title = {
                            Text(
                                text = "Scan further back",
                                style = UI.typo.h2.style(
                                    color = UI.colors.pureInverse,
                                    fontWeight = FontWeight.ExtraBold,
                                ),
                            )
                        },
                        text = {
                            Text(
                                text = "Pull older messages from the inbox to discover more templates.",
                                style = UI.typo.b2.style(
                                    color = UI.colors.gray,
                                    fontWeight = FontWeight.Medium,
                                ),
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                menuOpen = false
                                onScanFurther()
                            }) {
                                Text("Scan", color = Green)
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = { menuOpen = false }) {
                                Text("Cancel", color = Gray)
                            }
                        },
                        containerColor = UI.colors.pure,
                    )
                }
            }
            Spacer(Modifier.width(20.dp))
        }
        if (pendingCount > 0) {
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .clip(UI.shapes.r4)
                    .background(Orange.copy(alpha = 0.18f))
                    .clickable(onClick = onOpenPending)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "$pendingCount pending review item${if (pendingCount == 1) "" else "s"} →",
                    style = UI.typo.b2.style(
                        color = Orange,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
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
                text = "No templates yet",
                style = UI.typo.h2.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.ExtraBold,
                ),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Open a wallet and tap Sync to scan its linked SMS chat.",
                style = UI.typo.b2.style(
                    color = UI.colors.gray,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}

@Composable
private fun TemplateRow(
    row: TemplateRowViewState,
    displayCount: Int,
    onShowMatching: () -> Unit,
    onClick: () -> Unit,
    onIgnoreForever: () -> Unit,
) {
    var confirmIgnore by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(UI.shapes.r4)
            .background(UI.colors.medium)
            .padding(16.dp),
    ) {
        row.name?.takeIf { it.isNotBlank() }?.let { name ->
            Text(
                text = name,
                style = UI.typo.b1.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.ExtraBold,
                ),
            )
            Spacer(Modifier.height(4.dp))
        }

        CompositionLocalProvider(LocalLayoutDirection provides directionFor(row.exampleBody)) {
            Text(
                text = annotatePreview(row, defaultColor = UI.colors.pureInverse),
                style = UI.typo.b2.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StateBadge(row.state)
            Spacer(Modifier.weight(1f))
            // Single-match templates: invisible spacer keeps the card layout
            // uniform with multi-match rows (the example body shown above IS
            // the only matching message, so a "Show 1 message" link would
            // just open a modal with the same text the user is already
            // looking at).
            if (displayCount > 1) {
                Text(
                    text = "Show $displayCount messages",
                    style = UI.typo.c.style(
                        color = Blue,
                        fontWeight = FontWeight.Bold,
                    ),
                    modifier = Modifier.clickable(onClick = onShowMatching),
                )
            } else {
                Spacer(Modifier.height(20.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            IvyButton(
                modifier = Modifier.weight(1f),
                text = if (row.state == TemplateState.ACTIVE) "Edit roles" else "Open & map",
                onClick = onClick,
            )
            Spacer(Modifier.width(8.dp))
            IvyOutlinedButton(
                modifier = Modifier.weight(1f),
                iconStart = null,
                text = if (row.state == TemplateState.BLACKLISTED) "Un-ignore" else "Ignore",
                borderColor = if (row.state == TemplateState.BLACKLISTED) Gray else Red,
                textColor = if (row.state == TemplateState.BLACKLISTED) Gray else Red,
                onClick = {
                    if (row.state == TemplateState.ACTIVE) confirmIgnore = true
                    else onIgnoreForever()
                },
            )
        }
    }

    if (confirmIgnore) {
        AlertDialog(
            onDismissRequest = { confirmIgnore = false },
            title = {
                Text(
                    text = "Ignore this template forever?",
                    style = UI.typo.h2.style(
                        color = UI.colors.pureInverse,
                        fontWeight = FontWeight.ExtraBold,
                    ),
                )
            },
            text = {
                Text(
                    text = "Future matching messages will be silently dropped. Existing transactions are not deleted.",
                    style = UI.typo.b2.style(
                        color = UI.colors.gray,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmIgnore = false
                    onIgnoreForever()
                }) {
                    Text("Ignore forever", color = Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmIgnore = false }) {
                    Text("Cancel", color = Gray)
                }
            },
            containerColor = UI.colors.pure,
        )
    }
}

@Composable
private fun StateBadge(state: TemplateState) {
    val (label, accent) = when (state) {
        TemplateState.UNMAPPED -> "Unmapped" to Blue
        TemplateState.ACTIVE -> "Active" to Green
        TemplateState.BLACKLISTED -> "Ignored" to Gray
        TemplateState.PENDING_REVIEW -> "Review" to Orange
    }
    Box(
        modifier = Modifier
            .clip(UI.shapes.rFull)
            .background(accent.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = UI.typo.c.style(
                color = accent,
                fontWeight = FontWeight.Bold,
            ),
        )
    }
}

private data class GroupVisuals(val title: String, val accent: Color)

private fun TemplateGroupKey.visuals(): GroupVisuals = when (this) {
    TemplateGroupKey.Income -> GroupVisuals("Income", Green)
    TemplateGroupKey.Expense -> GroupVisuals("Expense", Red)
    TemplateGroupKey.Transfer -> GroupVisuals("Transfer", Blue)
    TemplateGroupKey.Unmapped -> GroupVisuals("Needs mapping", Orange)
    TemplateGroupKey.Ignored -> GroupVisuals("Ignored", Gray)
}

private fun groupTemplates(rows: List<TemplateRowViewState>): List<Pair<TemplateGroupKey, List<TemplateRowViewState>>> {
    val buckets = linkedMapOf<TemplateGroupKey, MutableList<TemplateRowViewState>>(
        TemplateGroupKey.Income to mutableListOf(),
        TemplateGroupKey.Expense to mutableListOf(),
        TemplateGroupKey.Transfer to mutableListOf(),
        TemplateGroupKey.Unmapped to mutableListOf(),
        TemplateGroupKey.Ignored to mutableListOf(),
    )
    for (row in rows) {
        val group = when {
            row.state == TemplateState.BLACKLISTED -> TemplateGroupKey.Ignored
            else -> {
                val amountRole = row.wildcardRolesByPosition.values.firstOrNull {
                    it == WildcardRole.Income || it == WildcardRole.Expense || it == WildcardRole.Transfer
                }
                when (amountRole) {
                    WildcardRole.Income -> TemplateGroupKey.Income
                    WildcardRole.Expense -> TemplateGroupKey.Expense
                    WildcardRole.Transfer -> TemplateGroupKey.Transfer
                    else -> TemplateGroupKey.Unmapped
                }
            }
        }
        buckets.getValue(group).add(row)
    }
    return buckets.entries.filter { it.value.isNotEmpty() }.map { it.key to it.value.toList() }
}

@Composable
private fun GroupHeader(
    group: TemplateGroupKey,
    count: Int,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val v = group.visuals()
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = springBounce(),
        label = "groupHeaderRotation",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(UI.shapes.r4)
            .background(if (expanded) v.accent else v.accent.copy(alpha = 0.18f))
            .clickable { onToggle() }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = v.title.uppercase(),
            style = UI.typo.b2.style(
                color = if (expanded) Color.White else v.accent,
                fontWeight = FontWeight.ExtraBold,
            ),
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = "$count",
            style = UI.typo.b2.style(
                color = if (expanded) Color.White else v.accent,
                fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(Modifier.width(8.dp))
        Icon(
            imageVector = Icons.Default.ExpandMore,
            contentDescription = null,
            tint = if (expanded) Color.White else v.accent,
            modifier = Modifier.rotate(rotation),
        )
    }
}

/**
 * Modal popup that lists every matching message for a template, paginated
 * 10 at a time. Replaces the inline expand row — the previous design
 * inflated the template card and made the list hard to scan once it grew
 * beyond a few items. Uses the project's standard `IvyModal` so the look
 * matches the unlink confirmation, role picker, etc.
 *
 * The "Load 10 more" button reveals the next page; once everything is
 * shown the button hides itself. A 1px separator (same hairline used in
 * the sender-picker batch markers) sits between rows so the wall of SMS
 * text stays visually parsable.
 */
@Composable
private fun BoxScope.MatchingMessagesModal(
    visible: Boolean,
    templateName: String?,
    bodies: ImmutableList<String>,
    totalCount: Int,
    loading: Boolean,
    canLoadMore: Boolean,
    onLoadMore: () -> Unit,
    dismiss: () -> Unit,
) {
    val modalId = remember(templateName, totalCount) { UUID.randomUUID() }
    val scrollState: ScrollState = rememberScrollState()

    IvyModal(
        id = modalId,
        visible = visible,
        dismiss = dismiss,
        scrollState = scrollState,
        PrimaryAction = {
            IvyButton(
                text = "Close",
                onClick = dismiss,
            )
        },
    ) {
        Spacer(Modifier.height(32.dp))
        ModalTitle(text = templateName?.takeIf { it.isNotBlank() } ?: "Matching messages")
        Spacer(Modifier.height(8.dp))
        Text(
            modifier = Modifier.padding(horizontal = 32.dp),
            text = "${bodies.size} of $totalCount shown",
            style = UI.typo.c.style(
                color = UI.colors.gray,
                fontWeight = FontWeight.Bold,
            ),
        )
        Spacer(Modifier.height(16.dp))

        if (loading && bodies.isEmpty()) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                color = Blue,
                trackColor = UI.colors.medium,
            )
            Spacer(Modifier.height(20.dp))
            return@IvyModal
        }
        if (bodies.isEmpty()) {
            Text(
                modifier = Modifier.padding(horizontal = 32.dp),
                text = "No matching messages found in the device inbox.",
                style = UI.typo.b2.style(
                    color = UI.colors.gray,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(Modifier.height(20.dp))
            return@IvyModal
        }

        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
        ) {
            bodies.forEachIndexed { idx, body ->
                if (idx > 0) HrDivider()
                MatchingMessageRow(body = body)
            }
        }

        if (canLoadMore) {
            Spacer(Modifier.height(16.dp))
            IvyOutlinedButton(
                text = "Load 10 more",
                iconStart = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp),
                onClick = onLoadMore,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun MatchingMessageRow(body: String) {
    CompositionLocalProvider(LocalLayoutDirection provides directionFor(body)) {
        Text(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            text = body,
            style = UI.typo.b2.style(
                color = UI.colors.pureInverse,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

/** Hairline separator — matches the batch dividers in the sender picker. */
@Composable
private fun HrDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(UI.colors.pureInverse.copy(alpha = 0.06f)),
    )
}

private fun annotatePreview(row: TemplateRowViewState, defaultColor: Color): AnnotatedString {
    val patternTokens = row.pattern.split(Regex("\\s+")).filter { it.isNotBlank() }
    val bodyTokens = row.exampleBody.split(Regex("\\s+")).filter { it.isNotBlank() }
    return buildAnnotatedString {
        for ((idx, bodyTok) in bodyTokens.withIndex()) {
            if (idx > 0) append(' ')
            val isWildcard = patternTokens.getOrNull(idx) == com.ivy.sms.data.WILDCARD_TOKEN
            val role = row.wildcardRolesByPosition[idx] ?: WildcardRole.Unmapped
            if (isWildcard) {
                withStyle(
                    SpanStyle(
                        color = colorForRole(role),
                        fontWeight = FontWeight.Bold,
                    ),
                ) { append(bodyTok) }
            } else {
                withStyle(SpanStyle(color = defaultColor)) { append(bodyTok) }
            }
        }
    }
}

private fun annotateBody(
    body: String,
    pattern: String,
    rolesByPosition: Map<Int, WildcardRole>,
    defaultColor: Color,
): AnnotatedString {
    val patternTokens = pattern.split(Regex("\\s+")).filter { it.isNotBlank() }
    val bodyTokens = body.split(Regex("\\s+")).filter { it.isNotBlank() }
    return buildAnnotatedString {
        var bodyIdx = 0
        for ((patternIdx, ptok) in patternTokens.withIndex()) {
            if (ptok != com.ivy.sms.data.WILDCARD_TOKEN) {
                val match = (bodyIdx until bodyTokens.size).firstOrNull {
                    bodyTokens[it].equals(ptok, ignoreCase = true)
                }
                if (match == null) {
                    if (length > 0) append(' ')
                    withStyle(SpanStyle(color = defaultColor)) { append(ptok) }
                    continue
                }
                while (bodyIdx <= match) {
                    if (length > 0) append(' ')
                    withStyle(SpanStyle(color = defaultColor)) { append(bodyTokens[bodyIdx]) }
                    bodyIdx++
                }
            } else {
                val nextLiteralPattern = (patternIdx + 1 until patternTokens.size).firstOrNull {
                    patternTokens[it] != com.ivy.sms.data.WILDCARD_TOKEN
                }
                val stopAt = if (nextLiteralPattern == null) bodyTokens.size else {
                    val literal = patternTokens[nextLiteralPattern]
                    (bodyIdx until bodyTokens.size).firstOrNull {
                        bodyTokens[it].equals(literal, ignoreCase = true)
                    } ?: bodyTokens.size
                }
                val role = rolesByPosition[patternIdx] ?: WildcardRole.Unmapped
                while (bodyIdx < stopAt) {
                    if (length > 0) append(' ')
                    withStyle(
                        SpanStyle(
                            color = colorForRole(role),
                            fontWeight = FontWeight.Bold,
                        ),
                    ) { append(bodyTokens[bodyIdx]) }
                    bodyIdx++
                }
            }
        }
        while (bodyIdx < bodyTokens.size) {
            if (length > 0) append(' ')
            withStyle(SpanStyle(color = defaultColor)) { append(bodyTokens[bodyIdx]) }
            bodyIdx++
        }
    }
}

