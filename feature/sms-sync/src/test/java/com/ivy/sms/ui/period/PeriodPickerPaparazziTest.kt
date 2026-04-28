package com.ivy.sms.ui.period

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import com.ivy.sms.domain.model.ScanPeriod
import com.ivy.ui.testing.PaparazziScreenshotTest
import com.ivy.ui.testing.PaparazziTheme
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(TestParameterInjector::class)
class PeriodPickerPaparazziTest(
    @TestParameter
    private val theme: PaparazziTheme,
) : PaparazziScreenshotTest() {

    @Test
    fun noSelection() {
        snapshot(theme) {
            PeriodPickerPreview(state = PeriodPickerViewState())
        }
    }

    @Test
    fun selected_lastYear() {
        snapshot(theme) {
            PeriodPickerPreview(state = PeriodPickerViewState(selected = ScanPeriod.LastYear))
        }
    }
}

@Composable
private fun PeriodPickerPreview(state: PeriodPickerViewState) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Pick how far back to scan your SMS")
        state.options.forEach { Text("[${if (state.selected == it) "x" else " "}] $it") }
    }
}
