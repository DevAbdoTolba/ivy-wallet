package com.ivy.sms.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.sms.data.DrainParser
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.domain.model.SmsMessage
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.domain.model.WildcardSlot
import java.time.Instant
import java.util.UUID
import javax.inject.Inject

class DiscoverTemplatesUseCase @Inject constructor(
    private val parser: DrainParser,
    private val templateRepo: SmsTemplateRepository,
) {
    /**
     * Returns the active or newly-discovered template for a single message.
     * Persists newly-emitted clusters as SmsTemplate rows in state = UNMAPPED.
     */
    suspend operator fun invoke(message: SmsMessage): Either<String, SmsTemplate> {
        val cluster = parser.consume(message)
        val templateId = SmsTemplateId(cluster.templateId)
        val pattern = cluster.templatePattern.joinToString(" ")
        val msgTime = Instant.ofEpochMilli(message.timestamp.toEpochMilli())

        val existingResult = templateRepo.findById(templateId)
        existingResult.onLeft { return it.left() }
        val existing = existingResult.getOrNull()

        if (existing != null) {
            // Refresh lastSeen + matchCount + pattern (clustering may have re-merged
            // it after this message). Preserves user-set role bindings and state.
            val updated = existing.copy(
                pattern = pattern,
                lastSeen = msgTime,
                matchCount = cluster.messageCount,
                wildcardSlots = mergeSlots(existing.wildcardSlots, cluster.templatePattern, cluster.exampleValues),
            )
            return templateRepo.upsert(updated).map { updated }
        }

        val slots = wildcardsFromPattern(cluster.templatePattern, cluster.exampleValues)
        val newTemplate = SmsTemplate(
            id = templateId,
            pattern = pattern,
            exampleBody = cluster.exampleBody,
            wildcardSlots = slots,
            state = TemplateState.UNMAPPED,
            senderIdHint = message.senderId,
            firstSeen = msgTime,
            lastSeen = msgTime,
            matchCount = cluster.messageCount,
        )
        return templateRepo.upsert(newTemplate).map { newTemplate }
    }

    /** Keep user-bound roles for slots whose position survived re-merging; create
     *  fresh Unmapped slots for newly-emerged wildcard positions. */
    private fun mergeSlots(
        existing: List<WildcardSlot>,
        newPattern: List<String>,
        exampleValues: Map<Int, String>,
    ): List<WildcardSlot> {
        val byPosition = existing.associateBy { it.positionInPattern }
        val merged = mutableListOf<WildcardSlot>()
        for ((idx, tok) in newPattern.withIndex()) {
            if (tok != com.ivy.sms.data.WILDCARD_TOKEN) continue
            val prior = byPosition[idx]
            merged.add(
                if (prior != null) {
                    prior.copy(exampleValue = exampleValues[idx].orEmpty().ifBlank { prior.exampleValue })
                } else {
                    WildcardSlot(
                        id = WildcardId(UUID.randomUUID()),
                        positionInPattern = idx,
                        contextSnippet = "",
                        exampleValue = exampleValues[idx].orEmpty(),
                        role = WildcardRole.Unmapped,
                    )
                },
            )
        }
        return merged
    }

    suspend fun seed() {
        val seed = templateRepo.findAll().getOrNull() ?: return
        parser.rebuildFromTemplates(seed)
    }

    private fun wildcardsFromPattern(
        tokens: List<String>,
        exampleValues: Map<Int, String>,
    ): List<WildcardSlot> {
        val slots = mutableListOf<WildcardSlot>()
        for ((idx, tok) in tokens.withIndex()) {
            if (tok != com.ivy.sms.data.WILDCARD_TOKEN) continue
            val before = tokens.subList(maxOf(0, idx - 2), idx).joinToString(" ")
            val after = tokens.subList(idx + 1, minOf(tokens.size, idx + 3)).joinToString(" ")
            slots.add(
                WildcardSlot(
                    id = WildcardId(UUID.randomUUID()),
                    positionInPattern = idx,
                    contextSnippet = "$before <*> $after".trim(),
                    exampleValue = exampleValues[idx].orEmpty(),
                    role = WildcardRole.Unmapped,
                )
            )
        }
        return slots
    }
}
