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
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.ui.directionFor
import com.ivy.sms.ui.theme.colorForRole
import com.ivy.sms.ui.theme.iconForRole
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
    viewModel: TemplateMappingViewModel = viewModel(),
) {
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

    state.convertedFromQueue?.let {
        LaunchedEffect(it) { onSaved(it) }
    }

    // Render data: pattern + body come from the VM state if populated, else
    // from the directly-fetched template (so the user always sees the SMS
    // even when the VM is racing). Wildcard ROLES, however, ALWAYS come from
    // state.rolesByWildcardId — that map is the single source of truth for
    // the user's picks. Reading from state.wildcards used to drop picks made
    // before applyTemplate ran, which the user hit as "I picked Expense and
    // the chip is still grey, save is still dimmed".
    val displayPattern = state.pattern.ifBlank { fetchedTemplate?.pattern.orEmpty() }
    val displayBody = state.exampleBody.ifBlank { fetchedTemplate?.exampleBody.orEmpty() }
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
    val isLoading = fetchedTemplate == null && state.pattern.isBlank() && state.exampleBody.isBlank()

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
                            pattern = displayPattern,
                            wildcards = displayWildcards,
                            onWildcardTap = { id ->
                                viewModel.onEvent(TemplateMappingEvent.WildcardTapped(id))
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
            dismiss = { viewModel.onEvent(TemplateMappingEvent.DismissBottomSheet) },
        )
    }
}

/**
 * Renders the template as an inline read of literals + wildcard chips, walking
 * ONLY the pattern. Each wildcard slot becomes exactly one chip whose text is
 * the slot's captured `exampleValue` — no body-token alignment, no multi-word
 * smushing, no "•••" placeholder. Literals and chips share the same font
 * scale (UI.typo.b1) so the line reads as one continuous sentence with the
 * variable parts colored, not as oversized buttons interrupting plain text.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TokenizedExample(
    pattern: String,
    wildcards: List<WildcardChip>,
    onWildcardTap: (WildcardId) -> Unit,
) {
    val tokens = remember(pattern, wildcards) {
        buildTokenList(pattern, wildcards)
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
        style = UI.typo.b1.style(
            color = UI.colors.pureInverse,
            fontWeight = FontWeight.SemiBold,
        ),
        modifier = Modifier.padding(vertical = 2.dp),
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
    data class Literal(val text: String) : Token
    data class Wild(val text: String, val role: WildcardRole, val id: WildcardId) : Token
}

/**
 * Pattern-only walk: each pattern token becomes either a literal or a chip.
 * Slots whose `exampleValue` contains no digit are demoted to literals —
 * Drain merges occasionally mark common Arabic prepositions like "كل" or
 * "من" as wildcards because their position varies across messages, but
 * the user never wants to map them as Income/Expense/Date roles. Numeric
 * slots (amounts, balances, dates, refcodes) stay as tappable chips.
 */
private fun buildTokenList(
    pattern: String,
    wildcards: List<WildcardChip>,
): List<Token> {
    val patternTokens = pattern.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (patternTokens.isEmpty()) return emptyList()
    val byPosition = wildcards.associateBy { it.positionInPattern }
    val out = mutableListOf<Token>()
    for ((idx, ptok) in patternTokens.withIndex()) {
        if (ptok == com.ivy.sms.data.WILDCARD_TOKEN) {
            val chip = byPosition[idx] ?: continue
            val example = chip.exampleValue.trim()
            if (example.isBlank()) {
                // No captured value → fall back to the role label if mapped,
                // skip the slot entirely if not (avoids empty pills).
                val fallback = labelFor(chip.role).orEmpty()
                if (fallback.isBlank()) continue
                out.add(Token.Wild(fallback, chip.role, chip.id))
                continue
            }
            if (containsDigit(example)) {
                out.add(Token.Wild(example, chip.role, chip.id))
            } else {
                // Non-digit "wildcard" — Drain quirk, render as literal so the
                // SMS reads naturally and the chip count stays meaningful.
                out.add(Token.Literal(example))
            }
        } else {
            out.add(Token.Literal(ptok))
        }
    }
    return out
}

