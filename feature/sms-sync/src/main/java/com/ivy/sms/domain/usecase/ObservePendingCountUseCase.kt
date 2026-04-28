package com.ivy.sms.domain.usecase

import com.ivy.sms.data.PendingReviewItemRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ObservePendingCountUseCase @Inject constructor(
    private val repo: PendingReviewItemRepository,
) {
    operator fun invoke(): Flow<Int> = repo.observeCount()
}
