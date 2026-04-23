package com.ivy.data.db.dao.fake

import com.ivy.data.db.dao.read.LoanItemDao
import com.ivy.data.db.dao.write.WriteLoanItemDao
import com.ivy.data.db.entity.LoanItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.UUID

class FakeLoanItemDao : LoanItemDao, WriteLoanItemDao {
    private val items = MutableStateFlow<List<LoanItemEntity>>(emptyList())

    override fun findAllByContactId(contactId: UUID): Flow<List<LoanItemEntity>> {
        return items.map { list -> 
            list.filter { it.contactId == contactId }.sortedByDescending { it.createdAt }
        }
    }

    override suspend fun findById(id: UUID): LoanItemEntity? {
        return items.value.find { it.id == id }
    }

    override suspend fun calculateUnsettledSum(contactId: UUID): Double? {
        return items.value
            .filter { it.contactId == contactId && !it.isSettled }
            .sumOf { it.amount }
            .takeIf { it != 0.0 }
    }

    override suspend fun save(value: LoanItemEntity) {
        val current = items.value.toMutableList()
        val index = current.indexOfFirst { it.id == value.id }
        if (index != -1) {
            current[index] = value
        } else {
            current.add(value)
        }
        items.value = current
    }

    override suspend fun updateSettledStatus(id: UUID, isSettled: Boolean) {
        val current = items.value.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index != -1) {
            current[index] = current[index].copy(isSettled = isSettled)
            items.value = current
        }
    }

    override suspend fun deleteById(id: UUID) {
        items.value = items.value.filter { it.id != id }
    }

    override suspend fun deleteByContactId(contactId: UUID) {
        items.value = items.value.filter { it.contactId != contactId }
    }
}
