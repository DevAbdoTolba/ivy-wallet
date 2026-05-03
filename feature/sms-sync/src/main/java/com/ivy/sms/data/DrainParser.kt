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
 * Pre-normalization rule (2026-04-28, unchanged):
 *   Any whitespace-bounded token containing a digit (Latin or Arabic-Indic) is replaced
 *   with `<*>`. Catches `70egp`, `190EGP`, `٦٠ج`, `$15`, dates, refcodes.
 */
@Singleton
class DrainParser @Inject constructor() {

    private val root = DrainNode()
    private val similarity: Double = SIMILARITY_THRESHOLD
    private val maxChildren: Int = MAX_CHILDREN

    @Synchronized
    fun consume(message: SmsMessage): DrainCluster {
        val rawTokens = tokenize(message.body)
        val tokens = preNormalize(rawTokens)
        val leaf = descend(tokens, createIfMissing = true)
        val best = bestMatch(leaf, tokens)
        if (best != null) {
            val merged = mergeTemplates(best.templatePattern, tokens)
            // Refresh examples for any wildcard slot that doesn't have a captured
            // value yet.
            val updatedExamples = best.exampleValues.toMutableMap()
            for (i in merged.indices) {
                if (merged[i] == WILDCARD_TOKEN && i !in updatedExamples) {
                    val raw = rawTokens.getOrNull(i) ?: continue
                    updatedExamples[i] = raw
                }
            }
            best.templatePattern = merged
            best.exampleValues = updatedExamples
            best.messageCount += 1
            return best
        }
        val examples = buildExampleValues(tokens, rawTokens)
        val newCluster = DrainCluster(
            templateId = UUID.randomUUID(),
            templatePattern = tokens,
            messageCount = 1,
            exampleBody = message.body,
            exampleValues = examples,
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

    private fun buildExampleValues(
        normalizedTokens: List<String>,
        rawTokens: List<String>,
    ): ExampleValues {
        val out = mutableMapOf<Int, String>()
        for (i in normalizedTokens.indices) {
            if (normalizedTokens[i] == WILDCARD_TOKEN) {
                val raw = rawTokens.getOrNull(i) ?: continue
                out[i] = raw
            }
        }
        return out
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
     * Merge the cluster's existing template with a new candidate message. Walks the
     * candidate token-by-token, keeping each token that ALSO appears as a stable
     * literal in the existing template (in any position), and replacing the rest with
     * a single collapsed `<*>` wildcard. Preserves the candidate's token order so the
     * `exampleBody` we render later still reads naturally.
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
