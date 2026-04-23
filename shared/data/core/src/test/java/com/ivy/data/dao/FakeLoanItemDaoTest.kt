package com.ivy.data.dao

import com.ivy.data.db.dao.fake.FakeLoanItemDao
import com.ivy.data.db.entity.LoanItemEntity
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

class FakeLoanItemDaoTest {
    private lateinit var dao: FakeLoanItemDao

    @Before
    fun setup() {
        dao = FakeLoanItemDao()
    }

    @Test
    fun `find all by contactId - returns filtered and sorted items`() = runTest {
        // given
        val contactId = UUID.randomUUID()
        val otherContactId = UUID.randomUUID()
        val item1 = LoanItemEntity(
            contactId = contactId,
            amount = 10.0,
            title = "Coffee",
            createdAt = Instant.now().minusSeconds(100)
        )
        val item2 = LoanItemEntity(
            contactId = contactId,
            amount = 20.0,
            title = "Lunch",
            createdAt = Instant.now()
        )
        val otherItem = LoanItemEntity(
            contactId = otherContactId,
            amount = 50.0,
            title = "Rent",
            createdAt = Instant.now()
        )

        // when
        dao.save(item1)
        dao.save(item2)
        dao.save(otherItem)
        val res = dao.findAllByContactId(contactId).first()

        // then
        res shouldBe listOf(item2, item1)
    }

    @Test
    fun `calculate unsettled sum - returns sum of unsettled items`() = runTest {
        // given
        val contactId = UUID.randomUUID()
        val item1 = LoanItemEntity(
            contactId = contactId,
            amount = 10.0,
            title = "Item 1",
            isSettled = false,
            createdAt = Instant.now()
        )
        val item2 = LoanItemEntity(
            contactId = contactId,
            amount = 20.0,
            title = "Item 2",
            isSettled = true,
            createdAt = Instant.now()
        )
        val item3 = LoanItemEntity(
            contactId = contactId,
            amount = 5.0,
            title = "Item 3",
            isSettled = false,
            createdAt = Instant.now()
        )

        // when
        dao.save(item1)
        dao.save(item2)
        dao.save(item3)
        val sum = dao.calculateUnsettledSum(contactId)

        // then
        sum shouldBe 15.0
    }

    @Test
    fun `update settled status - updates isSettled flag`() = runTest {
        // given
        val id = UUID.randomUUID()
        val item = LoanItemEntity(
            id = id,
            contactId = UUID.randomUUID(),
            amount = 10.0,
            title = "Coffee",
            isSettled = false,
            createdAt = Instant.now()
        )

        // when
        dao.save(item)
        dao.updateSettledStatus(id, true)
        val updated = dao.findById(id)

        // then
        updated?.isSettled shouldBe true
    }
}
