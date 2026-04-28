package com.ivy.sms.data

import com.ivy.data.model.TransactionMetadata
import com.ivy.sms.domain.model.SmsTemplateId

val TransactionMetadata.smsTemplateIdTyped: SmsTemplateId?
    get() = smsTemplateId?.let(::SmsTemplateId)
