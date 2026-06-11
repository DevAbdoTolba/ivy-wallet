package com.ivy.sms.ui.pending

import io.kotest.matchers.shouldBe
import org.junit.Test

class HumanizeReasonTest {

    @Test
    fun unmappedTemplate_keepsNeedsRolesLabel() {
        humanizeReason("TEMPLATE_NOT_MAPPED", templateActive = false) shouldBe
            "Needs roles assigned"
    }

    @Test
    fun activeTemplate_rendersPartiallyMapped_forStaleNotMappedReason() {
        // Re-quarantines hit the dedup index, so an item keeps its original
        // TEMPLATE_NOT_MAPPED reason even after the user maps the template —
        // the label must not claim roles are missing.
        humanizeReason("TEMPLATE_NOT_MAPPED", templateActive = true) shouldBe
            "Partially mapped — couldn't align"
    }

    @Test
    fun activeTemplate_rendersPartiallyMapped_forAlignmentQuarantines() {
        humanizeReason(
            "AMOUNT_NOT_PARSEABLE:token alignment failure",
            templateActive = true,
        ) shouldBe "Partially mapped — couldn't align"
    }

    @Test
    fun senderAndCurrencyReasons_unaffectedByTemplateState() {
        humanizeReason("SENDER_NOT_LINKED", templateActive = true) shouldBe
            "Sender not linked to a wallet"
        humanizeReason("CURRENCY_MISMATCH", templateActive = true) shouldBe
            "Currency doesn't match the wallet"
    }

    @Test
    fun autoRouteDisabled_rendersHeldForApproval_regardlessOfTemplateState() {
        // AUTO_ROUTE_DISABLED items always have ACTIVE templates — the
        // "Partially mapped" remap must NOT swallow this reason.
        humanizeReason("AUTO_ROUTE_DISABLED", templateActive = true) shouldBe
            "Held for approval — auto-import is off"
        humanizeReason("AUTO_ROUTE_DISABLED", templateActive = false) shouldBe
            "Held for approval — auto-import is off"
    }

    @Test
    fun unknownReason_fallsBack() {
        humanizeReason("SOMETHING_NEW", templateActive = false) shouldBe
            "Needs your attention"
    }
}
