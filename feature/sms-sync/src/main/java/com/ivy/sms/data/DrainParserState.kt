package com.ivy.sms.data

import java.util.UUID

internal const val WILDCARD_TOKEN: String = "<*>"
internal const val DEFAULT_DEPTH: Int = 4
internal const val DEFAULT_SIMILARITY_THRESHOLD: Double = 0.5
internal const val MAX_CHILDREN: Int = 100

data class DrainCluster(
    val templateId: UUID,
    var templatePattern: List<String>,
    var messageCount: Int,
)

data class DrainNode(
    val children: MutableMap<String, DrainNode> = mutableMapOf(),
    val clusters: MutableList<DrainCluster> = mutableListOf(),
)
