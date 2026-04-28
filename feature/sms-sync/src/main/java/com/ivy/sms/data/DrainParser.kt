package com.ivy.sms.data

import com.ivy.sms.domain.model.SmsMessage
import com.ivy.sms.domain.model.SmsTemplate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory port of the Drain log-parsing algorithm (He et al. 2017).
 * Fixed depth-4 tree keyed by (token-count, first-N tokens).
 * Similarity threshold 0.5; safety cap of 100 children per node.
 *
 * Pure Kotlin, no Android. Caller is responsible for dispatching to IO.
 */
@Singleton
class DrainParser @Inject constructor() {

    private val root = DrainNode()
    private val depth: Int = DEFAULT_DEPTH
    private val similarity: Double = DEFAULT_SIMILARITY_THRESHOLD
    private val maxChildren: Int = MAX_CHILDREN

    @Synchronized
    fun consume(message: SmsMessage): DrainCluster {
        val tokens = preNormalize(tokenize(message.body))
        val leaf = descend(tokens, createIfMissing = true)
        val best = bestMatch(leaf, tokens)
        if (best != null) {
            best.templatePattern = mergeTemplates(best.templatePattern, tokens)
            best.messageCount += 1
            return best
        }
        val newCluster = DrainCluster(
            templateId = UUID.randomUUID(),
            templatePattern = tokens,
            messageCount = 1,
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
            leaf.clusters.add(
                DrainCluster(
                    templateId = t.id.value,
                    templatePattern = tokens,
                    messageCount = t.matchCount,
                )
            )
        }
    }

    private fun tokenize(body: String): List<String> {
        if (body.isBlank()) return emptyList()
        return body.split(Regex("\\s+")).filter { it.isNotBlank() }
    }

    private val digitToken = Regex("^\\d+$")
    private val isoDate = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    private val slashDate = Regex("^\\d{1,2}/\\d{1,2}/\\d{2,4}$")
    private val currencyAmount = Regex("^(USD|EUR|GBP|JPY|EGP|BGN)?\\s?[+-]?\\d{1,3}(?:[,.]\\d{3})*(?:[.,]\\d{2})?$", RegexOption.IGNORE_CASE)

    private fun preNormalize(tokens: List<String>): List<String> = tokens.map { tok ->
        val cleaned = tok.trimEnd('.', ',', ':', ';')
        when {
            digitToken.matches(cleaned) -> WILDCARD_TOKEN
            isoDate.matches(cleaned) -> WILDCARD_TOKEN
            slashDate.matches(cleaned) -> WILDCARD_TOKEN
            currencyAmount.matches(cleaned) && cleaned.any { it.isDigit() } -> WILDCARD_TOKEN
            else -> tok
        }
    }

    private fun descend(tokens: List<String>, createIfMissing: Boolean): DrainNode {
        val countKey = tokens.size.toString()
        var node = childOrCreate(root, countKey, createIfMissing) ?: return root
        for (i in 0 until (depth - 1)) {
            if (i >= tokens.size) break
            val key = tokens[i].takeIf { it != WILDCARD_TOKEN } ?: WILDCARD_TOKEN
            val next = childOrCreate(node, key, createIfMissing) ?: return node
            node = next
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
            if (c.templatePattern.size != tokens.size) continue
            val score = similarity(c.templatePattern, tokens)
            if (score > bestScore) {
                bestScore = score
                best = c
            }
        }
        return if (bestScore >= similarity) best else null
    }

    private fun similarity(a: List<String>, b: List<String>): Double {
        if (a.isEmpty()) return 0.0
        var matches = 0
        for (i in a.indices) {
            if (a[i] == b[i]) matches++
        }
        return matches.toDouble() / a.size.toDouble()
    }

    private fun mergeTemplates(existing: List<String>, candidate: List<String>): List<String> {
        if (existing.size != candidate.size) return existing
        return existing.mapIndexed { i, tok ->
            if (tok == candidate[i]) tok else WILDCARD_TOKEN
        }
    }

    private fun patternToTokens(pattern: String): List<String> =
        pattern.split(Regex("\\s+")).filter { it.isNotBlank() }
}
