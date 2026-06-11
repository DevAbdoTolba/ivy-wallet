package com.ivy.sms.domain.usecase

import arrow.core.Either
import arrow.core.right
import com.ivy.sms.data.SmsInboxDataSource
import com.ivy.sms.data.SmsMessageMapper
import com.ivy.sms.data.SmsWatermarkPreferences
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
    private val watermarks: SmsWatermarkPreferences,
) {
    /**
     * Process-lifetime cache so re-entering the templates screen doesn't
     * re-scan the inbox for every expand-tap. Keyed by (templateId, pattern
     * hash) — the old templateId-only key assumed "a pattern change
     * re-creates the template id (Drain)", which is FALSE: MapTemplateUseCase
     * and DiscoverTemplatesUseCase both upsert the SAME id, so the row
     * counts/modal lists stayed stale after the user edited a pattern.
     */
    private data class CacheKey(val templateId: SmsTemplateId, val patternHash: Int)

    private val cache = ConcurrentHashMap<CacheKey, List<SmsMessage>>()

    /** Drops cached results — call after a sync that may have ingested new SMS. */
    fun invalidate() {
        cache.clear()
    }

    /** Drops cached results for one template — call after a pattern edit is
     *  persisted (MapTemplateUseCase upsert) so the templates list reflects
     *  the new pattern immediately, not on the next sync. */
    fun invalidate(templateId: SmsTemplateId) {
        cache.keys.removeAll { it.templateId == templateId }
    }

    suspend operator fun invoke(
        template: SmsTemplate,
        limit: Int = 100,
    ): Either<String, List<SmsMessage>> {
        val cacheKey = CacheKey(template.id, template.pattern.hashCode())
        cache[cacheKey]?.let {
            timber.log.Timber.tag("SmsTrace")
                .d("FIND   cache hit tpl=%s size=%d", template.id.value, it.size)
            return it.right()
        }
        val sender = template.senderIdHint.ifBlank { return emptyList<SmsMessage>().right() }
        // Honour the user's chosen sync period — the row's "X messages" count
        // and the modal's listed bodies must be the same set the sync scanned,
        // otherwise the user sees "5 messages" but only 1 transaction because
        // the other 4 were older than their picked period and never routed.
        // SyncSmsUseCase invalidates this cache after each scan, so updating
        // the period via the wallet's "Sync now" sheet is reflected here on
        // the next view.
        val lowerBound = watermarks.scanLowerBound().getOrNull() ?: 0L
        val rows = inbox.read(
            lowerBoundEpochMillis = lowerBound,
            watermarkEpochMillis = 0L,
            senderFilter = sender,
        ).getOrNull().orEmpty()
        timber.log.Timber.tag("SmsTrace")
            .d(
                "FIND → tpl=%s sender=%s lowerBound=%d rowsFromInbox=%d drainCount=%d",
                template.id.value, sender, lowerBound, rows.size, template.matchCount,
            )

        val matches = mutableListOf<SmsMessage>()
        var alignFailed = 0
        for (row in rows.sortedByDescending { it.dateEpochMillis }) {
            val msg = with(mapper) { row.toDomain() }
            if (extractWildcardValues(template, msg) != null) {
                matches.add(msg)
                if (matches.size >= limit) break
            } else {
                alignFailed++
            }
        }
        timber.log.Timber.tag("SmsTrace").d(
            "FIND ← tpl=%s aligned=%d alignFailed=%d total=%d",
            template.id.value, matches.size, alignFailed, rows.size,
        )
        cache[cacheKey] = matches.toList()
        return matches.right()
    }
}
