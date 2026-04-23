package com.ivy.data.model

import com.ivy.data.model.sync.UniqueId
import java.time.Instant
import java.util.UUID

@JvmInline
value class LoanItemId(override val value: UUID) : UniqueId

@JvmInline
value class LoanId(override val value: UUID) : UniqueId

data class LoanItem(
    val id: LoanItemId = LoanItemId(UUID.randomUUID()),
    val contactId: LoanId,
    val amount: Double,
    val title: String,
    val isSettled: Boolean = false,
    val createdAt: Instant = Instant.now()
)
