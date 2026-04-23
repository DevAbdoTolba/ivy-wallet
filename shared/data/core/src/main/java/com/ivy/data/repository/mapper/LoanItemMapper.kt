package com.ivy.data.repository.mapper

import arrow.core.Either
import arrow.core.raise.either
import com.ivy.data.db.entity.LoanItemEntity
import com.ivy.data.model.LoanId
import com.ivy.data.model.LoanItem
import com.ivy.data.model.LoanItemId
import javax.inject.Inject

class LoanItemMapper @Inject constructor() {
    fun LoanItemEntity.toDomain(): Either<String, LoanItem> = either {
        LoanItem(
            id = LoanItemId(id),
            contactId = LoanId(contactId),
            amount = amount,
            title = title,
            isSettled = isSettled,
            createdAt = createdAt
        )
    }

    fun LoanItem.toEntity(): LoanItemEntity {
        return LoanItemEntity(
            contactId = contactId.value,
            amount = amount,
            title = title,
            isSettled = isSettled,
            createdAt = createdAt,
            id = id.value
        )
    }
}
