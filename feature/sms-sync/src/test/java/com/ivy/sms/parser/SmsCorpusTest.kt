package com.ivy.sms.parser

import com.ivy.sms.data.DrainParser
import com.ivy.sms.data.SmsBodyNormalizer
import com.ivy.sms.domain.model.SmsMessage
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.domain.model.WildcardSlot
import com.ivy.sms.domain.usecase.extractWildcardValues
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assume
import org.junit.Test
import java.time.Instant
import java.util.UUID

/**
 * Corpus-driven regression test. Loads `/sms-corpus.json` (generated locally
 * by `logs/corpus-build.bat`) and exercises the parser pipeline end-to-end
 * for every real SMS the user has on their device — without needing a build,
 * device, or sync cycle.
 *
 * Two layers:
 *  1. Drain clustering regression — every body is fed through DrainParser in
 *     timestamp order. The test prints corpus-cluster ↔ drain-cluster
 *     mapping. If a future code change fragments or merges clusters
 *     unexpectedly, the print diff makes it obvious.
 *  2. Alignment + extraction — for each cluster that has an `annotation`
 *     block (expectedPattern + expectedRoles), build a synthetic
 *     SmsTemplate, run `extractWildcardValues` for every body, and assert
 *     alignment succeeds. When `messages[i].expectedValues` is present, also
 *     assert each extracted slot value matches the human-curated value.
 *
 * The corpus file is gitignored (real SMS = real PII). Each developer
 * generates their own.
 */
class SmsCorpusTest {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    @Test
    fun corpus_messages_cluster_deterministically() {
        val corpus = loadCorpusOrSkip() ?: return
        val parser = DrainParser()
        val all = corpus.clusters
            .flatMap { c -> c.messages.map { CorpusEntry(c, it) } }
            .sortedBy { it.message.timestamp }

        val drainGroups = mutableMapOf<UUID, MutableList<CorpusEntry>>()
        for (entry in all) {
            val msg = SmsMessage(
                dedupKey = "fixture-${entry.message.rowId}",
                senderId = entry.cluster.sender,
                body = SmsBodyNormalizer.normalize(entry.message.body),
                timestamp = Instant.ofEpochMilli(entry.message.timestamp),
            )
            val cluster = parser.consume(msg)
            drainGroups.getOrPut(cluster.templateId) { mutableListOf() }.add(entry)
        }

        println("=== Corpus → Drain clustering ===")
        println("Corpus clusters: ${corpus.clusters.size}")
        println("Drain clusters:  ${drainGroups.size}")
        drainGroups.entries
            .sortedByDescending { it.value.size }
            .forEachIndexed { idx, (drainKey, entries) ->
                val byCorpus = entries.groupingBy { it.cluster.id }.eachCount()
                val sample = entries.first().message.body
                    .replace(Regex("\\s+"), " ").take(70)
                println(
                    "  [$idx] drain=$drainKey size=${entries.size} " +
                        "corpus=${byCorpus} sample='$sample…'"
                )
            }
    }

