package com.ivy.sms.ui.period

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.sms.domain.model.ScanPeriod
import com.ivy.wallet.ui.theme.Green
import com.ivy.wallet.ui.theme.Red
import com.ivy.wallet.ui.theme.components.IvyButton

@Composable
fun PeriodPickerScreen(
    onConfirmed: () -> Unit,
    viewModel: PeriodPickerViewModel = viewModel(),
) {
    val state = viewModel.uiState()
    viewModel.setOnConfirmed(onConfirmed)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(UI.colors.pure)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "How far back?",
                style = UI.typo.h2.style(
                    color = UI.colors.pureInverse,
                    fontWeight = FontWeight.ExtraBold,
                ),
            )
            Text(
                text = "Pick the time window for the first scan. You can extend it later.",
                style = UI.typo.b2.style(
                    color = UI.colors.gray,
                    fontWeight = FontWeight.Medium,
                ),
            )
            Spacer(Modifier.height(8.dp))

            state.options.forEach { option ->
                PeriodRow(
                    label = option.label(),
                    selected = state.selected == option,
                    onClick = { viewModel.onEvent(PeriodPickerEvent.Select(option)) },
                )
            }

            state.error?.let { msg ->
                Spacer(Modifier.height(4.dp))
                Text(
                    text = msg,
                    style = UI.typo.c.style(
                        color = Red,
                        fontWeight = FontWeight.SemiBold,
                    ),
                )
            }

            Spacer(Modifier.weight(1f))
            IvyButton(
                text = if (state.confirming) "Scanning…" else "Start scanning",
                modifier = Modifier.fillMaxWidth(),
                enabled = state.selected != null && !state.confirming,
                onClick = { viewModel.onEvent(PeriodPickerEvent.Confirm) },
            )
        }
    }
}

@Composable
private fun PeriodRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = if (selected) Green else UI.colors.gray
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(UI.shapes.r4)
            .background(if (selected) Green.copy(alpha = 0.18f) else UI.colors.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(UI.shapes.rFull)
                .background(if (selected) Green else UI.colors.pure),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(UI.shapes.rFull)
                        .background(androidx.compose.ui.graphics.Color.White),
                )
            }
        }
        Spacer(Modifier.height(0.dp))
        Box(modifier = Modifier.padding(start = 14.dp)) {
            Text(
                text = label,
                style = UI.typo.b2.style(
                    color = if (selected) accent else UI.colors.pureInverse,
                    fontWeight = FontWeight.ExtraBold,
                ),
            )
        }
    }
}

private fun ScanPeriod.label(): String = when (this) {
    ScanPeriod.LastWeek -> "Last week"
    ScanPeriod.LastMonth -> "Last month"
    ScanPeriod.LastQuarter -> "Last 3 months"
    ScanPeriod.LastYear -> "Last year"
    ScanPeriod.AllTime -> "All time"
}
