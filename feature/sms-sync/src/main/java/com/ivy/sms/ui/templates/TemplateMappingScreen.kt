package com.ivy.sms.ui.templates

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import com.ivy.navigation.navigation
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.ui.directionFor
import com.ivy.sms.ui.theme.colorForRole
import com.ivy.wallet.ui.theme.Red
import com.ivy.wallet.ui.theme.components.BackButtonType
import com.ivy.wallet.ui.theme.components.IvyBasicTextField
import com.ivy.wallet.ui.theme.components.IvyButton
import com.ivy.wallet.ui.theme.components.IvyOutlinedButton
import com.ivy.wallet.ui.theme.components.IvyToolbar

@Composable
fun TemplateMappingScreen(
    templateId: SmsTemplateId,
    onSaved: (Int) -> Unit,
    onIgnoreForever: () -> Unit = {},
    viewModel: TemplateMappingViewModel = viewModel(),
) {
    val state = viewModel.uiState()
    val nav = navigation()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(templateId) { viewModel.load(templateId) }

    // Belt-and-braces: fetch the template directly from the repo on every
    // entry too. If the VM-side load is shadowed by a lifecycle race the
    // screen still ends up with data. seedFromScreen is idempotent.
    LaunchedEffect(templateId) {
        try {
            val ep = dagger.hilt.android.EntryPointAccessors.fromApplication(
                context.applicationContext,
                TemplateLookupEntryPoint::class.java,
            )
            val template = ep.templateRepo().findById(templateId).getOrNull()
            if (template != null) {
                viewModel.seedFromScreen(template)
            }
        } catch (t: Throwable) {
            timber.log.Timber.e(t, "TemplateMapping screen-fallback load failed")
        }
    }

    state.convertedFromQueue?.let {
        LaunchedEffect(it) { onSaved(it) }
    }

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
                Text(
                    text = "Map template",
                    style = UI.typo.h2.style(
                        color = UI.colors.pureInverse,
                        fontWeight = FontWeight.ExtraBold,
                    ),
                )
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
                    text = "Tap a colored chip and pick what it represents.",
                    style = UI.typo.b2.style(
                        color = UI.colors.gray,
                        fontWeight = FontWeight.Medium,
                    ),
                )

                CompositionLocalProvider(LocalLayoutDirection provides directionFor(state.exampleBody)) {
                    TokenizedExample(
                        exampleBody = state.exampleBody,
                        pattern = state.pattern,
                        wildcards = state.wildcards,
                        onWildcardTap = { id ->
                            viewModel.onEvent(TemplateMappingEvent.WildcardTapped(id))
                        },
                    )
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

                val canSave = state.wildcards.any {
                    it.role == WildcardRole.Income ||
                        it.role == WildcardRole.Expense ||
                        it.role == WildcardRole.Transfer
                } && !state.saving

                Spacer(Modifier.height(8.dp))
                IvyButton(
                    text = if (state.saving) "Saving…" else "Save",
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canSave,
                    onClick = { viewModel.onEvent(TemplateMappingEvent.Save) },
                )

                IvyOutlinedButton(
                    text = "Ignore this template forever",
                    iconStart = null,
                    borderColor = Red,
                    textColor = Red,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onIgnoreForever,
                )

                ReprocessHistoricalAction(templateId = templateId)

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    val activeId = state.activeWildcard
    if (activeId != null) {
        WildcardMappingBottomSheet(
            wildcardId = activeId,
            currentRoles = state.wildcards.associate { it.id to it.role },
            onChoose = { role ->
                viewModel.onEvent(TemplateMappingEvent.WildcardRoleChosen(activeId, role))
            },
            onDismiss = { viewModel.onEvent(TemplateMappingEvent.DismissBottomSheet) },
        )
    }
}

/**
 * Renders the SMS body as a FlowRow of per-token chips. Each wildcard slot is its
 * own tappable chip with the role's accent color; literals are plain text. This
 * decouples rendering and tap detection from AnnotatedString quirks (the previous
 * BasicText + character-offset tap approach showed an empty box on some devices).
 *
 * Falls back to rendering the pattern itself when the example body is empty —
 * legacy templates from earlier schemas may not have one stored.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TokenizedExample(
    exampleBody: String,
    pattern: String,
    wildcards: List<WildcardChip>,
    onWildcardTap: (WildcardId) -> Unit,
) {
    val tokens = remember(exampleBody, pattern, wildcards) {
        buildTokenList(exampleBody, pattern, wildcards)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(UI.shapes.r4)
            .background(UI.colors.medium)
            .padding(horizontal = 14.dp, vertical = 14.dp),
    ) {
        if (tokens.isEmpty()) {
            // Pattern AND example body both empty — likely the template hasn't
            // finished loading yet, or it was created with an unusable SMS
            // body. Either way show a neutral hint rather than a scary error.
            Text(
                text = if (pattern.isBlank() && exampleBody.isBlank()) {
                    "Loading template…"
                } else {
                    "Tap Save to use the default roles, or run a Sync from the wallet to refresh this template."
                },
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
                    is Token.Literal -> LiteralChip(token.text)
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
private fun LiteralChip(text: String) {
    Text(
        text = text,
        style = UI.typo.b2.style(
            color = UI.colors.pureInverse,
            fontWeight = FontWeight.Medium,
        ),
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun WildcardChipView(text: String, role: WildcardRole, onClick: () -> Unit) {
    val accent = colorForRole(role)
    val mapped = role !is WildcardRole.Unmapped
    val bg = if (mapped) accent else accent.copy(alpha = 0.16f)
    val fg = if (mapped) Color.White else accent
    val borderColor = if (mapped) accent else accent.copy(alpha = 0.5f)
    Row(
        modifier = Modifier
            .clip(UI.shapes.rFull)
            .background(bg)
            .border(width = 1.dp, color = borderColor, shape = UI.shapes.rFull)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text.ifBlank { "tap" },
            style = UI.typo.c.style(
                color = fg,
                fontWeight = FontWeight.Bold,
            ),
        )
        val label = labelFor(role)
        if (label != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = UI.typo.c.style(
                    color = fg.copy(alpha = 0.85f),
                    fontWeight = FontWeight.SemiBold,
                ),
            )
        }
    }
}

private sealed interface Token {
    data class Literal(val text: String) : Token
    data class Wild(val text: String, val role: WildcardRole, val id: WildcardId) : Token
}

/**
 * Walks the pattern and the example body in lockstep using the same alignment as
 * the runtime extractor, producing a flat list of either literal tokens or wildcard
 * chips. Falls back to rendering the pattern's own tokens when no example body
 * exists, so the user always has something to tap.
 */
