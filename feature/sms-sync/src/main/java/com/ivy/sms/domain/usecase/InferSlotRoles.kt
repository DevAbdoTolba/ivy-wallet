package com.ivy.sms.domain.usecase

import com.ivy.sms.data.WILDCARD_TOKEN
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.domain.model.WildcardSlot
import com.ivy.sms.domain.model.isAmountRole
import com.ivy.sms.domain.parser.AmountParser

/**
 * Result of the automatic role-inference pass over a template's wildcards.
 *
 * [confident] is the auto-activation gate: true ONLY when the inference is
 * complete and unambiguous — a direction keyword decided Income vs Expense,
 * exactly one wildcard was classified as the amount, and every slot received
 * a non-Unmapped role. Anything less and the template stays UNMAPPED with
 * the inferred roles saved as PRE-FILLED defaults for the mapping screen.
 */
data class InferredRoles(
    val roleBySlotId: Map<WildcardId, WildcardRole>,
    val confident: Boolean,
)

// ---------------------------------------------------------------------------
// Bilingual keyword tables (Arabic + English). Tokens are compared after
// trimming edge punctuation and lowercasing. These decide DEFAULTS the user
// can always override in the mapping screen — precision beats recall here:
// a missing keyword just means one more manual tap, a wrong keyword means a
// wrong auto-created transaction.
// ---------------------------------------------------------------------------

/** Money LEFT the account → the amount slot becomes Expense. */
private val EXPENSE_KEYWORDS = setOf(
    // Arabic
    "خصم", "سحب", "شراء", "دفع", "مدين",
    // English
    "debit", "debited", "paid", "payment", "purchase", "purchased",
    "withdrawn", "withdrawal", "spent", "charged",
)

/** Money ENTERED the account → the amount slot becomes Income. */
private val INCOME_KEYWORDS = setOf(
    // Arabic (hamza and hamza-less spellings both appear in the wild)
    "إضافة", "اضافة", "إيداع", "ايداع", "استلام", "استلمت", "وصلك", "دائن",
    // English
    "credit", "credited", "received", "deposit", "deposited", "added",
    "refund", "refunded",
)

/** Literal right before a parseable value ⇒ that value is the balance. */
private val BALANCE_CONTEXT = setOf(
    "المتاح", "الرصيد", "رصيد", "رصيدك", "رصيدكم",
    "balance", "available", "avail", "bal",
)

/** Literal right before a parseable value ⇒ that value is a fee. */
private val FEE_CONTEXT = setOf(
    "رسوم", "عمولة", "مصاريف", "بمصاريف",
    "fee", "fees",
)

/** Literal right before a digit run ⇒ card / reference / account number. */
private val IDENTIFIER_CONTEXT = setOf(
    "رقم", "مرجعي", "بطاقة", "بطاقتكم", "لبطاقتكم", "حساب", "حسابكم",
    "ref", "reference", "card", "account", "a/c", "no", "no.",
)

/** Literal right before a parseable value ⇒ that value is THE amount. */
private val AMOUNT_CONTEXT = setOf(
    "بمبلغ", "مبلغ", "بقيمة", "قيمة", "amount", "of",
) + EXPENSE_KEYWORDS + INCOME_KEYWORDS

/** "10:30" or "10:30:45". */
private val TIME_REGEX = Regex("^\\d{1,2}:\\d{2}(:\\d{2})?$")

/** "28/12", "04-21", "2026-04-28", and dotted FULL dates ("28.12.2026").
 *  A single dot is NOT a date separator — "99.00" is a decimal amount. */
private val DATE_REGEX =
    Regex("^(?:\\d{1,4}[-/]\\d{1,2}(?:[-/]\\d{1,4})?|\\d{1,2}\\.\\d{1,2}\\.\\d{2,4})$")

/** Card/reference numbers: an unbroken digit run with no decimal separator. */
private val PURE_DIGIT_RUN = Regex("^\\d{6,}$")

/**
 * Amount-SHAPED value: starts with a digit, digits/separators throughout,
 * optionally a short glued currency suffix ("143.00EGP", "٨٠ج"). This is
 * deliberately stricter than [AmountParser.parseAmount] (which finds a number
 * ANYWHERE, so merchant fragments like "ATM879" would count).
 */
private val AMOUNTISH = Regex("^[+-]?\\d[\\d.,\\u00A0 ]*(?:[\\p{L}.]{1,8})?$")

private val EDGE_PUNCTUATION = charArrayOf('.', ',', ':', ';', '!', '?', '،', '؛', '"', '\'', '(', ')')

private fun clean(token: String): String = token.trim(*EDGE_PUNCTUATION).lowercase()

private fun isAmountish(value: String): Boolean =
    AMOUNTISH.matches(value.trim()) && AmountParser.parseAmount(value).isRight()

/** Up to [max] literal tokens straight before [position], nearest first.
 *  Stops at another wildcard — context across a variable region is noise. */
