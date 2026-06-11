package com.ivy.data.repository

import com.ivy.base.TestDispatchersProvider
import com.ivy.data.db.dao.fake.FakeLoanItemDao
import com.ivy.data.db.entity.LoanItemEntity
import com.ivy.data.model.LoanId
import com.ivy.data.repository.mapper.LoanItemMapper
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.util.UUID

class LoanRepositoryTest {
    private lateinit var dao: FakeLoanItemDao
    private lateinit var repository: LoanRepository

    @Before
    fun setup() {
        dao = FakeLoanItemDao()
        repository = LoanRepository(
            mapper = LoanItemMapper(),
            loanItemDao = dao,
            writeLoanItemDao = dao,
            dispatchersProvider = TestDispatchersProvider,
        )
    }

    @Test
    fun `getLoanItems - maps entities to domain`() = runTest {
        // given
        val loanUuid = UUID.randomUUID()
        val entity = LoanItemEntity(
            contactId = loanUuid,
            amount = 42.0,
            title = "Coffee",
            isSettled = true,
            createdAt = Instant.now()
        )
        dao.save(entity)

        // when
        val items = repository.getLoanItems(LoanId(loanUuid)).first()

        // then
        items.size shouldBe 1
        val item = items.first()
        item.id.value shouldBe entity.id
        item.contactId.value shouldBe loanUuid
        item.amount shouldBe 42.0
        item.title shouldBe "Coffee"
        item.isSettled shouldBe true
    }

    @Test
    fun `getLoanItems - same-timestamp items keep a stable id order`() = runTest {
        // given two items created within the same millisecond
        val contactId = UUID.randomUUID()
        val createdAt = Instant.now()
        val idA = UUID.fromString("00000000-0000-0000-0000-00000000000a")
        val idB = UUID.fromString("00000000-0000-0000-0000-00000000000b")
        dao.save(
            LoanItemEntity(
                contactId = contactId,
                amount = 1.0,
                title = "B",
                createdAt = createdAt,
                id = idB
            )
        )
        dao.save(
            LoanItemEntity(
                contactId = contactId,
                amount = 2.0,
                title = "A",
                createdAt = createdAt,
                id = idA
            )
        )

        // when
        val items = repository.getLoanItems(LoanId(contactId)).first()

        // then: ties on createdAt are broken by id, not insertion order
        items.map { it.id.value } shouldBe listOf(idA, idB)
    }
}
