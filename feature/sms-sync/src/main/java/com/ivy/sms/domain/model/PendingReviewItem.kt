package com.ivy.sms.domain.model

import java.time.Instant

data class PendingReviewItem(
    val id: PendingReviewItemId,
    val sms: SmsMessage,
    val template: SmsTemplate,
    val quarantineReason: QuarantineReason,
    val enqueuedAt: Instant,
)
