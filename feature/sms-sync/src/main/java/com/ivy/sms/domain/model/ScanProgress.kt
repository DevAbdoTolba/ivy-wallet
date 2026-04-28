package com.ivy.sms.domain.model

data class ScanProgress(
    val processed: Int,
    val total: Int,
    val newTemplatesDiscovered: Int,
    val transactionsCreated: Int,
    val itemsQuarantined: Int,
)
