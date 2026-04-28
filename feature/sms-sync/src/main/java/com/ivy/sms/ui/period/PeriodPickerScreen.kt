package com.ivy.sms.ui.period

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ivy.sms.domain.model.ScanPeriod

@Composable
fun PeriodPickerScreen(
    onConfirmed: () -> Unit,
    viewModel: PeriodPickerViewModel = viewModel(),
) {
    val state = viewModel.uiState()
    viewModel.setOnConfirmed(onConfirmed)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "Pick how far back to scan your SMS")
        Spacer(Modifier.height(16.dp))

        state.options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(
                        selected = state.selected == option,
                        onClick = { viewModel.onEvent(PeriodPickerEvent.Select(option)) },
                    )
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = state.selected == option,
                    onClick = { viewModel.onEvent(PeriodPickerEvent.Select(option)) },
                )
                Spacer(Modifier.height(0.dp))
                Text(text = option.label())
            }
        }

        state.error?.let { Text(text = it) }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { viewModel.onEvent(PeriodPickerEvent.Confirm) },
            enabled = state.selected != null && !state.confirming,
        ) {
            Text(text = if (state.confirming) "Scanning…" else "Start scanning")
        }
    }
}

private fun ScanPeriod.label(): String = when (this) {
    ScanPeriod.LastWeek -> "Last Week"
    ScanPeriod.LastMonth -> "Last Month"
    ScanPeriod.LastQuarter -> "Last Quarter"
    ScanPeriod.LastYear -> "Last Year"
    ScanPeriod.AllTime -> "All Time"
}
