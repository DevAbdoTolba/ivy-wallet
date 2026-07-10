package com.ivy.sms.domain.usecase

import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.sms.domain.model.WildcardSlot
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.Test
import java.util.UUID

/**
 * The bilingual role-inference pass. Precision beats recall: a wrong guess
 * auto-creates wrong transactions, a missing guess costs one manual tap —
 * so every ambiguity case must come out unconfident.
 */
class InferSlotRolesTest {

    private fun slot(position: Int, example: String) = WildcardSlot(
        id = WildcardId(UUID.randomUUID()),
        positionInPattern = position,
        contextSnippet = "",
        exampleValue = example,
        role = WildcardRole.Unmapped,
    )

    @Test
    fun `arabic card debit infers every role confidently`() {
        // The bank-alahly card-debit shape (annotated corpus cluster 14).
        val pattern = "تم خصم <*> من بطاقة المدفوعة مقدماً رقم <*> عند <*> <*> " +
            "يوم <*> الساعه <*> المتاح <*> جم للمزيد إتصل ب 19623"
        val slots = listOf(
            slot(2, "143.00EGP"),
            slot(8, "3588"),
            slot(10, "UNION"),
            slot(11, "MARKETS"),
            slot(13, "28/12"),
            slot(15, "15:52"),
            slot(17, "260.24"),
        )

        val inferred = inferSlotRoles(pattern, slots)

        val byPosition = slots.associate { it.positionInPattern to inferred.roleBySlotId[it.id] }
        byPosition[2] shouldBe WildcardRole.Expense
        byPosition[8] shouldBe WildcardRole.Ignored
        byPosition[10] shouldBe WildcardRole.Merchant
        byPosition[11] shouldBe WildcardRole.Merchant
        byPosition[13] shouldBe WildcardRole.DateOnly
        byPosition[15] shouldBe WildcardRole.TimeOnly
        byPosition[17] shouldBe WildcardRole.CurrentTotal
        inferred.confident.shouldBeTrue()
    }

    @Test
    fun `arabic incoming transfer infers income direction`() {
        // The bank-alahly instant-transfer-in shape (annotated cluster 20).
        val pattern = "تم إضافة تحويل لحظي لبطاقتكم مسبقة الدفع بمبلغ <*> جم من <*> <*> " +
            "رقم مرجعي <*> يوم <*> الساعة <*> للمزيد اتصل بـ 19623"
        val slots = listOf(
            slot(8, "4000.00"),
            slot(11, "احمدمحمد"),
            slot(12, "احمدطلبه"),
            slot(15, "835583213664"),
            slot(17, "04-21"),
            slot(19, "13:44"),
        )

        val inferred = inferSlotRoles(pattern, slots)

        val byPosition = slots.associate { it.positionInPattern to inferred.roleBySlotId[it.id] }
        byPosition[8] shouldBe WildcardRole.Income
        byPosition[11] shouldBe WildcardRole.Merchant
        byPosition[12] shouldBe WildcardRole.Merchant
        byPosition[15] shouldBe WildcardRole.Ignored
        byPosition[17] shouldBe WildcardRole.DateOnly
        byPosition[19] shouldBe WildcardRole.TimeOnly
        inferred.confident.shouldBeTrue()
    }

    @Test
    fun `english debit with card and balance infers confidently`() {
        val pattern = "Debit <*> from card <*> available balance <*>"
        val slots = listOf(
            slot(1, "500.00"),
            slot(4, "9917"),
            slot(7, "1,200.50"),
        )

        val inferred = inferSlotRoles(pattern, slots)

        val byPosition = slots.associate { it.positionInPattern to inferred.roleBySlotId[it.id] }
        byPosition[1] shouldBe WildcardRole.Expense
        byPosition[4] shouldBe WildcardRole.Ignored
        byPosition[7] shouldBe WildcardRole.CurrentTotal
        inferred.confident.shouldBeTrue()
    }

    @Test
    fun `no direction keyword means no amount guess and no confidence`() {
        val pattern = "Transaction <*> completed"
        val slots = listOf(slot(1, "99.00"))

        val inferred = inferSlotRoles(pattern, slots)

        inferred.roleBySlotId[slots.single().id] shouldBe null
        inferred.confident.shouldBeFalse()
    }

    @Test
    fun `two bare numbers with no context are never guessed`() {
        val pattern = "Info <*> and <*> today paid"
        val slots = listOf(slot(1, "100.00"), slot(3, "200.00"))

        val inferred = inferSlotRoles(pattern, slots)

        // Direction exists ("paid") but WHICH number is the amount is
        // unanswerable — neither may be guessed.
        inferred.roleBySlotId[slots[0].id] shouldBe null
        inferred.roleBySlotId[slots[1].id] shouldBe null
        inferred.confident.shouldBeFalse()
    }

    @Test
    fun `pre-bound roles are respected and counted`() {
        val pattern = "Paid <*> fees to Cafe branch <*>"
        val bound = slot(1, "99").copy(role = WildcardRole.Expense)
        val fresh = slot(6, "uptown")

        val inferred = inferSlotRoles(pattern, listOf(bound, fresh))

        // The pre-bound Expense is untouched and no second amount emerges;
        // the fresh free-text slot defaults to Merchant.
        inferred.roleBySlotId[bound.id] shouldBe null
        inferred.roleBySlotId[fresh.id] shouldBe WildcardRole.Merchant
        inferred.confident.shouldBeTrue()
    }
}
