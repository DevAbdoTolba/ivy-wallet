package com.ivy.sms.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.sms.data.NORMALIZER_VERSION
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SmsBodyNormalizer
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.data.SmsWatermarkPreferences
import com.ivy.sms.data.WILDCARD_TOKEN
import com.ivy.sms.domain.model.SmsTemplate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One-shot renormalization of PERSISTED parse data. Templates and pending
 * items created by pre-normalizer builds hold un-normalized text (NBSP-glued
 * tokens, bidi marks, Arabic-Indic digits) on one side of every comparison,
 * while freshly-read bodies are normalized at the [com.ivy.sms.data.SmsMessageMapper]
 * chokepoint — so previously-working templates stop aligning and Drain forks
 * duplicate clusters. This pass rewrites the persisted TEXT once per
 * [NORMALIZER_VERSION]:
 *
 *  - SmsTemplate.pattern — token-wise, preserving `<*>` tokens EXACTLY and
 *    remapping wildcardSlots' positionInPattern when a glued literal splits
 *    (or a pure-junk literal drops) so slots keep pointing at their wildcard.
 *  - SmsTemplate.exampleBody and each slot's exampleValue.
 *  - PendingReviewItem.body.
 *
 * Dedup keys are NEVER touched: identity hashes the RAW body (see
 * SmsMessageMapper), so the pending unique index, transaction
 * smsSourceDedupKey metadata, and ReprocessHistorical dedup stay coherent
 * with no key migration.
 *
 * Ordering guarantee: every scan path flows through SyncSmsUseCase, which
 * invokes this use case INSIDE its mutex before ScanInboxUseCase runs — so
 * the pass always completes before DrainParser is seeded from persisted
 * templates. The internal mutex + version stamp make concurrent/repeat calls
 * cheap no-ops; the normalizer itself is idempotent, so a pass interrupted
 * before the version stamp simply re-runs.
 */
@Singleton
class RenormalizePersistedSmsDataUseCase @Inject constructor(
    private val templateRepo: SmsTemplateRepository,
    private val pendingRepo: PendingReviewItemRepository,
    private val prefs: SmsWatermarkPreferences,
) {

    private val mutex = Mutex()
    private val whitespace = Regex("\\s+")

    suspend operator fun invoke(): Either<String, Unit> = mutex.withLock {
        val applied = when (val r = prefs.normalizerAppliedVersion()) {
            is Either.Left -> return@withLock r.value.left()
            is Either.Right -> r.value
        }
        if (applied != null && applied >= NORMALIZER_VERSION) {
            return@withLock Unit.right()
        }

        val templates = when (val r = templateRepo.findAll()) {
            is Either.Left -> return@withLock r.value.left()
            is Either.Right -> r.value
        }
        var templatesChanged = 0
        for (template in templates) {
            val renormalized = renormalize(template)
            if (renormalized != template) {
                when (val w = templateRepo.upsert(renormalized)) {
                    is Either.Left -> return@withLock w.value.left()
                    is Either.Right -> templatesChanged++
                }
            }
        }

        val pendingRows = when (val r = pendingRepo.findAllRaw()) {
            is Either.Left -> return@withLock r.value.left()
            is Either.Right -> r.value
        }
        var pendingChanged = 0
        for (row in pendingRows) {
            val normalizedBody = SmsBodyNormalizer.normalize(row.body)
            if (normalizedBody != row.body) {
                when (val w = pendingRepo.updateBody(row.id, normalizedBody)) {
                    is Either.Left -> return@withLock w.value.left()
                    is Either.Right -> pendingChanged++
                }
            }
        }

        when (val w = prefs.writeNormalizerAppliedVersion(NORMALIZER_VERSION)) {
            is Either.Left -> return@withLock w.value.left()
            is Either.Right -> Unit
        }
        Timber.tag("SmsTrace").i(
            "RENORM v%d templates=%d/%d pending=%d/%d",
            NORMALIZER_VERSION,
            templatesChanged,
            templates.size,
            pendingChanged,
            pendingRows.size,
        )
        Unit.right()
    }

    private fun renormalize(template: SmsTemplate): SmsTemplate {
        val oldTokens = template.pattern.split(whitespace).filter { it.isNotBlank() }
        val newTokens = mutableListOf<String>()
        // old wildcard index → new wildcard index. Wildcards map 1:1 (kept
        // verbatim); literals may split into several tokens (NBSP glue) or
        // drop entirely (pure invisible-mark junk), shifting later positions.
        val wildcardPositions = mutableMapOf<Int, Int>()
        oldTokens.forEachIndexed { idx, token ->
            if (token == WILDCARD_TOKEN) {
                wildcardPositions[idx] = newTokens.size
                newTokens += token
            } else {
                val normalized = SmsBodyNormalizer.normalize(token)
                if (normalized.isNotEmpty()) {
                    // normalize() collapses all whitespace to single ASCII
                    // spaces, so a plain split is exact here.
                    newTokens += normalized.split(' ')
                }
            }
        }
        return template.copy(
            pattern = newTokens.joinToString(" "),
            exampleBody = SmsBodyNormalizer.normalize(template.exampleBody),
            wildcardSlots = template.wildcardSlots.map { slot ->
                slot.copy(
                    positionInPattern = wildcardPositions[slot.positionInPattern]
                        ?: slot.positionInPattern,
                    exampleValue = SmsBodyNormalizer.normalize(slot.exampleValue),
                )
            },
        )
    }
}