private fun buildTokenList(
    exampleBody: String,
    pattern: String,
    wildcards: List<WildcardChip>,
): List<Token> {
    val patternTokens = pattern.split(Regex("\\s+")).filter { it.isNotBlank() }
    val bodyTokens = exampleBody.split(Regex("\\s+")).filter { it.isNotBlank() }

    // Both empty → caller (TokenizedExample) will render a "loading" placeholder.
    if (patternTokens.isEmpty() && bodyTokens.isEmpty()) return emptyList()

    // Pattern missing but body present → render the body verbatim as plain literals.
    // This unblocks templates whose pattern column is blank in the DB (legacy /
    // backup-restored rows) so the user at least sees the SMS text.
    if (patternTokens.isEmpty()) {
        return bodyTokens.map { Token.Literal(it) }
    }
    val byPosition = wildcards.associateBy { it.positionInPattern }

    if (bodyTokens.isEmpty()) {
        // No example body — render the pattern shape itself so the user can still
        // tap wildcard slots.
        return patternTokens.mapIndexed { idx, ptok ->
            if (ptok == com.ivy.sms.data.WILDCARD_TOKEN) {
                val chip = byPosition[idx]
                if (chip != null) {
                    Token.Wild(chip.exampleValue.ifBlank { "•••" }, chip.role, chip.id)
                } else {
                    Token.Literal("•••")
                }
            } else {
                Token.Literal(ptok)
            }
        }
    }

    val out = mutableListOf<Token>()
    var bodyIdx = 0
    for ((patternIdx, ptok) in patternTokens.withIndex()) {
        if (ptok != com.ivy.sms.data.WILDCARD_TOKEN) {
            val match = (bodyIdx until bodyTokens.size).firstOrNull {
                bodyTokens[it].equals(ptok, ignoreCase = true)
            }
            if (match == null) {
                out.add(Token.Literal(ptok))
                continue
            }
            while (bodyIdx <= match) {
                out.add(Token.Literal(bodyTokens[bodyIdx]))
                bodyIdx++
            }
        } else {
            val chip = byPosition[patternIdx]
            val nextLiteralPattern = (patternIdx + 1 until patternTokens.size).firstOrNull {
                patternTokens[it] != com.ivy.sms.data.WILDCARD_TOKEN
            }
            val stopAt = if (nextLiteralPattern == null) bodyTokens.size else {
                val literal = patternTokens[nextLiteralPattern]
                (bodyIdx until bodyTokens.size).firstOrNull {
                    bodyTokens[it].equals(literal, ignoreCase = true)
                } ?: bodyTokens.size
            }
            val captured = if (bodyIdx < stopAt) {
                bodyTokens.subList(bodyIdx, stopAt).joinToString(" ")
            } else {
                "•••"
            }
            if (chip != null) {
                out.add(Token.Wild(captured, chip.role, chip.id))
            } else {
                out.add(Token.Literal(captured))
            }
            bodyIdx = stopAt
        }
    }
    while (bodyIdx < bodyTokens.size) {
        out.add(Token.Literal(bodyTokens[bodyIdx]))
        bodyIdx++
    }
    return out
}

private fun labelFor(role: WildcardRole): String? = when (role) {
    WildcardRole.Unmapped -> "tap"
    WildcardRole.Income -> "Income"
    WildcardRole.Expense -> "Expense"
    WildcardRole.Transfer -> "Transfer"
    WildcardRole.CurrentTotal -> "Balance"
    WildcardRole.TransactionFee -> "Fee"
    WildcardRole.DateFull -> "Date+Time"
    WildcardRole.DateOnly -> "Date"
    WildcardRole.TimeOnly -> "Time"
    WildcardRole.Merchant -> "Merchant"
    WildcardRole.Ignored -> "—"
}
