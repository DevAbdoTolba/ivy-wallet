package com.ivy.sms.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.model.AccountId
import com.ivy.sms.data.BuildFingerprintProvider
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.data.SmsInboxDataSource
import com.ivy.sms.data.SmsMessageMapper
import com.ivy.sms.data.SmsWatermarkPreferences
import com.ivy.sms.domain.model.ScanProgress
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

data class ScanSummary(
    val newMessagesProcessed: Int,
    val transactionsCreated: Int,
    val itemsQuarantined: Int,
    val newTemplatesDiscovered: Int,
    val durationMillis: Long,
)

@Singleton
class ScanInboxUseCase @Inject constructor(
    private val inbox: SmsInboxDataSource,
    private val watermarks: SmsWatermarkPreferences,
    private val senderRepo: SenderAccountLinkRepository,
    private val discover: DiscoverTemplatesUseCase,
    private val route: RouteSmsUseCase,
    private val smsMessageMapper: SmsMessageMapper,
    private val buildFingerprint: BuildFingerprintProvider,
    private val dispatchers: DispatchersProvider,
) {

    // StateFlow so multiple consumers (every screen that wants to render progress)
    // see every update. The previous Channel.receiveAsFlow was single-consumer:
    // when both the templates list and the wallet config screen subscribed, they
    // fought over emissions and the wallet bar barely budged.
    private val _progress = MutableStateFlow<ScanProgress?>(null)
    val progress: StateFlow<ScanProgress?> = _progress.asStateFlow()

    suspend operator fun invoke(): Either<String, ScanSummary> = withContext(dispatchers.io) {
        val started = System.currentTimeMillis()

        // Seed parser from persisted templates so subsequent matches are stable across launches.
        discover.seed()

        // Per-wallet redesign (2026-04-28): only scan messages from senders that have
        // been explicitly linked to a wallet. Messages from unlinked senders are not
        // user-relevant for SMS sync — clustering them just produces noise and shows
        // up as "templates" in the per-wallet template view.
        val links = senderRepo.findAll().getOrNull().orEmpty()
        val senderToAccount: Map<String, AccountId> =
            links.associate { it.senderId to it.accountId }
        if (links.isEmpty()) {
            return@withContext ScanSummary(0, 0, 0, 0, System.currentTimeMillis() - started).right()
        }

        // Per-sender incremental read: each link is bounded by its OWN
        // watermark, so configuring one wallet never re-reads another's
        // history and a newly linked sender's backlog is scanned from the
        // period lower bound instead of being skipped by a global bound.
        // Boundary semantics are strictly-greater: a link's watermark stores
        // the newest DATE already processed for that sender and the inbox
        // query returns only DATE > watermark (see SmsInboxDataSource.read),
        // so the boundary row is not re-routed on every scan.
        val globalLowerBound = watermarks.scanLowerBound().getOrNull() ?: 0L
        // LEGACY global watermark, read once per scan. Only consulted for
        // links without their own watermark: a link that already existed when
        // the global watermark last advanced (linkedAt <= watermark) was
        // covered by those global scans and inherits the bound; a genuinely
        // fresh link (linkedAt after the watermark) reads from the period
        // lower bound so its history is backfilled. SMS DATEs never exceed
        // wall-clock time, so linkedAt <= globalWatermark implies the link
        // predates the scan that wrote it.
        val globalWatermark = watermarks.read().getOrNull() ?: 0L
        val rows = links.flatMap { link ->
            inbox.read(
                lowerBoundEpochMillis = link.historicalLowerBound?.toEpochMilli()
                    ?: globalLowerBound,
                watermarkEpochMillis = link.watermark?.toEpochMilli()
                    ?: globalWatermark.takeIf { link.linkedAt.toEpochMilli() <= it }
                    ?: 0L,
                senderFilter = link.senderId,
            ).getOrNull().orEmpty()
        }.sortedBy { it.dateEpochMillis }
        // Build fingerprint + active per-link watermarks: makes every capture
        // attributable to a build and shows exactly which bound each sender
        // was read with.
        timber.log.Timber.tag("SmsTrace").i(
            "SCAN   build=%s linkWatermarks=%s",
            buildFingerprint.fingerprint,
            links.joinToString(prefix = "[", postfix = "]") {
                "${it.senderId}=${it.watermark?.toEpochMilli() ?: "none"}"
            },
        )
        timber.log.Timber.tag("SmsTrace").i(
            "SCAN → links=%d lowerBound=%d watermark=%d rows=%d",
            links.size, globalLowerBound, globalWatermark, rows.size,
        )

        var transactionsCreated = 0
        var itemsQuarantined = 0
        var newTemplates = 0

        // Seed an initial 0 / total snapshot before the loop so subscribers can
        // size their progress bars immediately instead of waiting for the first row.
        _progress.value = ScanProgress(
            processed = 0,
            total = rows.size,
            newTemplatesDiscovered = 0,
            transactionsCreated = 0,
            itemsQuarantined = 0,
        )

        for ((idx, row) in rows.withIndex()) {
            val message = with(smsMessageMapper) { row.toDomain() }
            val template = when (val r = discover(message)) {
                is Either.Left -> {
                    _progress.value = ScanProgress(
                        processed = idx + 1,
                        total = rows.size,
                        newTemplatesDiscovered = newTemplates,
                        transactionsCreated = transactionsCreated,
                        itemsQuarantined = itemsQuarantined,
                    )
                    continue
                }
                is Either.Right -> {
                    // Count only true creations — every template merely
                    // refreshed by this scan used to be reported as
                    // "discovered", inflating the number on each sync.
                    if (r.value.created) {
                        newTemplates++
                    }
                    r.value.template
                }
            }

            when (val r = route(message, template, senderToAccount)) {
                is Either.Left -> {
                    timber.log.Timber.tag("SmsTrace").w(
                        "SCAN  route returned Left detail='%s' tpl=%s",
                        r.value, template.id.value,
                    )
                }
                is Either.Right -> when (r.value) {
                    is RouteOutcome.Created -> transactionsCreated++
                    is RouteOutcome.Quarantined -> itemsQuarantined++
                    is RouteOutcome.Blacklisted -> { /* drop */ }
                }
            }

            _progress.value = ScanProgress(
                processed = idx + 1,
                total = rows.size,
                newTemplatesDiscovered = newTemplates,
                transactionsCreated = transactionsCreated,
                itemsQuarantined = itemsQuarantined,
            )
        }

        // Advance the LEGACY global watermark — it is only the fallback bound
        // for links that predate per-link watermarks (see read above).
        val newGlobalWatermark = rows.maxOfOrNull { it.dateEpochMillis } ?: globalWatermark
        if (newGlobalWatermark > globalWatermark) {
            watermarks.write(newGlobalWatermark)
        }
        // Per-sender watermarks are the real read bound. Stamp each link with
        // the newest DATE processed from ITS sender — and ONLY senders that
        // actually produced rows this scan. The old `?: newWatermark`
        // fallback stamped zero-row senders with the global value, marking a
        // newly linked sender's never-scanned history as synced and leaving
        // the gap unreachable by any later scan.
        val rowsBySender = rows.groupBy { it.address }
        for (link in links) {
            val perSenderMax = rowsBySender[link.senderId]
                ?.maxOfOrNull { it.dateEpochMillis } ?: continue
            val instant = java.time.Instant.ofEpochMilli(perSenderMax)
            if (link.watermark == null || link.watermark.isBefore(instant)) {
                senderRepo.upsert(link.copy(watermark = instant))
            }
        }
        // Clear progress so the next subscriber doesn't see a stale "100% done"
        // snapshot when they haven't started a sync yet.
        _progress.value = null

        timber.log.Timber.tag("SmsTrace").i(
            "SCAN ← total=%d created=%d quarantined=%d newTpls=%d duration=%dms",
            rows.size,
            transactionsCreated,
            itemsQuarantined,
            newTemplates,
            System.currentTimeMillis() - started,
        )
        ScanSummary(
            newMessagesProcessed = rows.size,
            transactionsCreated = transactionsCreated,
            itemsQuarantined = itemsQuarantined,
            newTemplatesDiscovered = newTemplates,
            durationMillis = System.currentTimeMillis() - started,
        ).right()
    }
}
