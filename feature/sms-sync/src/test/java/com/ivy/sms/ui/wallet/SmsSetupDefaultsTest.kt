package com.ivy.sms.ui.wallet

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.Test

class SmsSetupDefaultsTest {

    @Test
    fun defaultSender_isFirstOfRecencyRankedList() {
        val senders = listOf(
            SenderOptionViewState("Fresh-Bank", messageCount = 3, lastMessageEpochMillis = 9_000L),
            SenderOptionViewState("Old-Bank", messageCount = 900, lastMessageEpochMillis = 1_000L),
        )

        defaultSender(senders) shouldBe "Fresh-Bank"
    }

    @Test
    fun defaultSender_nullWhenNoUnlinkedSenders() {
        defaultSender(emptyList()).shouldBeNull()
    }

    @Test
    fun periodChips_preselect30Days() {
        DEFAULT_PERIOD_CHIP.days shouldBe 30
        SmsSetupViewState().selectedPeriod shouldBe DEFAULT_PERIOD_CHIP
    }

    @Test
    fun periodChips_offerTheFiveAgreedWindows() {
        SETUP_PERIOD_CHIPS.map { it.days } shouldBe listOf(7, 30, 90, 365, null)
    }

    @Test
    fun autoRoute_defaultsOn() {
        SmsSetupViewState().autoRoute shouldBe true
    }

    @Test
    fun linkConflictError_isHumanized() {
        humanizeLinkError(
            "LINK_CONFLICT: sender 'VF-Cash' already linked to wallet 'Cash'",
            "VF-Cash",
        ) shouldBe "\"VF-Cash\" is already feeding another wallet — sender 'VF-Cash' already linked to wallet 'Cash'"
    }

    @Test
    fun unknownError_passesThrough() {
        humanizeLinkError("WEIRD_FAILURE", "VF-Cash") shouldBe "WEIRD_FAILURE"
    }
}
