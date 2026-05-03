package com.ivy.sms.domain.usecase

import arrow.core.Either
import arrow.core.right
import com.ivy.sms.data.SmsInboxDataSource
import com.ivy.sms.data.SmsMessageMapper
import com.ivy.sms.domain.model.SmsMessage
import com.ivy.sms.domain.model.SmsTemplate
import javax.inject.Inject

/**
 * Returns up to [limit] inbox messages whose body the given template's pattern can
 * align to. Used by the templates list to answer the user's question "which 3
 * messages exactly does this row match?" without persisting per-template body
 * snapshots — the device inbox is the source of truth.
 *
 * Cheap because we scope the inbox query by `senderIdHint`. Even on devices with
 * tens of thousands of SMS this returns in tens of ms because each sender's chat
 * is small.
 */
class FindMatchingMessagesUseCase @Inject constructor(
    private val inbox: SmsInboxDataSource,
    private val mapper: SmsMessageMapper,
) {
    suspend operator fun invoke(
        template: SmsTemplate,
        limit: Int = 20,
    ): Either<String, List<SmsMessage>> {
        val sender = template.senderIdHint.ifBlank { return emptyList<SmsMessage>().right() }
        val rows = inbox.read(
            lowerBoundEpochMillis = 0L,
            watermarkEpochMillis = 0L,
            senderFilter = sender,
        ).getOrNull().orEmpty()

        val matches = mutableListOf<SmsMessage>()
        // Walk newest-first so the user sees the most recent matches.
        for (row in rows.sortedByDescending { it.dateEpochMillis }) {
            val msg = with(mapper) { row.toDomain() }
            if (extractWildcardValues(template, msg) != null) {
                matches.add(msg)
                if (matches.size >= limit) break
            }
        }
        return matches.right()
    }
}
