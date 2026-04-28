package com.ivy.sms.ui.templates

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Button
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.BasicText
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TransactionClassification
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardMapping
import java.util.UUID

private const val WILDCARD_TAG = "WILDCARD"
private const val WILDCARD_LABEL = "<*>"

@Composable
fun TemplateMappingScreen(
    templateId: SmsTemplateId,
    onSaved: (Int) -> Unit,
    viewModel: TemplateMappingViewModel = viewModel(),
) {
    val state = viewModel.uiState()

    LaunchedEffect(templateId) {
        viewModel.load(templateId)
    }
    state.convertedFromQueue?.let {
        LaunchedEffect(it) { onSaved(it) }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = "Tap each highlighted <*> in the message and tell us what it is.")

        TappablePattern(
            pattern = state.pattern,
            wildcards = state.wildcards,
            onWildcardTap = { id -> viewModel.onEvent(TemplateMappingEvent.WildcardTapped(id)) },
        )

        Spacer(Modifier.height(8.dp))
        ClassificationPicker(
            current = state.classification,
            onChoose = { viewModel.onEvent(TemplateMappingEvent.ClassificationChosen(it)) },
        )

        state.error?.let { Text(text = it, color = Color(0xFFB00020)) }

        val canSave = state.classification != null &&
            state.wildcards.any { it.mapping == WildcardMapping.Amount } &&
            !state.saving

        Button(
            onClick = { viewModel.onEvent(TemplateMappingEvent.Save) },
            enabled = canSave,
        ) {
            Text(if (state.saving) "Saving…" else "Save")
        }

        ReprocessHistoricalAction(templateId = templateId)

        val activeId = state.activeWildcard
        if (activeId != null) {
            WildcardMappingBottomSheet(
                wildcardId = activeId,
                currentMappings = state.wildcards.associate { it.id to it.mapping },
                onChoose = { mapping ->
                    viewModel.onEvent(TemplateMappingEvent.WildcardMappingChosen(activeId, mapping))
                },
                onDismiss = { viewModel.onEvent(TemplateMappingEvent.DismissBottomSheet) },
            )
        }
    }
}

@Composable
private fun TappablePattern(
    pattern: String,
    wildcards: List<WildcardChip>,
    onWildcardTap: (WildcardId) -> Unit,
) {
    val annotated = remember(pattern, wildcards) { annotatedFor(pattern, wildcards) }
    var layoutResult by remember(annotated) { mutableStateOf<TextLayoutResult?>(null) }

    BasicText(
        text = annotated,
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(annotated) {
                detectTapGestures { offset ->
                    val pos = layoutResult?.getOffsetForPosition(offset) ?: return@detectTapGestures
                    val ann = annotated.getStringAnnotations(WILDCARD_TAG, pos, pos).firstOrNull()
                        ?: return@detectTapGestures
                    val id = runCatching { WildcardId(UUID.fromString(ann.item)) }.getOrNull()
                        ?: return@detectTapGestures
                    onWildcardTap(id)
                }
            },
        onTextLayout = { layoutResult = it },
    )
}

private fun annotatedFor(pattern: String, wildcards: List<WildcardChip>): AnnotatedString {
    val tokens = pattern.split(Regex("\\s+")).filter { it.isNotBlank() }
    val byPosition = wildcards.associateBy { it.positionInPattern }
    return buildAnnotatedString {
        for ((idx, tok) in tokens.withIndex()) {
            if (idx > 0) append(' ')
            val chip = byPosition[idx]
            if (tok == WILDCARD_LABEL && chip != null) {
                pushStringAnnotation(WILDCARD_TAG, chip.id.value.toString())
                withStyle(
                    SpanStyle(
                        color = colorForMapping(chip.mapping),
                        fontWeight = FontWeight.Bold,
                    ),
                ) {
                    append(labelFor(chip.mapping))
                }
                pop()
            } else {
                append(tok)
            }
        }
    }
}

private fun colorForMapping(m: WildcardMapping): Color = when (m) {
    WildcardMapping.Unmapped -> Color(0xFF1565C0)
    WildcardMapping.Amount -> Color(0xFF2E7D32)
    WildcardMapping.Merchant -> Color(0xFF6A1B9A)
    WildcardMapping.DateTime -> Color(0xFFEF6C00)
    WildcardMapping.Reference -> Color(0xFF455A64)
    WildcardMapping.Ignored -> Color(0xFF9E9E9E)
}

private fun labelFor(m: WildcardMapping): String = when (m) {
    WildcardMapping.Unmapped -> WILDCARD_LABEL
    WildcardMapping.Amount -> "[Amount]"
    WildcardMapping.Merchant -> "[Merchant]"
    WildcardMapping.DateTime -> "[Date]"
    WildcardMapping.Reference -> "[Ref]"
    WildcardMapping.Ignored -> "[—]"
}

@Composable
private fun ClassificationPicker(
    current: TransactionClassification?,
    onChoose: (TransactionClassification) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TransactionClassification.entries.forEach { c ->
            Row(
                modifier = Modifier
                    .selectable(
                        selected = current == c,
                        onClick = { onChoose(c) },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = current == c, onClick = { onChoose(c) })
                Spacer(Modifier.height(0.dp))
                Text(c.name)
            }
        }
    }
}
