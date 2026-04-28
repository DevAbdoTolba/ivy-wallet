package com.ivy.sms.data

data class SmsRow(
    val id: Long,
    val address: String,
    val dateEpochMillis: Long,
    val body: String,
)
