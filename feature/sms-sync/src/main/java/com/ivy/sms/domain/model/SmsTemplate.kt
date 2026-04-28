package com.ivy.sms.domain.model

import java.time.Instant

data class SmsTemplate(
    val id: SmsTemplateId,
    val pattern: String,
    val wildcardSlots: List<WildcardSlot>,
    val state: TemplateState,
    val classification: TransactionClassification?,
    val senderIdHint: String,
    val firstSeen: Instant,
    val lastSeen: Instant,
    val matchCount: Int,
)

data class WildcardSlot(
    val id: WildcardId,
    val positionInPattern: Int,
    val contextSnippet: String,
    val mapping: WildcardMapping,
)

sealed interface WildcardMapping {
    data object Unmapped : WildcardMapping
    data object Amount : WildcardMapping
    data object Merchant : WildcardMapping
    data object DateTime : WildcardMapping
    data object Reference : WildcardMapping
    data object Ignored : WildcardMapping
}

enum class TemplateState { UNMAPPED, ACTIVE, BLACKLISTED, PENDING_REVIEW }

enum class TransactionClassification { INCOME, EXPENSE, TRANSFER }
