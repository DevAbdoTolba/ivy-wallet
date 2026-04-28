package com.ivy.sms.domain.model

import java.util.UUID

@JvmInline
value class SmsTemplateId(val value: UUID)

@JvmInline
value class WildcardId(val value: UUID)

@JvmInline
value class PendingReviewItemId(val value: UUID)
