package com.ivy.sms.data

import com.ivy.sms.domain.model.SmsMessage
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.time.Instant

class DrainParserTest {

    private fun msg(body: String, idx: Int = 0): SmsMessage = SmsMessage(
        dedupKey = "dedup-$idx",
        senderId = "TestBank",
        body = body,
        timestamp = Instant.ofEpochMilli(1_700_000_000_000L + idx),
    )

    @Test
    fun deterministicClustering_sameInputs_sameTemplates() {
        val parser1 = DrainParser()
        val parser2 = DrainParser()
        val inputs = listOf(
            "Purchase of 12.34 at Coffee Shop",
            "Purchase of 56.78 at Coffee Shop",
            "Purchase of 99.00 at Coffee Shop",
        )
        val pat1 = inputs.mapIndexed { i, b -> parser1.consume(msg(b, i)).templatePattern }
        val pat2 = inputs.mapIndexed { i, b -> parser2.consume(msg(b, i)).templatePattern }
        pat1 shouldBe pat2
    }

    @Test
    fun mergesIntoSingleCluster_whenOnlyAmountChanges() {
        val parser = DrainParser()
        val ids = listOf(
            "Purchase of 12.34 at Coffee Shop",
            "Purchase of 56.78 at Coffee Shop",
        ).mapIndexed { i, body ->
            parser.consume(msg(body, i)).templateId
        }
        ids.toSet() shouldHaveSize 1
    }

    @Test
    fun substitutesWildcardAtDisagreementPosition() {
        // Drain depth=4 keys descend on (count, token0, token1, token2). Disagreement
        // must be at index >= 3 for the messages to land at the same leaf and merge.
        val parser = DrainParser()
        parser.consume(msg("Order of food at Cafe", 0))
        val cluster = parser.consume(msg("Order of food at Diner", 1))
        cluster.templatePattern[4] shouldBe WILDCARD_TOKEN
        cluster.templatePattern[0] shouldBe "Order"
        cluster.templatePattern[3] shouldBe "at"
    }

    @Test
    fun creatsDistinctClusters_whenTokenCountDiffers() {
        val parser = DrainParser()
        val a = parser.consume(msg("Three short tokens", 0)).templateId
        val b = parser.consume(msg("Four tokens here please", 1)).templateId
        (a == b) shouldBe false
    }
}
