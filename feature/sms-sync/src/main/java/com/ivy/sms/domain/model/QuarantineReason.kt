package com.ivy.sms.domain.model

enum class QuarantineReason {
    TEMPLATE_NOT_MAPPED,
    AMOUNT_NOT_PARSEABLE,
    SENDER_NOT_LINKED,
    CURRENCY_MISMATCH,
}
