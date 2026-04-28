package com.ivy.sms.domain.model

import com.ivy.data.model.AccountId
import java.time.Instant

data class SenderAccountLink(
    val senderId: String,
    val accountId: AccountId,
    val linkedAt: Instant,
)
