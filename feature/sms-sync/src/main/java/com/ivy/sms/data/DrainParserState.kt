package com.ivy.sms.data

import java.util.UUID

internal const val WILDCARD_TOKEN: String = "<*>"
internal const val PREFIX_DEPTH: Int = 3
internal const val SIMILARITY_THRESHOLD: Double = 0.4
internal const val MAX_CHILDREN: Int = 100
// Legacy aliases — kept for binary compat with any tests still importing them.
internal const val DEFAULT_DEPTH: Int = PREFIX_DEPTH
internal const val DEFAULT_SIMILARITY_THRESHOLD: Double = SIMILARITY_THRESHOLD

/**
 * One example value per pattern position. Position keys are token indices into
 * `templatePattern`. Only positions whose pattern token is a wildcard have entries.
 * The string is the original token from the first message that occupied that slot —
 * the UI renders it in place instead of a literal `<*>` so the user can read the
 * message naturally.
 */
typealias ExampleValues = Map<Int, String>

data class DrainCluster(
    val templateId: UUID,
    var templatePattern: List<String>,
    var messageCount: Int,
    /** First message body that produced this cluster (used for rollup rendering). */
    var exampleBody: String,
    /** First example value per wildcard position (used for in-place rendering). */
    var exampleValues: ExampleValues,
)

data class DrainNode(
    val children: MutableMap<String, DrainNode> = mutableMapOf(),
    val clusters: MutableList<DrainCluster> = mutableListOf(),
)
