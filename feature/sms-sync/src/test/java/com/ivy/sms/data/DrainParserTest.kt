package com.ivy.sms.data

import com.ivy.sms.domain.model.SmsMessage
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.domain.model.WildcardSlot
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.time.Instant
import java.util.UUID

class DrainParserTest {

    private fun msg(body: String, idx: Int = 0, sender: String = "TestBank"): SmsMessage = SmsMessage(
        dedupKey = "dedup-$idx",
        senderId = sender,
        body = body,
        timestamp = Instant.ofEpochMilli(1_700_000_000_000L + idx),
    )

    private fun template(
        id: UUID,
        pattern: String,
        sender: String = "TestBank",
        matchCount: Int = 1,
        slots: List<WildcardSlot> = emptyList(),
    ): SmsTemplate = SmsTemplate(
        id = SmsTemplateId(id),
        pattern = pattern,
        exampleBody = pattern,
        wildcardSlots = slots,
        state = TemplateState.UNMAPPED,
        senderIdHint = sender,
        firstSeen = Instant.EPOCH,
        lastSeen = Instant.EPOCH,
        matchCount = matchCount,
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
        // Digit-collapsed views are identical ([Purchase, of, <*>, at, Coffee,
        // Shop]) so positional agreement = 6/6 = 1.0 >= 0.6.
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
        // Descent buckets on sender → token count (7) → first PREFIX_DEPTH=5
        // stable tokens [order, of, nice, hot, food] — identical for both bodies,
        // so they reach the same leaf. Positional agreement = 6 matching
        // positions / 7 ≈ 0.857 >= 0.6, so they merge; the disagreement at
        // index 6 becomes the wildcard. (The disagreement must sit past the
        // first 5 stable tokens or descent splits the bodies into two leaves.)
        val parser = DrainParser()
        parser.consume(msg("Order of nice hot food at Cafe", 0))
        val cluster = parser.consume(msg("Order of nice hot food at Diner", 1))
        cluster.templatePattern[6] shouldBe WILDCARD_TOKEN
        cluster.templatePattern[0] shouldBe "Order"
        cluster.templatePattern[5] shouldBe "at"
    }

    @Test
    fun creatsDistinctClusters_whenTokenCountDiffers() {
        val parser = DrainParser()
        val a = parser.consume(msg("Three short tokens", 0)).templateId
        val b = parser.consume(msg("Four tokens here please", 1)).templateId
        (a == b) shouldBe false
    }

    @Test
    fun rebuildFromTemplates_digitInPrefix_siblingStillMatchesAfterReseed() {
        // Regression for the consume/rebuild descent asymmetry: first-sample
        // patterns persist raw digits ("100" below sits inside the first 5
        // stable tokens). Rebuild used to descend on the raw literal while
        // messages descend digit-collapsed, orphaning the template and minting
        // a duplicate template per amount on every reseed.
        val parser = DrainParser()
        val first = parser.consume(msg("Charged 100 EGP at Cafe Centro", 0))
        val persisted = template(
            id = first.templateId,
            pattern = first.templatePattern.joinToString(" "),
            matchCount = first.messageCount,
        )
        parser.rebuildFromTemplates(listOf(persisted))
        val sibling = parser.consume(msg("Charged 250 EGP at Cafe Centro", 1))
        sibling.templateId shouldBe first.templateId
    }

    @Test
    fun positionalSimilarity_rejectsCrossFormatMerge_despiteSharedVocabulary() {
        // Both bodies: 10 tokens, shared 5-token prefix (same leaf). Literal
        // sets overlap heavily: 8 shared literals, union 11 (the raw pattern
        // side kept "10") → old set-Jaccard scored 8/11 ≈ 0.73 >= 0.4 and
        // merged them. Positional agreement: positions 0-4 match, positions
        // 5-9 all disagree ("fee 10 charged service tax" vs "tax service paid
        // 20 fee" — the digit tokens collapse to <*> at different indices)
        // = 5/10 = 0.5 < 0.6 → distinct clusters.
        val parser = DrainParser()
        val a = parser.consume(msg("Dear customer your account update fee 10 charged service tax", 0))
        val b = parser.consume(msg("Dear customer your account update tax service paid 20 fee", 1))
        (a.templateId == b.templateId) shouldBe false
    }