private fun containsDigit(text: String): Boolean = text.any { ch ->
    ch.isDigit() || ch in '٠'..'٩' || ch in '۰'..'۹'
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

private data class RoleTile(
    val role: WildcardRole,
    val title: String,
    val subtitle: String,
)

private val ROLE_TILES = listOf(
    RoleTile(WildcardRole.Income, "Income", "amount you received"),
    RoleTile(WildcardRole.Expense, "Expense", "amount you spent"),
    RoleTile(WildcardRole.Transfer, "Transfer", "amount moved between wallets"),
    RoleTile(WildcardRole.CurrentTotal, "Current Total", "running balance after this txn"),
    RoleTile(WildcardRole.TransactionFee, "Transaction Fee", "fee charged"),
    RoleTile(WildcardRole.DateFull, "Date + Time", "full date and time, e.g. 28/04/2026 10:30"),
    RoleTile(WildcardRole.DateOnly, "Date only", "date without time, e.g. 28/04/2026"),
    RoleTile(WildcardRole.TimeOnly, "Time only", "time without date, e.g. 10:30"),
    RoleTile(WildcardRole.Merchant, "Merchant", "free text added to the description"),
    RoleTile(WildcardRole.Ignored, "Ignored", "skip this part of the message"),
)

/**
 * Role picker rendered through the project's standard `IvyModal`. Same look,
 * feel, and back-button behaviour as the unlink-confirmation modal in
 * `WalletSmsConfigScreen` and the legacy account/category modals — replaces
 * the previous custom bottom sheet, which felt foreign and where the user
 * reported their pick "doesn't take effect".
 *
 * `wildcardId` is null while the modal is animating out so we still need to
 * hold a ref to the LAST id the user tapped — captured in [lastWildcardId]
 * via `rememberUpdatedState`-style `remember(wildcardId)` so the choose
 * callback always fires for the right slot even after the modal vanishes.
 */
@Composable
private fun BoxScope.RoleMappingModal(
    visible: Boolean,
    wildcardId: WildcardId?,
    currentRoles: Map<WildcardId, WildcardRole>,
    onChoose: (WildcardId, WildcardRole) -> Unit,
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

        Spacer(Modifier.height(8.dp))

        Text(
            modifier = Modifier.padding(horizontal = 32.dp),
            text = "Tap a role to map this segment. Picking a duplicate amount or date role replaces the previous one.",
            style = UI.typo.b2.style(
                color = UI.colors.gray,
                fontWeight = FontWeight.Medium,
            ),
        )

        Spacer(Modifier.height(20.dp))

        Column(
            modifier = Modifier.padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ROLE_TILES.forEach { tile ->
                RoleTileRow(
                    tile = tile,
                    selected = currentRole == tile.role,
                    onClick = {
                        if (effectiveId != null) {
                            onChoose(effectiveId, tile.role)
                        }
                        dismiss()
                    },
                )
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RoleTileRow(
    tile: RoleTile,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = colorForRole(tile.role)
    val bg = if (selected) accent else UI.colors.medium
    val fg = if (selected) Color.White else UI.colors.pureInverse
    val subFg = if (selected) Color.White.copy(alpha = 0.85f) else UI.colors.gray

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(UI.shapes.r4)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = iconForRole(tile.role),
            contentDescription = null,
            tint = if (selected) Color.White else accent,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.padding(end = 8.dp)) {
            Text(
                text = tile.title,
                style = UI.typo.b2.style(
                    color = fg,
                    fontWeight = FontWeight.ExtraBold,
                ),
            )
            Text(
                text = tile.subtitle,
                style = UI.typo.c.style(
                    color = subFg,
                    fontWeight = FontWeight.Medium,
                ),
            )
        }
    }
}
