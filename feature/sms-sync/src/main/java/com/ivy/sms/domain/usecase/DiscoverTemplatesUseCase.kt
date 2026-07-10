package com.ivy.sms.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.sms.data.DrainParser
import com.ivy.sms.data.SenderAccountLinkRepository
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

data class DiscoverResult(
    val template: SmsTemplate,
    /** True when this message minted a brand-new template row (vs refreshing an existing one). */
    val created: Boolean,
)

class DiscoverTemplatesUseCase @Inject constructor(
    private val parser: DrainParser,
    private val templateRepo: SmsTemplateRepository,
    private val senderRepo: SenderAccountLinkRepository,
) {
    /**
     * Returns the existing or newly-discovered template for a single message,
     * tagged with whether it was created by this call. Persists newly-emitted
     * clusters as SmsTemplate rows in state = UNMAPPED.
     */
    suspend operator fun invoke(message: SmsMessage): Either<String, DiscoverResult> {
        val cluster = parser.consume(message)
        val templateId = SmsTemplateId(cluster.templateId)
        val pattern = cluster.templatePattern.joinToString(" ")
        val msgTime = Instant.ofEpochMilli(message.timestamp.toEpochMilli())

        val existingResult = templateRepo.findById(templateId)
        existingResult.onLeft { return it.left() }
        val existing = existingResult.getOrNull()

        if (existing != null) {
            val updated = if (existing.state == TemplateState.UNMAPPED) {
                // Still unmapped — refresh pattern + slots alongside the counters
                // (clustering may have re-merged the cluster after this message).
                val merged = mergeSlots(existing.wildcardSlots, cluster.templatePattern, cluster.exampleValues)
                // AUTO-ROLES: wildcards still Unmapped default to what the
                // bilingual inference reads from the pattern, so the mapping
                // screen opens pre-filled. Roles already bound to surviving
                // slots are never overwritten.
                val inferred = inferSlotRoles(pattern, merged)
                // SENDER TRUST: Drain keeps a format's FIRST sample as raw
                // literals — wildcards only emerge when the second message
                // merges, i.e. HERE. So this refresh is where a confidently
                // inferred format from a trusted, wallet-linked sender
                // activates itself (two samples of evidence, zero questions).
                val promoted = inferred.confident && senderTrustedAndLinked(message.senderId)
                if (promoted) {
                    timber.log.Timber.tag("SmsTrace").i(
                        "DISCOVER auto-activated tpl=%s sender=%s on merge (confident inference + trusted sender)",
                        existing.id.value,
                        message.senderId,
                    )
                }
                existing.copy(
                    pattern = pattern,
                    lastSeen = msgTime,
                    matchCount = cluster.messageCount,
                    wildcardSlots = merged.withInferredRoles(inferred),
                    state = if (promoted) TemplateState.ACTIVE else existing.state,
                )
            } else {
                // INVARIANT: templates that left UNMAPPED (user-mapped ACTIVE,
                // BLACKLISTED, or queued for review) are frozen — clustering must
                // never rewrite their pattern or slots, because re-merges shift
                // wildcard positions and silently destroy user-bound roles
                // (including the mandatory amount role). Only counters move.
                existing.copy(
                    lastSeen = msgTime,
                    matchCount = cluster.messageCount,
                )
            }
            return templateRepo.upsert(updated).map { DiscoverResult(updated, created = false) }
        }

        // SCAN-BACK RE-ASK FIX: the Drain tree files messages by token count +
        // stable prefix, so a variant of an ALREADY-MAPPED format (extra
        // merchant word, dropped currency token, reordered amount) lands on a
        // fresh leaf and would mint a brand-new UNMAPPED template — re-asking
        // the user for a shape they already answered. Before creating one, try
        // ALIGNING the message against this sender's frozen templates with the
        // exact aligner routing uses. A hit returns the frozen template:
        // ACTIVE auto-creates the transaction, BLACKLISTED drops the message,
        // and the user is never asked twice for the same shape.
        val allTemplates = templateRepo.findAll().getOrNull().orEmpty()
        val frozenMatch = allTemplates
            .filter { it.state != TemplateState.UNMAPPED }
            .filter { it.senderIdHint.equals(message.senderId, ignoreCase = true) }
            .firstOrNull { extractWildcardValues(it, message) != null }
        if (frozenMatch != null) {
            timber.log.Timber.tag("SmsTrace").i(
                "DISCOVER fallback: message aligned to frozen tpl=%s state=%s (drain leaf was new)",
                frozenMatch.id.value,
                frozenMatch.state,
            )
            val updated = frozenMatch.copy(
                lastSeen = maxOf(frozenMatch.lastSeen, msgTime),
                matchCount = frozenMatch.matchCount + 1,
            )
            return templateRepo.upsert(updated).map { DiscoverResult(updated, created = false) }
        }

        val slots = wildcardsFromPattern(cluster.templatePattern, cluster.exampleValues)
        // AUTO-ROLES: infer every wildcard's role from the pattern's bilingual
        // keyword context + example values, persisted on the row so the
        // mapping screen opens pre-filled — the user glances and saves.
        val inferred = inferSlotRoles(pattern, slots)
        val preFilled = slots.withInferredRoles(inferred)

        // SENDER TRUST — the fully-automatic path: unambiguous inference AND
        // the user already approved a template from this sender AND the
        // sender is wallet-linked → the new template activates itself and
        // transactions flow with zero questions. Rare at creation (Drain's
        // first sample usually has no wildcards yet — see the refresh path),
        // but correct whenever the first sample does carry slots.
        val state = if (inferred.confident && senderTrustedAndLinked(message.senderId)) {
            timber.log.Timber.tag("SmsTrace").i(
                "DISCOVER auto-activated tpl=%s sender=%s at creation (confident inference + trusted sender)",
                templateId.value,
                message.senderId,
            )
            TemplateState.ACTIVE
        } else {
            TemplateState.UNMAPPED
        }

        val newTemplate = SmsTemplate(
            id = templateId,
            pattern = pattern,
            exampleBody = cluster.exampleBody,
            wildcardSlots = preFilled,
            state = state,
            senderIdHint = message.senderId,
            firstSeen = msgTime,
            lastSeen = msgTime,
            matchCount = cluster.messageCount,
        )
        return templateRepo.upsert(newTemplate).map { DiscoverResult(newTemplate, created = true) }
    }

    /** Overlay inferred roles onto slots that are still Unmapped. */
    private fun List<WildcardSlot>.withInferredRoles(inferred: InferredRoles): List<WildcardSlot> =
        map { slot ->
            if (slot.role == WildcardRole.Unmapped) {
                slot.copy(role = inferred.roleBySlotId[slot.id] ?: slot.role)
            } else {
                slot
            }
        }

    /**
     * The auto-activation trust gate: the user must have already approved at
     * least one ACTIVE template from this sender (their manual mapping is the
     * root of trust) AND the sender must be linked to a wallet so routing has
     * a destination. Per-sender auto-route prefs still gate the actual
     * transaction creation downstream.
     */
    private suspend fun senderTrustedAndLinked(senderId: String): Boolean {
        val templates = templateRepo.findAll().getOrNull().orEmpty()
        val trusted = templates.any {
            it.senderIdHint.equals(senderId, ignoreCase = true) && it.state == TemplateState.ACTIVE
        }
        return trusted && senderRepo.findBySenderId(senderId).getOrNull() != null
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