    @Test
    fun bestMatch_tieBreaksOnHigherMessageCount() {
        // Leaf: sender → count 10 → prefix [alpha, beta, gamma, delta, epsilon].
        // Clusters A and B disagree in 5 of 10 positions (5/10 = 0.5 < 0.6), so
        // they coexist in the leaf. The probe matches each in exactly 7 of 10
        // positions (the 5-token prefix + two tail tokens each) — a 0.7 vs 0.7
        // tie. The old first-at-max rule picked whichever cluster was inserted
        // first (A); the cluster with more samples (B, consumed twice) must win.
        val parser = DrainParser()
        val a = parser.consume(msg("alpha beta gamma delta epsilon one two three four five", 0))
        parser.consume(msg("alpha beta gamma delta epsilon six seven eight nine ten", 1))
        val b = parser.consume(msg("alpha beta gamma delta epsilon six seven eight nine ten", 2))
        b.messageCount shouldBe 2
        val probe = parser.consume(msg("alpha beta gamma delta epsilon one two eight nine zebra", 3))
        probe.templateId shouldBe b.templateId
        (probe.templateId == a.templateId) shouldBe false
    }

    @Test
    fun bestMatch_tieBreaksOnSmallerTemplateId_whenMessageCountsEqual() {
        // Same tie construction as above (both clusters score 7/10 = 0.7) but
        // with equal matchCounts, seeded in reverse-UUID order to prove that
        // insertion order no longer decides: the smaller templateId must win.
        val parser = DrainParser()
        val smaller = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val larger = UUID.fromString("00000000-0000-0000-0000-000000000002")
        parser.rebuildFromTemplates(
            listOf(
                template(larger, "alpha beta gamma delta epsilon six seven eight nine ten"),
                template(smaller, "alpha beta gamma delta epsilon one two three four five"),
            ),
        )
        val probe = parser.consume(msg("alpha beta gamma delta epsilon one two eight nine zebra", 0))
        probe.templateId shouldBe smaller
    }

    @Test
    fun mergeKeepsAdjacentWildcardPositions_whenLengthsMatch() {
        // Two adjacent variable positions (1 and 2). The old run-collapse merge
        // collapsed them into ONE <*>, shrinking the pattern to 6 tokens and
        // shifting every later slot position left by one. Same-length merges
        // must be token-by-token so positions survive.
        val parser = DrainParser()
        parser.consume(msg("Paid 10 50 to Cafe Centro today", 0))
        val cluster = parser.consume(msg("Paid 99 75 to Cafe Centro today", 1))
        cluster.templatePattern shouldHaveSize 7
        cluster.templatePattern[1] shouldBe WILDCARD_TOKEN
        cluster.templatePattern[2] shouldBe WILDCARD_TOKEN
        cluster.templatePattern[3] shouldBe "to"
        // exampleValues rebuilt from the merged sample, keyed by merged positions.
        cluster.exampleValues shouldBe mapOf(1 to "99", 2 to "75")
    }

    @Test
    fun merge_rebuildsExampleValues_andPrunesStaleKeys() {
        // Legacy persisted slot at position 4 ("stale") points at a literal
        // position of the pattern — rebuilding examples from scratch on merge
        // must drop it and key the fresh value at the actual wildcard position.
        val parser = DrainParser()
        val id = UUID.randomUUID()
        val staleSlot = WildcardSlot(
            id = WildcardId(UUID.randomUUID()),
            positionInPattern = 4,
            contextSnippet = "",
            exampleValue = "stale",
            role = WildcardRole.Unmapped,
        )
        parser.rebuildFromTemplates(
            listOf(template(id, "Paid 10 fees to Cafe", slots = listOf(staleSlot))),
        )
        val cluster = parser.consume(msg("Paid 25 fees to Cafe", 1))
        cluster.templateId shouldBe id
        cluster.exampleValues shouldBe mapOf(1 to "25")
    }

