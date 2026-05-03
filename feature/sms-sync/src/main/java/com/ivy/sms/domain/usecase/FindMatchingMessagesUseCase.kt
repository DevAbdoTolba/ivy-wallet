package com.ivy.sms.domain.usecase

import arrow.core.Either
import arrow.core.right
import com.ivy.sms.data.SmsInboxDataSource
import com.ivy.sms.data.SmsMessageMapper
import com.ivy.sms.data.WILDCARD_TOKEN
import com.ivy.sms.domain.model.SmsMessage
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Returns up to [limit] inbox messages whose body matches the given template.
 * Used by the templates list to answer the user's question "which messages
 * does this row match?" without persisting per-template body snapshots —
 * the device inbox is the source of truth.
 *
 * Match check is Jaccard-similarity over normalised literal tokens, mirroring
 * the same heuristic [com.ivy.sms.data.DrainParser] uses to cluster messages
 * into the template in the first place. The previous implementation used
 * [extractWildcardValues] which requires a STRICT pattern alignment — that
 * caused matchCount=17 to display only 5 messages in the expand view because
 * Drain merges had narrowed the pattern over time and old example bodies
 * could no longer align to it. The user said "shows almost only extra 5 but
 * it says there are 17!".
 */
private const val JACCARD_THRESHOLD = 0.4
private val whitespaceRegex = Regex("\\s+")
private val digitRegex = Regex("[0-9\\u0660-\\u0669\\u06F0-\\u06F9]")

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

        val patternLiterals = template.pattern.split(whitespaceRegex)
            .filter { it.isNotBlank() && it != WILDCARD_TOKEN }
            .toSet()
        if (patternLiterals.isEmpty()) {
            // All-wildcard pattern → fall back to "all from this sender".
            val all = rows
                .sortedByDescending { it.dateEpochMillis }
                .take(limit)
                .map { with(mapper) { it.toDomain() } }
            cache[template.id] = all
            return all.right()
        }

        val matches = mutableListOf<SmsMessage>()
        for (row in rows.sortedByDescending { it.dateEpochMillis }) {
            val msg = with(mapper) { row.toDomain() }
            if (jaccardMatches(patternLiterals, msg.body)) {
                matches.add(msg)
                if (matches.size >= limit) break
            }
        }
        cache[template.id] = matches.toList()
        return matches.right()
    }

    private fun jaccardMatches(patternLiterals: Set<String>, body: String): Boolean {
        val msgLiterals = body.split(whitespaceRegex)
            .filter { it.isNotBlank() }
            .filterNot { digitRegex.containsMatchIn(it) }
            .toSet()
        if (msgLiterals.isEmpty()) return false
        val intersect = patternLiterals.intersect(msgLiterals).size
        val union = patternLiterals.union(msgLiterals).size
        if (union == 0) return false
        return intersect.toDouble() / union.toDouble() >= JACCARD_THRESHOLD
    }
}
