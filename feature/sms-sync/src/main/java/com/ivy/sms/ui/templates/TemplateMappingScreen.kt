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
                            exampleBody = displayBody,
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

                state.reprocess?.let { p ->
                    ReprocessProgressCard(progress = p)
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
 * Renders the FULL example SMS body as inline literals + wildcard chips. Walks
 * the body (not the pattern) because Drain merges shed non-shared tokens from
 * the pattern, which made the on-screen message look truncated even on a
 * tall phone. Each digit-bearing body token becomes a tappable chip paired
 * with a slot in pattern-position order; non-digit tokens stay as plain
 * literal text. The user sees the entire SMS, no cropping.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TokenizedExample(
    exampleBody: String,
    wildcards: List<WildcardChip>,
    onWildcardTap: (WildcardId) -> Unit,
) {
    val tokens = remember(exampleBody, wildcards) {
        buildTokenList(exampleBody, wildcards)
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
 * Body-only walk: every body token becomes either a literal or a tappable
 * chip. Digit-bearing tokens (Latin / Arabic-Indic / Eastern Arabic-Indic)
 * are paired with the template's wildcard slots in pattern-position order —
 * so the FIRST digit token in the body maps to the slot at the smallest
 * pattern position, the second to the next, and so on. This keeps the FULL
 * SMS visible (the previous pattern-walk dropped any token Drain had merged
 * away, which the user reported as the message being "cropped"), while still
 * giving every amount / balance / date its own role-coloured chip.
 */
private fun buildTokenList(
    exampleBody: String,
    wildcards: List<WildcardChip>,
): List<Token> {
    val bodyTokens = exampleBody.split(Regex("\\s+")).filter { it.isNotBlank() }
    if (bodyTokens.isEmpty()) return emptyList()
    val digitSlots = wildcards
        .filter { containsDigit(it.exampleValue) }
        .sortedBy { it.positionInPattern }
    val slotIter = digitSlots.iterator()
    return bodyTokens.map { tok ->
        if (containsDigit(tok)) {
            if (slotIter.hasNext()) {
                val slot = slotIter.next()
                Token.Wild(tok, slot.role, slot.id)
            } else {
                // More digit tokens in the body than digit slots in the
                // template (e.g., a phone number stuck at the end). Render
                // it as plain text so the message stays readable.
                Token.Literal(tok)
            }
        } else {
            Token.Literal(tok)
        }
    }
}

private fun containsDigit(text: String): Boolean = text.any { ch ->
    ch.isDigit() || ch in '٠'..'٩' || ch in '۰'..'۹'
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
