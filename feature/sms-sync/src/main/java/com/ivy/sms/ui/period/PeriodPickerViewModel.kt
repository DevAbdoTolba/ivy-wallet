package com.ivy.sms.ui.period

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.sms.data.SmsWatermarkPreferences
import com.ivy.sms.domain.model.ScanPeriod
import com.ivy.sms.domain.model.SyncTrigger
import com.ivy.sms.domain.usecase.SyncSmsUseCase
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

@Stable
@HiltViewModel
class PeriodPickerViewModel @Inject constructor(
    private val watermarks: SmsWatermarkPreferences,
    private val sync: SyncSmsUseCase,
) : ComposeViewModel<PeriodPickerViewState, PeriodPickerEvent>() {

    private var state by mutableStateOf(PeriodPickerViewState())
    private var onConfirmed: (() -> Unit)? = null

    fun setOnConfirmed(action: () -> Unit) {
        onConfirmed = action
    }

    @Composable
    override fun uiState(): PeriodPickerViewState = state

    override fun onEvent(event: PeriodPickerEvent) {
        when (event) {
            is PeriodPickerEvent.Select -> state = state.copy(selected = event.option, error = null)
            PeriodPickerEvent.Confirm -> confirm()
            PeriodPickerEvent.Dismiss -> { /* Screen-owned dismissal */ }
        }
    }

    private fun confirm() {
        val chosen = state.selected ?: run {
            state = state.copy(error = "Pick a period")
            return
        }
        state = state.copy(confirming = true, error = null)
        viewModelScope.launch {
            watermarks.writeLowerBound(lowerBoundFor(chosen))
            sync(SyncTrigger.FIRST_SCAN_AFTER_PERMISSION)
            state = state.copy(confirming = false)
            onConfirmed?.invoke()
        }
    }

    private fun lowerBoundFor(period: ScanPeriod): Long {
        val now = Instant.now()
        return when (period) {
            ScanPeriod.AllTime -> 0L
            ScanPeriod.LastWeek -> now.minus(7, ChronoUnit.DAYS).toEpochMilli()
            ScanPeriod.LastMonth -> now.minus(30, ChronoUnit.DAYS).toEpochMilli()
            ScanPeriod.LastQuarter -> now.minus(90, ChronoUnit.DAYS).toEpochMilli()
            ScanPeriod.LastYear -> now.minus(365, ChronoUnit.DAYS).toEpochMilli()
        }
    }
}