private fun precedingLiterals(tokens: List<String>, position: Int, max: Int = 2): List<String> {
    val out = mutableListOf<String>()
    var i = position - 1
    while (i >= 0 && out.size < max) {
        val tok = tokens[i]
        if (tok == WILDCARD_TOKEN) break
        out.add(clean(tok))
        i--
    }
    return out
}

/**
 * Infers a role for every Unmapped slot from the pattern's literal context
 * and each slot's example value. Slots that already carry a role (a user's
 * pick, or a previous inference pass) are respected: they are never
 * overwritten and their unique roles count as taken.
 *
 * Design rule: VALUE evidence first (a value shaped like a time IS a time),
 * literal context second (what word introduces the value), and the amount
 * slot is chosen last from whatever parseable slots remain.
 */
@Suppress("CyclomaticComplexMethod", "LongMethod")
fun inferSlotRoles(pattern: String, slots: List<WildcardSlot>): InferredRoles {
    val tokens = pattern.split(Regex("\\s+")).filter { it.isNotBlank() }
    val out = mutableMapOf<WildcardId, WildcardRole>()

    // Unique roles already taken by pre-bound slots.
    val taken = slots.filter { it.role != WildcardRole.Unmapped }
        .map { it.role }
        .toMutableSet()
    var ambiguous = false

    fun claim(id: WildcardId, role: WildcardRole, unique: Boolean): Boolean {
        if (unique && role in taken) {
            ambiguous = true
            return false
        }
        out[id] = role
        if (unique) taken.add(role)
        return true
    }

    // Direction from the pattern's literals: exactly one keyword family may
    // fire. Both (or neither) = ambiguous, the user decides.
    val literals = tokens.filter { it != WILDCARD_TOKEN }.map { clean(it) }
    val expenseHit = literals.any { it in EXPENSE_KEYWORDS }
    val incomeHit = literals.any { it in INCOME_KEYWORDS }
    val direction: WildcardRole? = when {
        expenseHit && !incomeHit -> WildcardRole.Expense
        incomeHit && !expenseHit -> WildcardRole.Income
        else -> null
    }

    val amountCandidates = mutableListOf<Pair<WildcardSlot, Boolean>>() // slot to hasAmountContext
    for (slot in slots.filter { it.role == WildcardRole.Unmapped }) {
        val value = slot.exampleValue.trim()
        val context = precedingLiterals(tokens, slot.positionInPattern)
        when {
            value.isEmpty() -> ambiguous = true

            TIME_REGEX.matches(value) -> claim(slot.id, WildcardRole.TimeOnly, unique = true)

            DATE_REGEX.matches(value) -> claim(slot.id, WildcardRole.DateOnly, unique = true)

            // Identifier BEFORE amount: card/ref digit runs parse as huge
            // "amounts" otherwise ("835583213664").
            PURE_DIGIT_RUN.matches(value) ||
                (context.any { it in IDENTIFIER_CONTEXT } && value.all { it.isDigit() }) ->
                out[slot.id] = WildcardRole.Ignored

            isAmountish(value) -> when {
                context.any { it in BALANCE_CONTEXT } ->
                    claim(slot.id, WildcardRole.CurrentTotal, unique = true)
                context.any { it in FEE_CONTEXT } ->
                    claim(slot.id, WildcardRole.TransactionFee, unique = true)
                else -> amountCandidates.add(slot to context.any { it in AMOUNT_CONTEXT })
            }

            // Anything with letters is free text — merchant / payer name.
            value.any { it.isLetter() } -> out[slot.id] = WildcardRole.Merchant

            else -> ambiguous = true
        }
    }

    // THE amount: one candidate, or the single one introduced by an amount
    // keyword ("بمبلغ 4000.00", "Debit 143.00EGP"). More than one plausible
    // amount with no tiebreak = ambiguous, never a guess.
    val amountSlot: WildcardSlot? = when {
        amountCandidates.size == 1 -> amountCandidates.single().first
        amountCandidates.count { it.second } == 1 -> {
            // The keyword-less leftovers are opaque numbers; leaving them
            // Unmapped would block confidence, and rightly so — unless they
            // are plainly identifiers, which the identifier rule already took.
            ambiguous = true
            amountCandidates.first { it.second }.first
        }
        amountCandidates.isEmpty() -> null
        else -> {
            ambiguous = true
            null
        }
    }
    if (amountSlot != null) {
        if (direction != null) {
            claim(amountSlot.id, direction, unique = true)
        } else {
            ambiguous = true
        }
    }

    val everySlotClassified = slots.all { slot ->
        slot.role != WildcardRole.Unmapped ||
            (out[slot.id] != null && out[slot.id] != WildcardRole.Unmapped)
    }
    val exactlyOneAmount = (
        slots.count { it.role.isAmountRole() } +
            out.values.count { it.isAmountRole() }
        ) == 1

    return InferredRoles(
        roleBySlotId = out,
        confident = !ambiguous && direction != null && everySlotClassified && exactlyOneAmount,
    )
}
