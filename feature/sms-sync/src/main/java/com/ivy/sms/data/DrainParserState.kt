package com.ivy.sms.data

import java.util.UUID

internal const val WILDCARD_TOKEN: String = "<*>"
// Bumped 3 → 5 (2026-05-14) to make sibling SMS structures with the same
// total token count but different mid-body shape land in DIFFERENT clusters.
// The user hit this as a Frankenstein bank-alahly cluster where new-format
// ("…المتاح X جم للمزيد…") and old-format ("…المتاح X للمزيد…") debit
// messages collapsed into one template because they shared the first 3
// stable tokens. Depth 5 forces the descent past that shared prefix.
// NOTE: bodies whose first disagreement falls INSIDE the first PREFIX_DEPTH
// stable tokens descend to different leaves and can never merge — test
// fixtures must place disagreements past the prefix (see DrainParserTest).
internal const val PREFIX_DEPTH: Int = 5
// Positional-agreement (simSeq) threshold (2026-06-11). The previous 0.4 was
// tuned for set-Jaccard over unordered token sets; with order-aware simSeq,
// same-format siblings score high (only variable positions disagree — e.g.
// 6/7 ≈ 0.86 for one varying token in a 7-token body) while different formats
// sharing boilerplate vocabulary score low because positions shift (typically
// ≤ 0.5). 0.6 separates the two populations.
internal const val SIMILARITY_THRESHOLD: Double = 0.6
internal const val MAX_CHILDREN: Int = 100
// Legacy aliases — kept for binary compat with any tests still importing them.
internal const val DEFAULT_DEPTH: Int = PREFIX_DEPTH
internal const val DEFAULT_SIMILARITY_THRESHOLD: Double = SIMILARITY_THRESHOLD

/**
 * One example value per pattern position. Position keys are token indices into
 * `templatePattern`. Only positions whose pattern token is a wildcard have entries.
 * The string is the raw token from the most recent sample merged into the cluster —
 * the map is rebuilt on every merge so position keys never go stale when wildcard
 * positions change. The UI renders the value in place of a literal `<*>` so the
 * user can read the message naturally.
 */
typealias ExampleValues = Map<Int, String>

data class DrainCluster(
    val templateId: UUID,
    var templatePattern: List<String>,
    var messageCount: Int,
    /** First message body that produced this cluster (used for rollup rendering). */
    var exampleBody: String,
    /** Latest example value per wildcard position (used for in-place rendering). */
    var exampleValues: ExampleValues,
)

data class DrainNode(
    val children: MutableMap<String, DrainNode> = mutableMapOf(),
    val clusters: MutableList<DrainCluster> = mutableListOf(),
)
