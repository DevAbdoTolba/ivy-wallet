package com.ivy.sms.domain.usecase

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.model.AccountId
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

        // Pull rows scoped to each linked sender. The per-sender watermark on each
        // SenderAccountLink would let us bound this even tighter, but for the first
        // implementation we read from the global lower bound and filter by address.
        val lowerBound = watermarks.scanLowerBound().getOrNull() ?: 0L
        val watermark = watermarks.read().getOrNull() ?: 0L
        val rows = links.flatMap { link ->
            inbox.read(lowerBound, watermark, senderFilter = link.senderId)
                .getOrNull().orEmpty()
        }.sortedBy { it.dateEpochMillis }
        timber.log.Timber.tag("SmsTrace").i(
            "SCAN → links=%d lowerBound=%d watermark=%d rows=%d",
            links.size, lowerBound, watermark, rows.size,
        )

        var transactionsCreated = 0
        var itemsQuarantined = 0
        var newTemplates = 0
        val previousTemplateIds = HashSet<String>()

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
                is Either.Right -> r.value
            }
            if (previousTemplateIds.add(template.id.value.toString())) {
                newTemplates++
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

        val newWatermark = rows.maxOfOrNull { it.dateEpochMillis } ?: watermark
        if (newWatermark > watermark) {
            watermarks.write(newWatermark)
        }
        // Update per-sender link watermarks so the wallet config screen's
        // "Last sync" status (and the never-synced auto-pop check) flips
        // out of the "Never synced" state. Each link gets the newest message
        // timestamp from ITS sender — falls back to global newWatermark when
        // the sender produced no rows this scan, so the link still records
        // that a sync happened against it.
        if (links.isNotEmpty()) {
            val rowsBySender = rows.groupBy { it.address }
            for (link in links) {
                val perSenderMax = rowsBySender[link.senderId]
                    ?.maxOfOrNull { it.dateEpochMillis } ?: newWatermark
                if (perSenderMax <= 0L) continue
                val instant = java.time.Instant.ofEpochMilli(perSenderMax)
                if (link.watermark == null || link.watermark.isBefore(instant)) {
                    senderRepo.upsert(link.copy(watermark = instant))
                }
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
