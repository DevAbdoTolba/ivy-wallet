package com.ivy.sms.ui.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState

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

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopBar(
                pendingCount = state.pendingReviewCount,
                onScanFurther = { viewModel.onEvent(TemplateListEvent.ScanFurtherBack) },
                onOpenPending = { viewModel.onEvent(TemplateListEvent.OpenPendingReview) },
            )

            state.scanProgress?.let { p ->
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(text = "Scanning… ${p.processed} / ${p.total}")
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = if (p.total > 0) p.processed.toFloat() / p.total else 0f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (state.templates.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No templates yet — pull down or tap Sync to scan.")
                }
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.templates, key = { it.id.value }) { row ->
                        TemplateRow(
                            row = row,
                            onClick = { viewModel.onEvent(TemplateListEvent.TemplateClicked(row.id)) },
                            onBlacklistToggle = { viewModel.onEvent(TemplateListEvent.ToggleBlacklist(row.id)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    pendingCount: Int,
    onScanFurther: () -> Unit,
    onOpenPending: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "SMS Templates", modifier = Modifier.weight(1f))
        if (pendingCount > 0) {
            TextButton(onClick = onOpenPending) { Text("Pending Review ($pendingCount)") }
        }
        var menuOpen by remember { mutableStateOf(false) }
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Scan further back…") },
                onClick = {
                    menuOpen = false
                    onScanFurther()
                },
            )
        }
    }
}

@Composable
private fun TemplateRow(
    row: TemplateRowViewState,
    onClick: () -> Unit,
    onBlacklistToggle: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.preview,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Row menu")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (row.state == TemplateState.BLACKLISTED) "Un-blacklist"
                                else "Blacklist",
                            )
                        },
                        onClick = {
                            menuOpen = false
                            onBlacklistToggle()
                        },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Text(text = row.state.badge())
                Spacer(Modifier.weight(1f))
                Text(text = "${row.matchCount} matches")
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                TextButton(onClick = onClick) { Text("Open") }
            }
        }
    }
}

private fun TemplateState.badge(): String = when (this) {
    TemplateState.UNMAPPED -> "Unmapped"
    TemplateState.ACTIVE -> "Active"
    TemplateState.BLACKLISTED -> "Blacklisted"
    TemplateState.PENDING_REVIEW -> "Pending Review"
}
