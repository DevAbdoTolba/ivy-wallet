package com.ivy.sms.data

import com.ivy.sms.domain.model.SmsMessage
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.TemplateState
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory clustering engine for SMS bodies. Originally a Drain port (He et al. 2017),
 * rebuilt 2026-05-02 as a length-agnostic set-Jaccard matcher, and rebuilt again
 * 2026-06-11 back to classic Drain descent + positional similarity. The Jaccard
 * variant compared unordered token SETS, and Egyptian bank SMS reuse a small
 * boilerplate vocabulary (تم, خصم, من, حسابك, جم…) — a debit notification and a
 * fee notification routinely shared >40% of their vocabulary while having
 * completely different structure, so unrelated formats merged into Frankenstein
 * clusters whose rewritten pattern aligned with neither format.
 *
 * Current design:
 *   - Descent is `root → senderId → token count → first PREFIX_DEPTH stable
 *     (non-wildcard) tokens`. Sender partitioning keeps two senders that share
 *     boilerplate from ever sharing a cluster. Token-count bucketing restores
 *     the classic-Drain guarantee that in-leaf merges are always same-length,
 *     which keeps wildcard slot positions stable across merges. The trade-off
 *     (variable-length variants of one format cluster per length) is accepted:
 *     cross-format merges were the worse failure mode.
 *   - Similarity is classic Drain simSeq: tokens matching at the same position /
 *     max(length), computed over the digit-collapsed views of both sides.
 *   - All cluster-side comparisons (descent keys, similarity, merge equality)
 *     are case-insensitive; stored patterns and example values keep original
 *     casing — mirroring extraction's `equals(ignoreCase = true)` alignment.
 *
 * Digit-collapse rule (2026-05-07, descent + scoring only):
 *   Digit-bearing tokens are treated as `<*>` for tree descent and similarity
 *   so "Spent 100 EGP at Cafe" and "Spent 250 EGP at Cafe" land in the same
 *   cluster. The SAVED pattern stays raw (first sample verbatim): wildcards
 *   appear in it only when a later sample genuinely disagrees at a position
 *   (mergeTemplates), because with a single sample we can't tell variable from
 *   constant. The user marks unknown wildcards manually on the mapping screen.
 */
@Singleton
class DrainParser @Inject constructor() {

    private val root = DrainNode()
    private val similarity: Double = SIMILARITY_THRESHOLD
    private val maxChildren: Int = MAX_CHILDREN

    @Synchronized
    fun consume(message: SmsMessage): DrainCluster {
        val rawTokens = tokenize(message.body)
        // Digit-collapsed view used ONLY for cluster descent + similarity scoring.
        // Saved templatePattern stays as rawTokens (or earlier merged result)
        // so we don't lie about which positions actually vary.
        val descentTokens = preNormalize(rawTokens)
        val leaf = descend(message.senderId, descentTokens)
        val best = bestMatch(leaf, descentTokens)
        if (best != null) {
            // Merge the cluster's existing pattern against the new sample's
            // RAW tokens (not preNormalized). A position becomes `<*>` only
            // when this sample's literal genuinely disagrees with the
            // cluster's pattern — that's the structurally-required signal
            // of variability, no guessing.
            //
            // FROZEN clusters (template left UNMAPPED) are count-only: their
            // persisted pattern never changes, so mutating the in-memory copy
            // would only make scoring drift away from what routing actually
            // aligns against within the current scan batch.
            if (!best.frozen) {
                val merged = mergeTemplates(best.templatePattern, rawTokens)
                best.templatePattern = merged.pattern
                best.exampleValues = merged.exampleValues
            }
            best.messageCount += 1
            return best
        }
        // First sample for this leaf — save the body verbatim. No wildcards
        // until a second sample arrives; until then the user can opt in by
        // tapping any token on the mapping screen.
        val newCluster = DrainCluster(
            templateId = UUID.randomUUID(),
            templatePattern = rawTokens,
            messageCount = 1,
            exampleBody = message.body,
            exampleValues = emptyMap(),
        )
        leaf.clusters.add(newCluster)
        return newCluster
    }

    @Synchronized
    fun rebuildFromTemplates(seed: List<SmsTemplate>) {
        root.children.clear()
        root.clusters.clear()
        for (t in seed) {
            val tokens = patternToTokens(t.pattern)
            // Descend with the SAME digit-collapsed view consume() uses.
            // First-sample patterns persist raw digit literals; descending on
            // them verbatim used to re-home the cluster at a leaf no incoming
            // message could ever reach (messages descend digit-collapsed),
            // minting a duplicate template per amount on every reseed.
            val leaf = descend(t.senderIdHint, preNormalize(tokens))
            val examples: ExampleValues = t.wildcardSlots
                .associate { it.positionInPattern to it.exampleValue }
            leaf.clusters.add(
                DrainCluster(
                    templateId = t.id.value,
                    templatePattern = tokens,
                    messageCount = t.matchCount,
                    exampleBody = t.exampleBody,
                    exampleValues = examples,
                    // Mirror DiscoverTemplatesUseCase's persistence freeze:
                    // only UNMAPPED templates may have their pattern rewritten
                    // by clustering.
                    frozen = t.state != TemplateState.UNMAPPED,
                )
            )
        }
    }

