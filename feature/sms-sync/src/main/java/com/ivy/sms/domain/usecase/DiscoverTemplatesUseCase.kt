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
import com.ivy.sms.domain.model.WildcardMapping
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

        templateRepo.findById(templateId).onRight { existing ->
            if (existing != null) {
                return existing.right()
            }
        }.onLeft { return it.left() }

        val pattern = cluster.templatePattern.joinToString(" ")
        val now = Instant.ofEpochMilli(message.timestamp.toEpochMilli())
        val slots = wildcardsFromPattern(cluster.templatePattern)
        val newTemplate = SmsTemplate(
            id = templateId,
            pattern = pattern,
            wildcardSlots = slots,
            state = TemplateState.UNMAPPED,
            classification = null,
            senderIdHint = message.senderId,
            firstSeen = now,
            lastSeen = now,
            matchCount = cluster.messageCount,
        )
        return templateRepo.upsert(newTemplate).map { newTemplate }
    }

    suspend fun seed() {
        val seed = templateRepo.findAll().getOrNull() ?: return
        parser.rebuildFromTemplates(seed)
    }

    private fun wildcardsFromPattern(tokens: List<String>): List<WildcardSlot> {
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
                    mapping = WildcardMapping.Unmapped,
                )
            )
        }
        return slots
    }
}
