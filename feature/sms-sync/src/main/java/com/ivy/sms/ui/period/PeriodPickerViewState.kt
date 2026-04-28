package com.ivy.sms.ui.period

import androidx.compose.runtime.Immutable
import com.ivy.sms.domain.model.ScanPeriod
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Immutable
data class PeriodPickerViewState(
    val options: ImmutableList<ScanPeriod> = persistentListOf(
        ScanPeriod.LastWeek,
        ScanPeriod.LastMonth,
        ScanPeriod.LastQuarter,
        ScanPeriod.LastYear,
        ScanPeriod.AllTime,
    ),
    val selected: ScanPeriod? = null,
    val confirming: Boolean = false,
    val error: String? = null,
)

sealed interface PeriodPickerEvent {
    data class Select(val option: ScanPeriod) : PeriodPickerEvent
    data object Confirm : PeriodPickerEvent
    data object Dismiss : PeriodPickerEvent
}