    private fun tokenize(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        return body.split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    /** Latin 0-9 plus Arabic-Indic and Eastern Arabic-Indic digit blocks. */
    private val digitChar = Regex("[0-9\\u0660-\\u0669\\u06F0-\\u06F9]")

    private fun preNormalize(tokens: List<String>): List<String> = tokens.map { tok ->
        val cleaned = tok.trimEnd('.', ',', ':', ';', '!', '?')
        if (digitChar.containsMatchIn(cleaned)) WILDCARD_TOKEN else tok
    }

    /**
     * Classic Drain descent, sender-partitioned: root → senderId → token count →
     * first PREFIX_DEPTH stable (non-wildcard) tokens. Keys are lowercased so
     * case variants of the same sender/boilerplate bucket together; the stored
     * patterns themselves keep original casing.
     */
    private fun descend(senderId: String, tokens: List<String>): DrainNode {
        var node = childOrCreate(root, senderId.lowercase())
        node = childOrCreate(node, tokens.size.toString())
        val stablePrefix = tokens.asSequence()
            .filter { it != WILDCARD_TOKEN }
            .take(PREFIX_DEPTH)
            .map { it.lowercase() }
            .toList()
        if (stablePrefix.isEmpty()) {
            // Body is all-wildcards — bucket all of them under a sentinel key.
            return childOrCreate(node, WILDCARD_TOKEN)
        }
        for (key in stablePrefix) {
            node = childOrCreate(node, key)
        }
        return node
    }

    private fun childOrCreate(parent: DrainNode, key: String): DrainNode {
        parent.children[key]?.let { return it }
        if (parent.children.size >= maxChildren) {
            return parent.children.getOrPut(WILDCARD_TOKEN) { DrainNode() }
        }
        return parent.children.getOrPut(key) { DrainNode() }
    }

    private fun bestMatch(leaf: DrainNode, tokens: List<String>): DrainCluster? {
        if (leaf.clusters.isEmpty()) return null
        var bestScore = 0.0
        var best: DrainCluster? = null
        for (c in leaf.clusters) {
            // Score against the digit-collapsed view of the stored pattern so
            // consume-time and rebuild-time matching use identical keys.
            val score = positionalAgreement(preNormalize(c.templatePattern), tokens)
            if (score > bestScore) {
                bestScore = score
                best = c
            } else if (score == bestScore && best != null && tieBreakWins(c, best)) {
                // Deterministic tie-break: leaf.clusters order equals repo
                // findAll() order after a reseed, so "first at max score"
                // varied with DB row order across sessions.
                best = c
            }
        }
        return if (bestScore >= similarity) best else null
    }

    /** Prefer the cluster with more samples; then the smaller templateId. */
    private fun tieBreakWins(challenger: DrainCluster, incumbent: DrainCluster): Boolean =
        challenger.messageCount > incumbent.messageCount ||
            (challenger.messageCount == incumbent.messageCount &&
                challenger.templateId < incumbent.templateId)

    /**
     * Classic Drain simSeq: tokens equal (case-insensitive) at the same position /
     * max(length). Both sides must already be digit-collapsed so amounts compare
     * as `<*>` == `<*>`. Replaced set-Jaccard (2026-06-11): unordered vocabulary
     * overlap merged structurally different formats sharing Arabic boilerplate.
     */
    private fun positionalAgreement(a: List<String>, b: List<String>): Double {
        val longest = maxOf(a.size, b.size)
        if (longest == 0) return 1.0
        var matches = 0
        for (i in 0 until minOf(a.size, b.size)) {
            if (a[i].equals(b[i], ignoreCase = true)) matches++
        }
        return matches.toDouble() / longest
    }

    private data class MergeResult(
        val pattern: List<String>,
        val exampleValues: ExampleValues,
    )

    /**
     * Merge the cluster's existing template with a new candidate message.
     *
     * Same-length merge (the only case reachable through descent, which buckets
     * by token count): token-by-token — equal (case-insensitive) keeps the
     * existing literal, any disagreement becomes `<*>`. Wildcard positions never
     * move, so user-bound slot roles keyed by positionInPattern survive merges.
     *
     * Length-differing merge (only legacy persisted patterns whose adjacent
     * wildcards were collapsed by the pre-2026-06-11 merge): falls back to the
     * old run-collapse walk over the candidate.
     *
     * exampleValues are rebuilt from scratch on every merge — one aligned raw
     * candidate token per wildcard position of the MERGED pattern. Stale
     * position keys from before the merge are pruned, never carried over.
     */
    private fun mergeTemplates(existing: List<String>, candidate: List<String>): MergeResult {
        if (existing.size == candidate.size) {
            val pattern = List(existing.size) { i ->
                val tok = existing[i]
                if (tok != WILDCARD_TOKEN && tok.equals(candidate[i], ignoreCase = true)) {
                    tok
                } else {
                    WILDCARD_TOKEN
                }
            }
            val examples = mutableMapOf<Int, String>()
            for (i in pattern.indices) {
                if (pattern[i] == WILDCARD_TOKEN) examples[i] = candidate[i]
            }
            return MergeResult(pattern, examples)
        }
        val existingLiterals = existing.asSequence()
            .filter { it != WILDCARD_TOKEN }
            .map { it.lowercase() }
            .toSet()
        val pattern = mutableListOf<String>()
        val examples = mutableMapOf<Int, String>()
        var prevWildcard = false
        for (tok in candidate) {
            val stable = tok != WILDCARD_TOKEN && tok.lowercase() in existingLiterals
            if (stable) {
                pattern.add(tok)
                prevWildcard = false
            } else if (!prevWildcard) {
                examples[pattern.size] = tok
                pattern.add(WILDCARD_TOKEN)
                prevWildcard = true
            }
        }
        return MergeResult(pattern, examples)
    }

    private fun patternToTokens(pattern: String): List<String> =
        pattern.split(Regex("\\s+")).filter { it.isNotBlank() }
}
