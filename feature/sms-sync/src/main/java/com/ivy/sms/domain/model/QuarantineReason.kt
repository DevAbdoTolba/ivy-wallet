package com.ivy.sms.domain.model

enum class QuarantineReason {
    TEMPLATE_NOT_MAPPED,
    AMOUNT_NOT_PARSEABLE,
    SENDER_NOT_LINKED,
    CURRENCY_MISMATCH,

    /** Tier-1 match held for review because the sender's auto-route
     *  toggle is off — the user approves via Map/Save instead. */
    AUTO_ROUTE_DISABLED,
}
