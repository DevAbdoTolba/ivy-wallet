package com.ivy.sms.domain.usecase

import arrow.core.Either
import arrow.core.right
import com.ivy.sms.data.SmsInboxDataSource
import com.ivy.sms.data.SmsMessageMapper
import com.ivy.sms.domain.model.SmsMessage
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Returns up to [limit] inbox messages whose body the given template's pattern
 * can ACTUALLY align to (via extractWildcardValues). Used by the templates
 * list to answer "which inbox messages will this template turn into a
 * transaction?" — the count and the listed bodies always agree because both
 * come from the same alignment check.
 *
 * Earlier this used Jaccard similarity (which mirrors Drain's clustering
 * heuristic), but that was too permissive: an income template's literals
 * overlap heavily with an unrelated شحن template from the same Vodafone
 * Cash sender, so the row showed 100 matches but only 17 became actual
 * transactions. The user reported the mismatch.
 */
private val whitespaceRegex = Regex("\\s+")

@Singleton
class FindMatchingMessagesUseCase @Inject constructor(
    private val inbox: SmsInboxDataSource,
    private val mapper: SmsMessageMapper,
) {
    /**
     * Process-lifetime cache so re-entering the templates screen doesn't
     * re-scan the inbox for every expand-tap. Keyed by templateId — a
     * pattern change re-creates the template id (Drain), so cached entries
     * are implicitly invalidated when the template moves on.
     */
    private val cache = ConcurrentHashMap<SmsTemplateId, List<SmsMessage>>()

    /** Drops cached results — call after a sync that may have ingested new SMS. */
    fun invalidate() {
        cache.clear()
    }

    suspend operator fun invoke(
        template: SmsTemplate,
        limit: Int = 100,
    ): Either<String, List<SmsMessage>> {
        cache[template.id]?.let { return it.right() }
        val sender = template.senderIdHint.ifBlank { return emptyList<SmsMessage>().right() }
        val rows = inbox.read(
            lowerBoundEpochMillis = 0L,
            watermarkEpochMillis = 0L,
            senderFilter = sender,
        ).getOrNull().orEmpty()

        val matches = mutableListOf<SmsMessage>()
        for (row in rows.sortedByDescending { it.dateEpochMillis }) {
            val msg = with(mapper) { row.toDomain() }
            // Strict alignment: only count messages the template's pattern can
            // actually extract wildcard values from. The same check the runtime
            // routing pipeline uses, so the row count == the number of
            // transactions that will actually be created when the template is
            // mapped (or has already been mapped).
            if (extractWildcardValues(template, msg) != null) {
                matches.add(msg)
                if (matches.size >= limit) break
            }
        }
        cache[template.id] = matches.toList()
        return matches.right()
    }
}
