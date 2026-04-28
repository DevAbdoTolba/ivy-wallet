package com.ivy.sms.domain.model

import java.time.Instant

data class SmsMessage(
    val dedupKey: String,
    val senderId: String,
    val body: String,
    val timestamp: Instant,
)
