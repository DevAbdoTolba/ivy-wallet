package com.ivy.sms.domain.usecase

import arrow.core.Either
import com.ivy.data.model.AccountId
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.domain.model.SenderAccountLink
import java.time.Instant
import javax.inject.Inject

class LinkSenderToWalletUseCase @Inject constructor(
    private val senderRepo: SenderAccountLinkRepository,
) {
    suspend operator fun invoke(senderId: String, accountId: AccountId): Either<String, Unit> =
        senderRepo.upsert(
            SenderAccountLink(
                senderId = senderId,
                accountId = accountId,
                linkedAt = Instant.now(),
            )
        )
}