    @Test
    fun frozenTemplate_consumeCounts_butNeverMutatesTheInMemoryPattern() {
        // ACTIVE/BLACKLISTED/PENDING_REVIEW templates are frozen on disk
        // (DiscoverTemplatesUseCase) — the in-memory cluster must not drift
        // either: a drifted pattern absorbs near-format messages that then
        // fail alignment against the UNCHANGED persisted pattern and stick
        // as "Partially mapped" under the wrong template.
        // Disagreement ("tonight" vs "today") sits PAST the first 5 stable
        // tokens [paid, fees, to, cafe, centro], so descent reaches the same
        // leaf (see PREFIX_DEPTH note above).
        val parser = DrainParser()
        val id = UUID.randomUUID()
        parser.rebuildFromTemplates(
            listOf(
                template(id, "Paid <*> fees to Cafe Centro today")
                    .copy(state = TemplateState.ACTIVE),
            ),
        )
        // An unfrozen merge would rewrite position 6 to <*> ("tonight"
        // disagrees with the literal "today"). Score 6/7 ≈ 0.857 >= 0.6, so
        // the message still matches and counts.
        val cluster = parser.consume(msg("Paid 25 fees to Cafe Centro tonight", 1))
        cluster.templateId shouldBe id
        cluster.templatePattern shouldBe
            listOf("Paid", WILDCARD_TOKEN, "fees", "to", "Cafe", "Centro", "today")
        cluster.messageCount shouldBe 2
        cluster.exampleValues shouldBe emptyMap<Int, String>()
    }

    @Test
    fun senderPartitioning_identicalBodies_differentSenders_distinctClusters() {
        val parser = DrainParser()
        val a = parser.consume(msg("Spent 50 EGP at Cafe", 0, sender = "BankA"))
        val b = parser.consume(msg("Spent 75 EGP at Cafe", 1, sender = "BankB"))
        (a.templateId == b.templateId) shouldBe false
    }

    @Test
    fun rebuildFromTemplates_partitionsBySenderIdHint() {
        // Same pattern persisted for two senders: a BankB message must match
        // the BankB template. Without sender partitioning both templates would
        // share a leaf, tie at 1.0 with equal matchCounts, and the smaller
        // templateId (BankA's) would win the tie-break.
        val parser = DrainParser()
        val bankA = UUID.fromString("00000000-0000-0000-0000-00000000000a")
        val bankB = UUID.fromString("00000000-0000-0000-0000-00000000000b")
        parser.rebuildFromTemplates(
            listOf(
                template(bankA, "Spent 50 EGP at Cafe", sender = "BankA"),
                template(bankB, "Spent 50 EGP at Cafe", sender = "BankB"),
            ),
        )
        val matched = parser.consume(msg("Spent 99 EGP at Cafe", 0, sender = "BankB"))
        matched.templateId shouldBe bankB
    }

    @Test
    fun caseInsensitiveClustering_mergesCaseVariants_keepsStoredCasing() {
        // Descent keys, similarity, and merge equality all compare lowercase
        // (sender id included); the stored pattern keeps the first sample's
        // casing and only the truly variable position (the amount) becomes a
        // wildcard.
        val parser = DrainParser()
        val first = parser.consume(msg("Spent 10 EGP at Cafe Centro", 0))
        val second = parser.consume(msg("spent 20 egp AT Cafe Centro", 1))
        second.templateId shouldBe first.templateId
        second.templatePattern shouldBe listOf("Spent", WILDCARD_TOKEN, "EGP", "at", "Cafe", "Centro")
        val third = parser.consume(msg("SPENT 30 EGP at Cafe Centro", 2, sender = "TESTBANK"))
        third.templateId shouldBe first.templateId
    }
}
