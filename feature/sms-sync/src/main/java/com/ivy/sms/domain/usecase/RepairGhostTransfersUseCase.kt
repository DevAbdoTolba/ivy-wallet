package com.ivy.sms.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.base.model.TransactionType
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.db.dao.read.TransactionDao
import com.ivy.data.db.dao.write.WriteTransactionDao
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.data.SmsInboxDataSource
import com.ivy.sms.data.SmsMessageMapper
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.data.SmsWatermarkPreferences
import com.ivy.sms.domain.model.SmsTemplateId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private const val TRACE = "SmsTrace"

/** Bump to re-run the ghost repair on installs that already applied an
 *  earlier version. Version 1 = the 2026-07 self-transfer rescue. */
const val GHOST_TRANSFER_REPAIR_VERSION = 1

/**
 * One-shot repair of "ghost" transfers: rows the pre-136 engine persisted with
 * `toAccountId == accountId` (the old Transfer branch defaulted the destination
 * to the source wallet). The read mapper rejects self-transfers and every
 * repository read drops them silently — so these rows exist in the table but
 * appear in no list, affect no balance, and the user never learned the money
 * moved.
 *
 * Strategy (research R5): work at the ENTITY level (the domain read path can't
 * even see these rows), delete each ghost, re-read its original SMS from the
 * device inbox (located by sender + timestamp, verified by dedupKey — deleting
 * the row freed the key), and push the message back through the standard
 * routing pipeline. With the 136+ routing rules a destination-less transfer
 * quarantines as TRANSFER_NEEDS_DESTINATION, so the lost money surfaces as a
 * review item the user resolves with the destination picker.
 *
 * Invariants preserved: NO watermark is read or written; valid transfers and
 * non-SMS rows are untouched; the pass is versioned (SharedPrefs-style
 * DataStore key) and runs inside the SyncSms mutex after renormalization, so
 * it can never interleave with a scan. An SMS deleted from the device inbox
 * since then cannot resurface — the ghost row is removed and the loss is
 * logged (the row was already invisible and unrecoverable before this pass).
 */
@Singleton
class RepairGhostTransfersUseCase @Inject constructor(
    private val transactionDao: TransactionDao,
    private val writeTransactionDao: WriteTransactionDao,
    private val inbox: SmsInboxDataSource,
    private val smsMessageMapper: SmsMessageMapper,
    private val templateRepo: SmsTemplateRepository,
    private val senderRepo: SenderAccountLinkRepository,
    private val route: RouteSmsUseCase,
    private val prefs: SmsWatermarkPreferences,
    private val dispatchers: DispatchersProvider,
) {
    private val mutex = Mutex()

    @Suppress("ReturnCount", "LongMethod")
    suspend operator fun invoke(): Either<String, Unit> = mutex.withLock {
        withContext(dispatchers.io) {
            val applied = when (val r = prefs.ghostRepairAppliedVersion()) {
                is Either.Left -> return@withContext r.value.left()
                is Either.Right -> r.value
            }
            if (applied != null && applied >= GHOST_TRANSFER_REPAIR_VERSION) {
                return@withContext Unit.right()
            }

            val allRows = runCatching { transactionDao.findAll() }
                .getOrElse { return@withContext "STORAGE_ERROR:${it.message}".left() }
            val ghosts = allRows.filter { row ->
                row.type == TransactionType.TRANSFER &&
                    row.toAccountId != null &&
                    row.toAccountId == row.accountId &&
                    row.smsSourceDedupKey != null
            }
            if (ghosts.isEmpty()) {
                prefs.writeGhostRepairAppliedVersion(GHOST_TRANSFER_REPAIR_VERSION)
                Timber.tag(TRACE).i("ghost-repair: found=0 — nothing to do (v%d stamped)", GHOST_TRANSFER_REPAIR_VERSION)
                return@withContext Unit.right()
            }

            val links = senderRepo.findAll().getOrNull().orEmpty()
            val senderToAccount = links.associate { it.senderId to it.accountId }

            var resurfaced = 0
            var lost = 0
            for (ghost in ghosts) {
                // Delete FIRST: this frees the dedupKey so the standard
                // routing pipeline (dedup guard included) can re-process the
                // message instead of short-circuiting to the ghost row.
                runCatching { writeTransactionDao.deleteById(ghost.id) }
                    .onFailure {
                        Timber.tag(TRACE).e(it, "ghost-repair: delete failed id=%s", ghost.id)
                        return@withContext "STORAGE_ERROR:${it.message}".left()
                    }

                val sender = ghost.smsSourceSenderId
                val ts = ghost.smsSourceTimestamp?.toEpochMilli()
                if (sender == null || ts == null) {
                    lost++
                    Timber.tag(TRACE).w("ghost-repair: row %s has no sender/timestamp — dropped", ghost.id)
                    continue
                }

                // Locate the exact original SMS: sender-scoped inbox read from
                // just before the recorded timestamp, verified by dedupKey.
                // Watermark bound passed as 0 — this is a historical read and
                // MUST NOT consult or advance any watermark.
                val rows = inbox.read(
                    lowerBoundEpochMillis = ts - 1,
                    watermarkEpochMillis = 0L,
                    senderFilter = sender,
                ).getOrNull().orEmpty()
                val message = rows
                    .asSequence()
                    .map { with(smsMessageMapper) { it.toDomain() } }
                    .firstOrNull { it.dedupKey == ghost.smsSourceDedupKey }
                if (message == null) {
                    lost++
                    Timber.tag(TRACE).w(
                        "ghost-repair: SMS gone from inbox (sender=%s ts=%d) — row dropped",
                        sender, ts,
                    )
                    continue
                }

                val template = ghost.smsTemplateId
                    ?.let { templateRepo.findById(SmsTemplateId(it)).getOrNull() }
                if (template == null) {
                    lost++
                    Timber.tag(TRACE).w("ghost-repair: template %s missing — row dropped", ghost.smsTemplateId)
                    continue
                }

                // userInitiated: deterministic outcome — the auto-route gate
                // must not divert the rescue into AUTO_ROUTE_DISABLED. With no
                // destination on the template this quarantines as
                // TRANSFER_NEEDS_DESTINATION; with one (user set it since) it
                // creates the correct transfer directly.
                when (route(message, template, senderToAccount, userInitiated = true).getOrNull()) {
                    is RouteOutcome.Created, is RouteOutcome.Quarantined -> resurfaced++
                    else -> lost++
                }
            }

            prefs.writeGhostRepairAppliedVersion(GHOST_TRANSFER_REPAIR_VERSION)
            Timber.tag(TRACE).i(
                "ghost-repair: found=%d resurfaced=%d lost=%d senders=%s (v%d stamped)",
                ghosts.size,
                resurfaced,
                lost,
                ghosts.mapNotNull { it.smsSourceSenderId }.distinct(),
                GHOST_TRANSFER_REPAIR_VERSION,
            )
            Unit.right()
        }
    }
}
