package com.ivy.sms.data

import com.ivy.sms.domain.model.SmsMessage
import com.ivy.sms.domain.model.SmsTemplate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory clustering engine for SMS bodies. Originally a Drain port (He et al. 2017)
 * but rebuilt 2026-05-02 to handle the variable-length-merchant problem that classic
 * Drain (which buckets by exact token count) cannot:
 *
 *   "Spent EGP <*> at Cafe"          (5 tokens)
 *   "Spent EGP <*> at Coffee Shop"   (6 tokens)
 *
 * In Drain these go to different leaves and never merge. With bank SMS where merchant
 * names vary in word count constantly, that produced one cluster per unique-length
 * message — making the Templates screen useless.
 *
 * The new approach:
 *   - Bucket by the **first 3 stable (non-wildcard) tokens** only. Length is ignored.
 *   - Within a bucket, similarity is **Jaccard over stable token sets** (intersection
 *     of literals / union of literals).
 *   - Threshold lowered to 0.4: messages from the same template share most boilerplate
 *     even when the variable middle differs in length.
 *   - Merge collapses any non-shared tokens to a single `<*>` placeholder, preserving
 *     the order of the first message's stable tokens.
 *
 * Digit-collapse rule (2026-05-07, descent only):
 *   We still treat digit-bearing tokens as `<*>` *for tree descent and Jaccard
 *   matching* — that way a "Spent 100 EGP at Cafe" and "Spent 250 EGP at Cafe"
 *   land in the same cluster. But the SAVED pattern is now the raw tokens of
 *   the first sample. Wildcards only appear in the saved pattern when a
 *   later sample actually disagrees at a position (mergeTemplates).
 *
 *   Why the change: the old rule pre-marked every digit token as a wildcard
 *   in the saved pattern, so a stable "2024" in a greeting got auto-classified
 *   as variable. With a single sample we can't tell variable from constant —
 *   so we don't guess. The user marks unknown wildcards manually on the
 *   mapping screen; the tap-to-toggle UI handles the rest.
 */
@Singleton
class DrainParser @Inject constructor() {

    private val root = DrainNode()
    private val similarity: Double = SIMILARITY_THRESHOLD
    private val maxChildren: Int = MAX_CHILDREN

    @Synchronized
    fun consume(message: SmsMessage): DrainCluster {
        val rawTokens = tokenize(message.body)
        // Digit-collapsed view used ONLY for cluster descent + Jaccard scoring.
        // Saved templatePattern stays as rawTokens (or earlier merged result)
        // so we don't lie about which positions actually vary.
        val descentTokens = preNormalize(rawTokens)
        val leaf = descend(descentTokens, createIfMissing = true)
        val best = bestMatch(leaf, descentTokens)
        if (best != null) {
            // Merge the cluster's existing pattern against the new sample's
            // RAW tokens (not preNormalized). A position becomes `<*>` only
            // when this sample's literal genuinely disagrees with the
            // cluster's stable literals — that's the structurally-required
            // signal of variability, no guessing.
            val merged = mergeTemplates(best.templatePattern, rawTokens)
            val updatedExamples = best.exampleValues.toMutableMap()
            for (i in merged.indices) {
                if (merged[i] == WILDCARD_TOKEN && i !in updatedExamples) {
                    val raw = rawTokens.getOrNull(i) ?: continue
                    if (raw.isNotBlank()) updatedExamples[i] = raw
                }
            }
            best.templatePattern = merged
            best.exampleValues = updatedExamples
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
            val leaf = descend(tokens, createIfMissing = true)
            val examples: ExampleValues = t.wildcardSlots
                .associate { it.positionInPattern to it.exampleValue }
            leaf.clusters.add(
                DrainCluster(
                    templateId = t.id.value,
                    templatePattern = tokens,
                    messageCount = t.matchCount,
                    exampleBody = t.exampleBody,
                    exampleValues = examples,
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
     * Length-agnostic descent: walk into a child per the first PREFIX_DEPTH stable
     * (non-wildcard) tokens. Messages with the same boilerplate prefix bucket together
     * regardless of overall length.
     */
    private fun descend(tokens: List<String>, createIfMissing: Boolean): DrainNode {
        val stablePrefix = tokens.asSequence()
            .filter { it != WILDCARD_TOKEN }
            .take(PREFIX_DEPTH)
            .toList()
        if (stablePrefix.isEmpty()) {
            // Body is all-wildcards — bucket all of them under a sentinel key.
            return childOrCreate(root, WILDCARD_TOKEN, createIfMissing) ?: root
        }
        var node = root
        for (key in stablePrefix) {
            node = childOrCreate(node, key, createIfMissing) ?: return node
        }
        return node
    }

    private fun childOrCreate(parent: DrainNode, key: String, createIfMissing: Boolean): DrainNode? {
        parent.children[key]?.let { return it }
        if (!createIfMissing) return null
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
            val score = jaccardSimilarity(c.templatePattern, tokens)
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return if (bestScore >= similarity) best else null
    }

    /**
     * Jaccard similarity over stable (non-wildcard) tokens only. Length-agnostic so
     * variable-length merchant names don't fragment clusters.
     */
    private fun jaccardSimilarity(a: List<String>, b: List<String>): Double {
        val literalsA = a.filter { it != WILDCARD_TOKEN }.toSet()
        val literalsB = b.filter { it != WILDCARD_TOKEN }.toSet()
        if (literalsA.isEmpty() && literalsB.isEmpty()) return 1.0
        val intersect = literalsA.intersect(literalsB).size
        val union = literalsA.union(literalsB).size
        if (union == 0) return 0.0
        return intersect.toDouble() / union.toDouble()
    }

    /**
     * Merge the cluster's existing template with a new candidate message. Walks
     * the candidate token-by-token, keeping each token that also appears as a
     * stable literal in the existing template (in any position) and replacing
     * the rest with a single collapsed `<*>` wildcard. Adjacent wildcards
     * collapse into one — that keeps slot count stable so runtime extraction
     * doesn't end up with empty slots when a body has fewer variable tokens
     * than the merged pattern's slot positions.
     */
    private fun mergeTemplates(existing: List<String>, candidate: List<String>): List<String> {
        val existingLiterals = existing.filter { it != WILDCARD_TOKEN }.toSet()
        val merged = mutableListOf<String>()
        var prevWildcard = false
        for (tok in candidate) {
            val stable = tok != WILDCARD_TOKEN && tok in existingLiterals
            if (stable) {
                merged.add(tok)
                prevWildcard = false
            } else if (!prevWildcard) {
                merged.add(WILDCARD_TOKEN)
                prevWildcard = true
            }
        }
        return merged
    }

    private fun patternToTokens(pattern: String): List<String> =
        pattern.split(Regex("\\s+")).filter { it.isNotBlank() }
}
