package com.ivy.sms.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.data.model.AccountId
import com.ivy.sms.data.SenderAccountLinkRepository
import java.time.Instant
import javax.inject.Inject

/**
 * Applies a user-picked sync period to ONE wallet's linked sender(s).
 *
 * Sync-now is incremental by default: re-picking the same (or a shorter)
 * period leaves the link's bounds untouched and the next scan reads only rows
 * newer than the link's watermark. Only an explicitly LONGER period — or the
 * first explicit pick for a link — moves the link's `historicalLowerBound`
 * back AND clears its watermark so the next scan re-reads the gap from the
 * new lower bound. The already-imported overlap above the old bound is
 * absorbed by the transaction-level dedup guard in
 * [CreateTransactionFromSmsUseCase] and the pending queue's unique dedupKey
 * index, so the re-scan costs time, not correctness.
 *
 * Deliberately never touches the legacy GLOBAL watermark/lower bound: the old
 * `watermarks.write(0L)` reset on every sync-now forced a full re-read of
 * EVERY linked sender's period — the enabler of the duplicate-transactions
 * defect (logs/2026-05-14 captures).
 */
class ApplySyncPeriodUseCase @Inject constructor(
    private val senderRepo: SenderAccountLinkRepository,
    private val findMatching: FindMatchingMessagesUseCase,
) {
    suspend operator fun invoke(
        walletId: AccountId,
        lowerBoundEpochMillis: Long,
    ): Either<String, Unit> {
        val links = senderRepo.findByAccountId(walletId).getOrNull().orEmpty()
        val newLower = Instant.ofEpochMilli(lowerBoundEpochMillis)
        var boundsChanged = false
        for (link in links) {
            val current = link.historicalLowerBound
            val movesBack = current == null || newLower.isBefore(current)
            if (!movesBack) continue
            senderRepo.upsert(link.copy(historicalLowerBound = newLower, watermark = null))
                .onLeft { return it.left() }
            boundsChanged = true
        }
        if (boundsChanged) {
            // FindMatching's row counts honour the per-link bound — results
            // cached against the OLD bound are stale the moment it moves.
            findMatching.invalidate()
        }
        return Unit.right()
    }
}
