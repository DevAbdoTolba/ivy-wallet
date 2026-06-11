package com.ivy.sms.data

import io.kotest.matchers.shouldBe
import org.junit.Test

class SenderSummaryRankingTest {

    @Test
    fun ranksByNewestMessageFirst() {
        val ranked = listOf(
            SenderSummary("Old-Bank", messageCount = 900, lastMessageEpochMillis = 1_000L),
            SenderSummary("Fresh-Bank", messageCount = 3, lastMessageEpochMillis = 9_000L),
            SenderSummary("Mid-Bank", messageCount = 50, lastMessageEpochMillis = 5_000L),
        ).rankedByRecency()

        ranked.map { it.senderId } shouldBe listOf("Fresh-Bank", "Mid-Bank", "Old-Bank")
    }

    @Test
    fun tiesOnRecency_breakByMessageCount() {
        val ranked = listOf(
            SenderSummary("Quiet", messageCount = 2, lastMessageEpochMillis = 5_000L),
            SenderSummary("Chatty", messageCount = 40, lastMessageEpochMillis = 5_000L),
        ).rankedByRecency()

        ranked.map { it.senderId } shouldBe listOf("Chatty", "Quiet")
    }

    @Test
    fun unknownDates_sinkToTheBottom() {
        val ranked = listOf(
            SenderSummary("NoDate", messageCount = 99, lastMessageEpochMillis = 0L),
            SenderSummary("Dated", messageCount = 1, lastMessageEpochMillis = 1L),
        ).rankedByRecency()

        ranked.first().senderId shouldBe "Dated"
    }
}
