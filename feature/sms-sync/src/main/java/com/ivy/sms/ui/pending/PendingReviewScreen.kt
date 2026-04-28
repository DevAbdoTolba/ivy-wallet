package com.ivy.sms.ui.pending

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ivy.navigation.TemplateMappingScreen
import com.ivy.navigation.navigation

@Composable
fun PendingReviewScreen(
    viewModel: PendingReviewViewModel = viewModel(),
) {
    val state = viewModel.uiState()
    val nav = navigation()

    if (state.items.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No items to review.")
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.items, key = { it.id }) { row ->
            PendingItemCard(
                row = row,
                onToggleExpand = { viewModel.onEvent(PendingReviewEvent.ToggleExpand(row.id)) },
                onMapTemplate = {
                    nav.navigateTo(TemplateMappingScreen(row.templateId.value.toString()))
                },
                onDismiss = { viewModel.onEvent(PendingReviewEvent.Dismiss(row.itemId)) },
                onBlacklist = { viewModel.onEvent(PendingReviewEvent.Blacklist(row.templateId)) },
            )
        }
    }
}

@Composable
private fun PendingItemCard(
    row: PendingItemRowViewState,
    onToggleExpand: () -> Unit,
    onMapTemplate: () -> Unit,
    onDismiss: () -> Unit,
    onBlacklist: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AssistChip(
                    onClick = {},
                    label = { Text(row.senderId) },
                    colors = AssistChipDefaults.assistChipColors(),
                )
                Text(text = " — ${row.reason}", modifier = Modifier.padding(start = 8.dp))
            }
            Text(
                text = if (row.expanded) row.body else row.body.take(160) + if (row.body.length > 160) "…" else "",
                modifier = Modifier
                    .fillMaxWidth(),
            )
            TextButton(onClick = onToggleExpand) {
                Text(if (row.expanded) "Collapse" else "Show full SMS")
            }
            Text(text = "Discovered template: ${row.templatePattern}")
            Row {
                TextButton(onClick = onMapTemplate) { Text("Map this template") }
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                TextButton(onClick = onBlacklist) { Text("Blacklist") }
            }
        }
    }
}