    @Test
    fun annotated_clusters_align_and_extract_correctly() {
        val corpus = loadCorpusOrSkip() ?: return
        val annotated = corpus.clusters.filter { it.annotation != null }
        Assume.assumeTrue(
            "No annotated clusters yet — fill in `annotation` blocks in " +
                "sms-corpus.json to enable extraction assertions.",
            annotated.isNotEmpty(),
        )

        val alignFailures = mutableListOf<String>()
        val valueMismatches = mutableListOf<String>()

        for (cluster in annotated) {
            val ann = cluster.annotation!!
            val slotIds = ann.expectedRoles.associate {
                it.position to WildcardId(UUID.randomUUID())
            }
            val template = SmsTemplate(
                id = SmsTemplateId(UUID.randomUUID()),
                pattern = ann.expectedPattern,
                exampleBody = cluster.messages.first().body,
                wildcardSlots = ann.expectedRoles.map {
                    WildcardSlot(
                        id = slotIds.getValue(it.position),
                        positionInPattern = it.position,
                        contextSnippet = "",
                        exampleValue = "",
                        role = roleFromName(it.role),
                    )
                },
                state = TemplateState.ACTIVE,
                senderIdHint = cluster.sender,
                firstSeen = Instant.EPOCH,
                lastSeen = Instant.EPOCH,
                matchCount = cluster.messages.size,
            )
            val roleByPosition = ann.expectedRoles.associate { it.position to it.role }

            for (msg in cluster.messages) {
                val message = SmsMessage(
                    dedupKey = "fixture-${msg.rowId}",
                    senderId = cluster.sender,
                    body = SmsBodyNormalizer.normalize(msg.body),
                    timestamp = Instant.ofEpochMilli(msg.timestamp),
                )
                val extracted = extractWildcardValues(template, message)
                if (extracted == null) {
                    alignFailures.add(
                        "[${cluster.id}] row=${msg.rowId} alignment FAILED  " +
                            "body='${message.body.take(80)}…'"
                    )
                    continue
                }
                val expected = msg.expectedValues ?: continue
                for ((roleName, expectedValue) in expected) {
                    val position = roleByPosition.entries
                        .firstOrNull { it.value == roleName }?.key
                    if (position == null) {
                        valueMismatches.add(
                            "[${cluster.id}] row=${msg.rowId} role=$roleName " +
                                "not declared in expectedRoles"
                        )
                        continue
                    }
                    val slotId = slotIds.getValue(position)
                    val actual = extracted[slotId]
                    if (actual != expectedValue) {
                        valueMismatches.add(
                            "[${cluster.id}] row=${msg.rowId} role=$roleName " +
                                "expected='$expectedValue' actual='$actual'"
                        )
                    }
                }
            }
        }

        if (alignFailures.isNotEmpty()) {
            println("=== Alignment failures (${alignFailures.size}) ===")
            alignFailures.forEach { println("  - $it") }
        }
        if (valueMismatches.isNotEmpty()) {
            println("=== Value mismatches (${valueMismatches.size}) ===")
            valueMismatches.forEach { println("  - $it") }
        }
        // Surface failures as a single assertion so the test fails when ANY
        // expectation breaks, but the print blocks above show every cause.
        (alignFailures.size + valueMismatches.size) shouldBe 0
    }

    private fun loadCorpusOrSkip(): SmsCorpus? {
        val stream = javaClass.getResourceAsStream("/sms-corpus.json")
        if (stream == null) {
            println(
                "No /sms-corpus.json on test classpath — generate one via:\n" +
                    "  logs\\dump-sms.bat <senders...>\n" +
                    "  logs\\corpus-build.bat <dump-file>",
            )
            return null
        }
        return stream.bufferedReader(Charsets.UTF_8).use { reader ->
            // PowerShell 5.1's `Out-File -Encoding utf8` writes a UTF-8 BOM
            // (U+FEFF) at the start of the file. kotlinx.serialization Json
            // rejects that as an unexpected token before `{`. Strip it.
            val raw = reader.readText().removePrefix("﻿")
            json.decodeFromString<SmsCorpus>(raw)
        }
    }

    private fun roleFromName(name: String): WildcardRole = when (name) {
        "Income" -> WildcardRole.Income
        "Expense" -> WildcardRole.Expense
        "Transfer" -> WildcardRole.Transfer
        "CurrentTotal" -> WildcardRole.CurrentTotal
        "TransactionFee" -> WildcardRole.TransactionFee
        "DateFull" -> WildcardRole.DateFull
        "DateOnly" -> WildcardRole.DateOnly
        "TimeOnly" -> WildcardRole.TimeOnly
        "Merchant" -> WildcardRole.Merchant
        "Ignored" -> WildcardRole.Ignored
        else -> WildcardRole.Unmapped
    }

    @Serializable
    private data class SmsCorpus(
        val version: Int = 1,
        val totalMessages: Int = 0,
        val clusterCount: Int = 0,
        val clusters: List<CorpusCluster>,
    )

    @Serializable
    private data class CorpusCluster(
        val id: String,
        val sender: String,
        val signature: String = "",
        // PS 5.1 ConvertTo-Json emits this as a quoted string ("21") because
        // it came from a `-split` array element. Decoding as String avoids a
        // serializer mismatch without forcing a corpus regeneration.
        val tokenCount: String = "0",
        val sampleSize: Int = 0,
        val annotation: CorpusAnnotation? = null,
        val messages: List<CorpusMessage>,
    )

    @Serializable
    private data class CorpusMessage(
        val rowId: Int,
        val timestamp: Long,
        val body: String,
        val expectedValues: Map<String, String>? = null,
    )

    @Serializable
    private data class CorpusAnnotation(
        val label: String,
        val expectedPattern: String,
        val expectedRoles: List<ExpectedRole>,
    )

    @Serializable
    private data class ExpectedRole(
        val position: Int,
        val role: String,
    )

    private data class CorpusEntry(
        val cluster: CorpusCluster,
        val message: CorpusMessage,
    )
}
