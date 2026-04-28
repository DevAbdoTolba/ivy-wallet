package com.ivy.sms.domain.model

sealed interface ScanPeriod {
    data object LastWeek : ScanPeriod
    data object LastMonth : ScanPeriod
    data object LastQuarter : ScanPeriod
    data object LastYear : ScanPeriod
    data object AllTime : ScanPeriod
}
