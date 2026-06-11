package com.ivy.sms.ui.templates

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.legacy.utils.selectEndTextFieldValue
import kotlinx.collections.immutable.toImmutableList
import com.ivy.navigation.navigation
import com.ivy.sms.data.WILDCARD_TOKEN
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.domain.model.WildcardSlot
import com.ivy.sms.domain.usecase.AlignedDisplayToken
import com.ivy.sms.domain.usecase.alignForDisplay
import com.ivy.sms.ui.directionFor
import com.ivy.sms.ui.theme.colorForRole
import com.ivy.sms.ui.theme.iconForRole
import com.ivy.wallet.ui.theme.Orange
import com.ivy.wallet.ui.theme.Red
import com.ivy.wallet.ui.theme.components.BackButtonType
import com.ivy.wallet.ui.theme.components.IvyBasicTextField
import com.ivy.wallet.ui.theme.components.IvyButton
import com.ivy.wallet.ui.theme.components.IvyOutlinedButton
import com.ivy.wallet.ui.theme.components.IvyToolbar
import com.ivy.wallet.ui.theme.modal.IvyModal
import com.ivy.wallet.ui.theme.modal.ModalTitle
import java.util.UUID

@Composable
fun TemplateMappingScreen(
    templateId: SmsTemplateId,
    onSaved: (Int) -> Unit,
    onIgnoreForever: () -> Unit = {},
    pendingItemId: String? = null,
    walletScope: com.ivy.data.model.AccountId? = null,
    viewModel: TemplateMappingViewModel = viewModel(),
) {
    // Push the wallet scope into the VM on every recompose (cheap, idempotent).
    // Keying on viewModel too in case the custom nav recreates it mid-screen.
    LaunchedEffect(walletScope, viewModel) {
        viewModel.setWalletScope(walletScope)
    }
    val state = viewModel.uiState()
    val nav = navigation()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(templateId) { viewModel.load(templateId) }

    // The whole template is fetched DIRECTLY from the repo into Compose state
    // here, completely bypassing the VM's lifecycle. The VM still owns saves
    // and role bindings, but the data the user looks at comes from this
    // produceState. Even if the VM-store gets cleared mid-flight (which it
    // does on every screen change in this app's custom navigation), the
    // screen still has the template the moment the DAO call returns. We seed
    // the VM via seedFromScreen so save() has a templateId to work with.
    val fetchedTemplate by androidx.compose.runtime.produceState<com.ivy.sms.domain.model.SmsTemplate?>(
        initialValue = null,
        key1 = templateId,
    ) {
        try {
            val ep = dagger.hilt.android.EntryPointAccessors.fromApplication(
                context.applicationContext,
                TemplateLookupEntryPoint::class.java,
            )
            val template = ep.templateRepo().findById(templateId).getOrNull()
            value = template
            if (template != null) {
                viewModel.seedFromScreen(template)
            }
        } catch (t: Throwable) {
            timber.log.Timber.e(t, "TemplateMapping screen-fallback load failed")
        }
    }

    // The VM can be recreated mid-screen (this app's custom nav clears
    // ViewModelStore on every screen change), in which case its loadedTemplate
    // becomes null while produceState above doesn't re-execute (its key,
    // templateId, didn't change). That left role picks landing on an empty
    // VM and the user got the "pick at least one amount" rejection no matter
    // what they did. Keying this LaunchedEffect on `viewModel` itself fires
    // whenever the VM ref changes, so a fresh instance gets re-seeded
    // immediately. seedFromScreen is idempotent for the same instance.
    LaunchedEffect(fetchedTemplate, viewModel) {
        fetchedTemplate?.let { viewModel.seedFromScreen(it) }
    }

    // When the user reached this screen by tapping a SPECIFIC pending item,
    // look up that item's body and use it as the chip-canvas display below.
    // Without this the screen always rendered `template.exampleBody` — the
    // cluster's first-ever sample — so a tap on "+40 EGP" could open the
    // mapper around a sibling "+89 EGP" message. Roles + pattern still come
    // from the template; only the visible body changes.
    val tappedItemBody by androidx.compose.runtime.produceState<String?>(
        initialValue = null,
        key1 = pendingItemId,
    ) {
        val id = pendingItemId ?: return@produceState
        try {
            val ep = dagger.hilt.android.EntryPointAccessors.fromApplication(
                context.applicationContext,
                TemplateLookupEntryPoint::class.java,
            )
            val items = ep.pendingRepo().findAll().getOrNull().orEmpty()
            value = items.firstOrNull { it.id.value.toString() == id }?.sms?.body
        } catch (t: Throwable) {
            timber.log.Timber.e(t, "TemplateMapping pending-item lookup failed")
        }
    }

    // Only auto-navigate away when the save was a CLEAN sweep — every
    // pending item routed (or the leftovers were bulk-dismissed). If any
    // failed, stay on the screen and show a breakdown card so the user can
    // edit the pattern or dismiss the leftovers instead of being bounced to
    // a queue that still flags the template as needing attention.
    state.convertedFromQueue?.let { converted ->
        if (state.failedTotal == 0) {
            LaunchedEffect(converted) { onSaved(converted) }
        }
    }

    // Render data: pattern + body come from the VM state if populated, else
    // from the directly-fetched template (so the user always sees the SMS
    // even when the VM is racing). Wildcard ROLES, however, ALWAYS come from
    // state.rolesByWildcardId — that map is the single source of truth for
    // the user's picks.
    //
    // CRITICAL: the chip canvas MUST render against the TEMPLATE's own
    // exampleBody — that's the body the pattern was built from, so the
    // pattern-vs-body 2-pointer aligner in buildTokenList is guaranteed to
    // line up and every chip's positionInPattern is correct. An earlier
    // attempt (P1-3) rendered the canvas against the *tapped* pending item's
    // body so the user "saw the message they tapped"; but a sibling body
    // doesn't necessarily align to the pattern, the aligner desynced, taps
    // landed on the wrong slots, and slots got silently reverted to
    // literals — baking message-specific values like "387.44." into the
    // pattern and breaking alignment for every other message. Never again:
    // canvas = template body, full stop.
    val displayBody = state.exampleBody.ifBlank { fetchedTemplate?.exampleBody.orEmpty() }
    // The tapped message is shown as read-only CONTEXT only (see below) — it
    // never feeds the chip aligner.
    val tappedContext = tappedItemBody?.takeIf { it.isNotBlank() && it != displayBody }
    val displayWildcards = remember(fetchedTemplate, state.wildcards, state.rolesByWildcardId) {
        val baseChips: List<WildcardChip> = if (state.wildcards.isNotEmpty()) {
            state.wildcards
        } else {
            fetchedTemplate?.wildcardSlots
                ?.map {
                    WildcardChip(
                        id = it.id,
                        positionInPattern = it.positionInPattern,
                        exampleValue = it.exampleValue,
                        role = it.role,
                    )
                }
                .orEmpty()
        }
        baseChips.map { chip ->
            chip.copy(role = state.rolesByWildcardId[chip.id] ?: chip.role)
        }.toImmutableList()
    }
    val isLoading =
        fetchedTemplate == null && state.pattern.isBlank() && state.exampleBody.isBlank()

    // Local field state, re-initialised ONLY when the loaded template id changes
    // so each keystroke doesn't reset the cursor to the end. We push every
    // change to the VM so save() reads the current value.
    var nameField by remember(state.templateId) {
        mutableStateOf(selectEndTextFieldValue(state.name))
    }
    LaunchedEffect(state.name) {
        // If the VM updates name from outside (e.g., after first load), reflect it.
        if (state.name != nameField.text) {
            nameField = selectEndTextFieldValue(state.name)
        }
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
                        text = "Map template",
                        style = UI.typo.h2.style(
                            color = UI.colors.pureInverse,
                            fontWeight = FontWeight.ExtraBold,
                        ),
                    )
                    // Scoped-wallet indicator: the save reprocess only routes
                    // into this wallet, so make the scope visible up-front.
                    state.walletScopeName?.let { wallet ->
                        Text(
                            text = "for $wallet",
                            style = UI.typo.c.style(
                                color = UI.colors.gray,
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = true)
                    .navigationBarsPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Tap a chip to pick its role. Tap any other word to mark it variable.",
                    style = UI.typo.b2.style(
                        color = UI.colors.gray,
                        fontWeight = FontWeight.Medium,
                    ),
                )

                // Read-only context: the specific SMS the user tapped to get
                // here. It's NOT the chip canvas (that's always the template's
                // own example body so the aligner stays in sync) — just a
                // reminder of which message they were acting on.
                tappedContext?.let { tapped ->
                    CompositionLocalProvider(
                        LocalLayoutDirection provides directionFor(tapped),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(UI.shapes.r4)
                                .background(UI.colors.medium)
                                .padding(12.dp),
                        ) {
                            Column {
                                Text(
                                    text = "YOU TAPPED",
                                    style = UI.typo.c.style(
                                        color = UI.colors.gray,
                                        fontWeight = FontWeight.Bold,
                                    ),
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = tapped,
                                    style = UI.typo.c.style(
                                        color = UI.colors.pureInverse,
                                        fontWeight = FontWeight.Medium,
                                    ),
                                )
                            }
                        }
                    }
                }

                CompositionLocalProvider(LocalLayoutDirection provides directionFor(displayBody)) {
                    if (isLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(UI.shapes.r4)
                                .background(UI.colors.medium)
                                .padding(16.dp),
                        ) {
                            androidx.compose.material3.LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(UI.shapes.rFull),
                                color = com.ivy.wallet.ui.theme.Green,
                                trackColor = UI.colors.pure,
                            )
                        }
                    } else {
                        TokenizedExample(
                            pattern = state.pattern.ifBlank { fetchedTemplate?.pattern.orEmpty() },
                            exampleBody = displayBody,
                            wildcards = displayWildcards,
                            onWildcardTap = { id ->
                                viewModel.onEvent(TemplateMappingEvent.WildcardTapped(id))
                            },
                            onLiteralTap = { pos, tok ->
                                viewModel.onEvent(TemplateMappingEvent.LiteralTapped(pos, tok))
                            },
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))
                Text(
                    text = "TRANSACTION TITLE",
                    style = UI.typo.c.style(
                        color = UI.colors.gray,
                        fontWeight = FontWeight.Bold,
                    ),
                )
                com.ivy.sms.ui.components.TappableInputBox(
                    value = nameField,
                    hint = "Optional — defaults to merchant or first SMS words",
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Text,
                        imeAction = ImeAction.Done,
                        capitalization = KeyboardCapitalization.Sentences,
                    ),
                    onValueChanged = { tfv ->
                        nameField = tfv
                        viewModel.onEvent(TemplateMappingEvent.NameChanged(tfv.text))
                    },
                )

                state.error?.let { msg ->
                    Text(
                        text = msg,
                        style = UI.typo.c.style(
                            color = Red,
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                }

                // canSave reads state.rolesByWildcardId directly so the user's
                // pick enables the button as soon as the VM processes the
                // event — independent of whether displayWildcards has been
                // rebuilt from fetchedTemplate yet.
                val canSave = state.rolesByWildcardId.values.any {
                    it == WildcardRole.Income ||
                        it == WildcardRole.Expense ||
                        it == WildcardRole.Transfer
                } && !state.saving

                state.reprocess?.let { p ->
                    ReprocessProgressCard(progress = p)
                }

                // Blocking zero-alignment warning: the save was rejected
                // because the pattern aligned NONE of this template's queued
                // messages. The user must either keep editing or explicitly
                // accept a save that won't route anything.
                state.zeroAlignmentWarning?.let { queued ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(UI.shapes.r4)
                            .background(Red.copy(alpha = 0.12f))
                            .border(1.dp, Red, UI.shapes.r4)
                            .padding(14.dp),
                    ) {
                        Column {
                            Text(
                                text = "Pattern matches nothing",
                                style = UI.typo.b1.style(
                                    color = UI.colors.pureInverse,
                                    fontWeight = FontWeight.ExtraBold,
                                ),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = if (queued == 0) {
                                    "This pattern can't even align the template's own " +
                                        "example message. Saving it won't convert " +
                                        "anything — tweak the chips first."
                                } else {
                                    "This pattern matches 0 of your $queued queued " +
                                        "message${if (queued == 1) "" else "s"}. Saving it " +
                                        "won't convert anything — tweak the chips first."
                                },
                                style = UI.typo.b2.style(
                                    color = UI.colors.gray,
                                    fontWeight = FontWeight.Medium,
                                ),
                            )
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                IvyOutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    text = "Keep editing",
                                    iconStart = null,
                                    onClick = {
                                        viewModel.onEvent(
                                            TemplateMappingEvent.DismissZeroAlignmentWarning,
                                        )
                                    },
                                )
                                IvyOutlinedButton(
                                    modifier = Modifier.weight(1f),
                                    text = "Save anyway",
                                    iconStart = null,
                                    borderColor = Red,
                                    textColor = Red,
                                    onClick = {
                                        viewModel.onEvent(
                                            TemplateMappingEvent.Save(
                                                explicitTemplateId = state.templateId
                                                    ?: fetchedTemplate?.id ?: templateId,
                                                saveAnyway = true,
                                            ),
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                // Post-save partial-success card. Only renders when the
                // reprocess routed SOME but not ALL of THIS template's own
                // pending items — the user reported "I mapped it,
                // transactions came in, but the template still says Needs
                // roles assigned". The card explains the split per reason,
                // and offers a bulk dismiss for the leftovers (dismiss only —
                // never blacklist a template that just routed messages).
                val converted = state.convertedFromQueue
                val failedTotal = state.failedTotal
                if (converted != null && failedTotal > 0) {
                    val total = state.totalOwn ?: (converted + failedTotal)
                    val reasons = listOfNotNull(
                        state.failedAlignment?.takeIf { it > 0 }
                            ?.let { "$it couldn't align" },
                        state.failedAmountParse?.takeIf { it > 0 }
                            ?.let { "$it amount unreadable" },
                        state.failedSenderNotLinked?.takeIf { it > 0 }
                            ?.let { "$it sender not linked" },
                        state.failedOther?.takeIf { it > 0 }
                            ?.let { "$it other" },
                    ).joinToString(", ")
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(UI.shapes.r4)
                            .background(Orange.copy(alpha = 0.12f))
                            .border(1.dp, Orange, UI.shapes.r4)
                            .padding(14.dp),
                    ) {
                        Column {
                            Text(
                                text = "Partially mapped",
                                style = UI.typo.b1.style(
                                    color = UI.colors.pureInverse,
                                    fontWeight = FontWeight.ExtraBold,
                                ),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                text = "$converted of $total for this pattern routed; " +
                                    "$failedTotal skipped: $reasons.",
                                style = UI.typo.b2.style(
                                    color = UI.colors.gray,
                                    fontWeight = FontWeight.Medium,
                                ),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Tweak a chip or mark a literal as variable, " +
                                    "then Save again to re-run on the leftovers.",
                                style = UI.typo.c.style(
                                    color = UI.colors.gray,
                                    fontWeight = FontWeight.Medium,
                                ),
                            )
                            Spacer(Modifier.height(10.dp))
                            IvyOutlinedButton(
                                modifier = Modifier.fillMaxWidth(),
                                text = "Dismiss these $failedTotal unmatched",
                                iconStart = null,
                                onClick = {
                                    viewModel.onEvent(
                                        TemplateMappingEvent.DismissUnmatched(
                                            explicitTemplateId = state.templateId
                                                ?: fetchedTemplate?.id ?: templateId,
                                        ),
                                    )
                                },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                IvyButton(
                    text = if (state.saving) "Saving…" else "Save",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canSave,
                    onClick = {
                        // Pass the screen's directly-fetched templateId so
                        // save() never has to depend on state.templateId,
                        // which can be null during the produceState seed
                        // race that previously caused "clicking Save does
                        // nothing".
                        viewModel.onEvent(
                            TemplateMappingEvent.Save(
                                explicitTemplateId = state.templateId ?: fetchedTemplate?.id ?: templateId,
                            ),
                        )
                    },
                )

                IvyOutlinedButton(
                    text = "Ignore this template forever",
                    iconStart = null,
                    borderColor = Red,
                    textColor = Red,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        // Dispatch BEFORE nav so the VM event reaches the
                        // alive instance — onIgnoreForever calls nav.back()
                        // which can clear the VM store under us.
                        viewModel.onEvent(
                            TemplateMappingEvent.IgnoreForever(
                                explicitTemplateId = state.templateId
                                    ?: fetchedTemplate?.id ?: templateId,
                            ),
                        )
                        onIgnoreForever()
                    },
                )

                ReprocessHistoricalAction(templateId = templateId)

                Spacer(Modifier.height(24.dp))
            }
        }

        // Role picker lives inside the outer Box so IvyModal — whose receiver
        // is BoxScope — can layer over the screen with the same animation,
        // scrim, and back-button behaviour the rest of the app uses (account
        // modal, unlink modal, etc.). Replaces the previous custom bottom
        // sheet, which felt foreign and where the user reported picks not
        // taking effect.
        val activeId = state.activeWildcard
        RoleMappingModal(
            visible = activeId != null,
            wildcardId = activeId,
            currentRoles = displayWildcards.associate { it.id to it.role },
            onChoose = { id, role ->
                viewModel.onEvent(TemplateMappingEvent.WildcardRoleChosen(id, role))
            },
            onClearToLiteral = { id, confirmed ->
                viewModel.onEvent(TemplateMappingEvent.WildcardClearedToLiteral(id, confirmed))
            },
            dismiss = { viewModel.onEvent(TemplateMappingEvent.DismissBottomSheet) },
        )
    }
}

/**
 * Renders the example SMS body as inline literals + wildcard chips, paired
 * to the template pattern via a 2-pointer alignment so each body token
 * carries the right metadata (positionInPattern for literals, slot id for
 * wildcards). Wildcard regions that consume multiple body tokens render
 * those tokens as separate chips all keyed to the same slot.
 *
 * Tappable behaviour:
 *   - Literal at a known pattern position → opens role picker for a NEW slot.
 *   - Wildcard chip → opens role picker for that existing slot.
 *   - Trailing body tokens beyond the pattern's end (rare, defensive) →
 *     non-tappable plain text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TokenizedExample(
    pattern: String,
    exampleBody: String,
    wildcards: List<WildcardChip>,
    onWildcardTap: (WildcardId) -> Unit,
    onLiteralTap: (positionInPattern: Int, token: String) -> Unit,
) {
    val tokens = remember(pattern, exampleBody, wildcards) {
        buildTokenList(pattern, exampleBody, wildcards)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(UI.shapes.r4)
            .background(UI.colors.medium)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        if (tokens.isEmpty()) {
            Text(
                text = "Loading template…",
                style = UI.typo.b2.style(
                    color = UI.colors.gray,
                    fontWeight = FontWeight.Medium,
                ),
            )
            return@Box
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tokens.forEach { token ->
                when (token) {
                    is Token.Literal -> LiteralChip(
                        text = token.text,
                        onClick = token.positionInPattern?.let { pos ->
                            { onLiteralTap(pos, token.text) }
                        },
                    )
                    is Token.Wild -> WildcardChipView(
                        text = token.text,
                        role = token.role,
                        onClick = { onWildcardTap(token.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun LiteralChip(text: String, onClick: (() -> Unit)? = null) {
    val baseModifier = Modifier.padding(vertical = 2.dp)
    val tapModifier = if (onClick != null) baseModifier.clickable(onClick = onClick) else baseModifier
    Text(
        text = text,
        style = UI.typo.b1.style(
            color = UI.colors.pureInverse,
            fontWeight = FontWeight.SemiBold,
        ),
        modifier = tapModifier,
    )
}

/**
 * Inline-style wildcard chip — same font as the surrounding literals, just
 * coloured by role and outlined. When mapped, the chip shows the role's
 * icon next to the value so the user gets immediate visual confirmation
 * that their pick took effect (separate from the color change, which can
 * be hard to spot at glance). Tapping anywhere on the chip re-opens the
 * picker.
 */
@Composable
private fun WildcardChipView(text: String, role: WildcardRole, onClick: () -> Unit) {
    val accent = colorForRole(role)
    val mapped = role !is WildcardRole.Unmapped
    val bg = if (mapped) accent.copy(alpha = 0.22f) else accent.copy(alpha = 0.10f)
    val fg = accent
    val borderColor = accent
    Row(
        modifier = Modifier
            .clip(UI.shapes.rFull)
            .background(bg)
            .border(width = 1.5.dp, color = borderColor, shape = UI.shapes.rFull)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mapped) {
            Icon(
                imageVector = iconForRole(role),
                contentDescription = null,
                tint = fg,
                modifier = Modifier
                    .padding(end = 6.dp)
                    .size(16.dp),
            )
        }
        Text(
            text = text,
            style = UI.typo.b1.style(
                color = fg,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

private sealed interface Token {
    /**
     * Literal body token. [positionInPattern] is non-null when the token
     * aligned to a literal position in the pattern — that's when the user
     * can tap to convert it to a wildcard. Null for trailing body tokens
     * that fell beyond the pattern (rare, defensive non-tappable case).
     */
    data class Literal(val text: String, val positionInPattern: Int?) : Token
    data class Wild(val text: String, val role: WildcardRole, val id: WildcardId) : Token
}

/**
 * Pattern-and-body alignment for the chip canvas. The canvas MUST agree with
 * what `extractWildcardValues` does at runtime on which body token belongs to
 * which slot — otherwise the user taps a "DateOnly" chip and gets the picker
 * for the "TimeOnly" slot (or both chips share an id because the screen
 * lumped them together).
 *
 * Primary path: [alignForDisplay] — the runtime aligner ITSELF (strict
 * adjacency + bounded backtracking), exposed in display shape. When it
 * aligns, every chip carries exactly the token routing would capture for
 * that slot, so canvas and routing can never disagree.
 *
 * Fallback (alignForDisplay returned null — the pattern can't align its own
 * example body, e.g. a broken/legacy template or mid-edit state where
 * routing matches nothing anyway): the old loose first-occurrence 2-pointer
 * walk, kept so the canvas still renders something tappable and the user can
 * repair the pattern.
 *
 * Body tokens beyond the pattern's end render as non-tappable literals
 * (positionInPattern = null) — that prevents creating a slot at an
 * out-of-range index if the user taps them.
 */
private fun buildTokenList(
    pattern: String,
    exampleBody: String,
    wildcards: List<WildcardChip>,
): List<Token> {
    val bodyTokens = exampleBody.split(Regex("\\s+")).filter { it.isNotBlank() }
    val patternTokens = pattern.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (bodyTokens.isEmpty()) return emptyList()
    if (patternTokens.isEmpty()) {
        return bodyTokens.map { Token.Literal(it, null) }
    }
    val slotByPosition = wildcards.associateBy { it.positionInPattern }

    val aligned = alignForDisplay(
        pattern = pattern,
        body = exampleBody,
        slots = wildcards.map {
            WildcardSlot(
                id = it.id,
                positionInPattern = it.positionInPattern,
                contextSnippet = "",
                exampleValue = it.exampleValue,
                role = it.role,
            )
        },
    )
    if (aligned != null) {
        val out = mutableListOf<Token>()
        for (tok in aligned) {
            out += when (tok) {
                is AlignedDisplayToken.Literal -> Token.Literal(tok.text, tok.positionInPattern)
                is AlignedDisplayToken.Wildcard -> {
                    val slot = slotByPosition[tok.positionInPattern]
                    if (slot != null) {
                        Token.Wild(tok.text, slot.role, slot.id)
                    } else {
                        // `<*>` position without a slot (degenerate pattern) —
                        // render read-only so a tap can't mint a bogus slot.
                        Token.Literal(tok.text, null)
                    }
                }
            }
        }
        // Trailing body tokens beyond the aligned prefix: non-tappable.
        for (i in aligned.size until bodyTokens.size) {
            out += Token.Literal(bodyTokens[i], null)
        }
        return out
    }

    val out = mutableListOf<Token>()
    var bodyIdx = 0
    var pIdx = 0
    while (pIdx < patternTokens.size) {
        val pTok = patternTokens[pIdx]
        if (pTok != WILDCARD_TOKEN) {
            if (bodyIdx < bodyTokens.size) {
                out.add(Token.Literal(bodyTokens[bodyIdx], pIdx))
                bodyIdx += 1
            }
            pIdx += 1
            continue
        }

        // Collect the wildcard run.
        val runStart = pIdx
        var runEnd = pIdx
        while (runEnd + 1 < patternTokens.size &&
            patternTokens[runEnd + 1] == WILDCARD_TOKEN
        ) {
            runEnd += 1
        }
        val runLength = runEnd - runStart + 1

        // Find where this run ends in the body (the next pattern literal's
        // first occurrence).
        val nextLitIdx = (runEnd + 1 until patternTokens.size).firstOrNull {
            patternTokens[it] != WILDCARD_TOKEN
        }
        val regionEnd = if (nextLitIdx != null) {
            val nextLit = patternTokens[nextLitIdx]
            val matched = (bodyIdx until bodyTokens.size).firstOrNull {
                bodyTokens[it].equals(nextLit, ignoreCase = true)
            }
            // Frankenstein guard: pattern's next literal not in body → cap
            // the run to its expected size so a single wildcard doesn't
            // swallow the whole sentence on broken patterns.
            matched ?: minOf(bodyIdx + runLength, bodyTokens.size)
        } else {
            bodyTokens.size
        }

        // 1-per-slot distribution. Body tokens beyond runLength land on the
        // last slot (so its chip count grows but slot ids stay distinct).
        val available = bodyTokens.subList(bodyIdx, regionEnd)
        for (i in available.indices) {
            val slotPos = if (i < runLength) runStart + i else runEnd
            val slot = slotByPosition[slotPos]
            if (slot != null) {
                out.add(Token.Wild(available[i], slot.role, slot.id))
            } else {
                out.add(Token.Literal(available[i], null))
            }
        }
        bodyIdx = regionEnd
        pIdx = runEnd + 1
    }

    while (bodyIdx < bodyTokens.size) {
        out.add(Token.Literal(bodyTokens[bodyIdx], null))
        bodyIdx += 1
    }
    return out
}

/**
 * Live progress while [MapTemplateUseCase] drains the pending queue. Always
 * shows numbers ("3 of 17") because that's the project-wide rule the user
 * asked for: "always progress bar with numbers".
 */
@Composable
private fun ReprocessProgressCard(progress: ReprocessProgress) {
    val ratio = if (progress.total > 0) {
        progress.processed.toFloat() / progress.total.toFloat()
    } else {
        0f
    }
    val animatedRatio by androidx.compose.animation.core.animateFloatAsState(
        targetValue = ratio.coerceIn(0f, 1f),
        animationSpec = androidx.compose.animation.core.tween(
            durationMillis = 400,
            easing = androidx.compose.animation.core.LinearEasing,
        ),
        label = "reprocessProgressBar",
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
                text = "Reprocessing pending…",
                style = UI.typo.b1.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.ExtraBold,
                ),
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "${progress.processed} / ${progress.total}",
                style = UI.typo.c.style(
                    color = UI.colors.gray,
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
        Spacer(Modifier.height(10.dp))
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
        Spacer(Modifier.height(8.dp))
        Text(
            text = "${progress.converted} new transaction${if (progress.converted == 1) "" else "s"} created so far",
            style = UI.typo.c.style(
                color = UI.colors.gray,
                fontWeight = FontWeight.SemiBold,
            ),
        )
    }
}

private fun labelFor(role: WildcardRole): String? = when (role) {
    WildcardRole.Unmapped -> null
    WildcardRole.Income -> "Income"
    WildcardRole.Expense -> "Expense"
    WildcardRole.Transfer -> "Transfer"
    WildcardRole.CurrentTotal -> "Balance"
    WildcardRole.TransactionFee -> "Fee"
    WildcardRole.DateFull -> "Date+Time"
    WildcardRole.DateOnly -> "Date"
    WildcardRole.TimeOnly -> "Time"
    WildcardRole.Merchant -> "Merchant"
    WildcardRole.Ignored -> null
}

private data class RoleTile(val role: WildcardRole, val title: String)

private data class RoleCategory(val title: String, val tiles: List<RoleTile>)

private val ROLE_CATEGORIES = listOf(
    RoleCategory(
        title = "Amount",
        tiles = listOf(
            RoleTile(WildcardRole.Income, "Income"),
            RoleTile(WildcardRole.Expense, "Expense"),
            RoleTile(WildcardRole.Transfer, "Transfer"),
        ),
    ),
    RoleCategory(
        title = "Date / Time",
        tiles = listOf(
            RoleTile(WildcardRole.DateFull, "Date + Time"),
            RoleTile(WildcardRole.DateOnly, "Date only"),
            RoleTile(WildcardRole.TimeOnly, "Time only"),
        ),
    ),
    RoleCategory(
        title = "Other",
        tiles = listOf(
            RoleTile(WildcardRole.CurrentTotal, "Balance"),
            RoleTile(WildcardRole.TransactionFee, "Fee"),
            RoleTile(WildcardRole.Merchant, "Merchant"),
            RoleTile(WildcardRole.Ignored, "Ignored"),
        ),
    ),
)

/**
 * Role picker rendered through the project's standard `IvyModal`. The 10
 * possible roles overflowed the previous single-column list, so the user
 * couldn't see all of them without scrolling and the modal felt cramped.
 * Now grouped into three categories (Amount / Date+Time / Other) with each
 * category laid out as a 2-column grid of compact icon + label tiles. The
 * picker fits the modal at all common screen sizes.
 *
 * `wildcardId` becomes null while the modal is animating out, so we hold the
 * LAST id the user tapped via `remember(wildcardId)` and use it for the
 * choose callback — that's how a pick survives the dismiss animation.
 */
@Composable
private fun BoxScope.RoleMappingModal(
    visible: Boolean,
    wildcardId: WildcardId?,
    currentRoles: Map<WildcardId, WildcardRole>,
    onChoose: (WildcardId, WildcardRole) -> Unit,
    onClearToLiteral: (WildcardId, Boolean) -> Unit,
    dismiss: () -> Unit,
) {
    val lastWildcardId = remember(wildcardId) {
        mutableStateOf(wildcardId)
    }
    if (wildcardId != null) lastWildcardId.value = wildcardId
    val effectiveId = lastWildcardId.value
    val modalId = remember(effectiveId) { UUID.randomUUID() }
    val currentRole = effectiveId?.let { currentRoles[it] } ?: WildcardRole.Unmapped

    IvyModal(
        id = modalId,
        visible = visible,
        dismiss = dismiss,
        PrimaryAction = {
            IvyButton(
                text = "Done",
                onClick = dismiss,
            )
        },
    ) {
        Spacer(Modifier.height(32.dp))

        ModalTitle(text = "What does this part represent?")

        Spacer(Modifier.height(16.dp))

        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ROLE_CATEGORIES.forEach { category ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = category.title.uppercase(),
                        style = UI.typo.c.style(
                            color = UI.colors.gray,
                            fontWeight = FontWeight.Bold,
                        ),
                    )
                    RoleTileGrid(
                        tiles = category.tiles,
                        currentRole = currentRole,
                        onPick = { picked ->
                            if (effectiveId != null) {
                                onChoose(effectiveId, picked)
                            }
                            dismiss()
                        },
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Escape hatch: "I tapped a literal by mistake" or "this isn't
            // really variable, ignore my pick". Removes the slot and puts
            // the literal token back in the pattern at that position.
            // Clearing a slot that already carries a role is destructive
            // (the role is lost and the example value gets baked into the
            // pattern as a literal — this silently bricked templates in the
            // field), so it takes a second confirming tap.
            var confirmClear by remember(effectiveId) { mutableStateOf(false) }
            val holdsRole = currentRole != WildcardRole.Unmapped
            IvyOutlinedButton(
                text = if (holdsRole && confirmClear) {
                    "Tap again to remove the ${labelFor(currentRole) ?: "mapped"} role"
                } else {
                    "Make this part literal again"
                },
                iconStart = null,
                borderColor = if (holdsRole && confirmClear) Red else UI.colors.gray,
                textColor = if (holdsRole && confirmClear) Red else UI.colors.pureInverse,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    when {
                        effectiveId == null -> dismiss()
                        holdsRole && !confirmClear -> confirmClear = true
                        else -> {
                            onClearToLiteral(effectiveId, holdsRole)
                            dismiss()
                        }
                    }
                },
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 2-column grid of role tiles. Uses `chunked(2)` + `Row` rather than
 * LazyVerticalGrid because the modal's parent already manages scrolling
 * and a Lazy layout can't measure inside a wrap-content modal column.
 */
@Composable
private fun RoleTileGrid(
    tiles: List<RoleTile>,
    currentRole: WildcardRole,
    onPick: (WildcardRole) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { tile ->
                    RoleTileGridCell(
                        tile = tile,
                        selected = currentRole == tile.role,
                        modifier = Modifier.weight(1f),
                        onClick = { onPick(tile.role) },
                    )
                }
                // Pad odd rows so the trailing tile keeps its half-width.
                if (pair.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun RoleTileGridCell(
    tile: RoleTile,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = colorForRole(tile.role)
    val bg = if (selected) accent else UI.colors.medium
    val fg = if (selected) Color.White else UI.colors.pureInverse

    Row(
        modifier = modifier
            .clip(UI.shapes.r4)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = iconForRole(tile.role),
            contentDescription = null,
            tint = if (selected) Color.White else accent,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = tile.title,
            style = UI.typo.b2.style(
                color = fg,
                fontWeight = FontWeight.ExtraBold,
            ),
        )
    }
}
