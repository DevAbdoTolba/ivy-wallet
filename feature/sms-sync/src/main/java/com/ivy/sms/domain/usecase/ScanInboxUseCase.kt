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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ScanSummary(
    val newMessagesProcessed: Int,
    val transactionsCreated: Int,
    val itemsQuarantined: Int,
    val newTemplatesDiscovered: Int,
    val durationMillis: Long,
)

class ScanInboxUseCase @Inject constructor(
    private val inbox: SmsInboxDataSource,
    private val watermarks: SmsWatermarkPreferences,
    private val senderRepo: SenderAccountLinkRepository,
    private val discover: DiscoverTemplatesUseCase,
    private val route: RouteSmsUseCase,
    private val smsMessageMapper: SmsMessageMapper,
    private val dispatchers: DispatchersProvider,
) {

    private val _progress = Channel<ScanProgress>(capacity = Channel.CONFLATED)

    val progress: Flow<ScanProgress> = _progress.receiveAsFlow().flowOn(dispatchers.io)

    suspend operator fun invoke(): Either<String, ScanSummary> = withContext(dispatchers.io) {
        val started = System.currentTimeMillis()

        // Seed parser from persisted templates so subsequent matches are stable across launches.
        discover.seed()

        val watermark = watermarks.read().getOrNull() ?: 0L
        val lowerBound = watermarks.scanLowerBound().getOrNull() ?: 0L

        val rows = when (val r = inbox.read(lowerBound, watermark)) {
            is Either.Left -> return@withContext r.value.left()
            is Either.Right -> r.value
        }

        val links = senderRepo.findAll().getOrNull().orEmpty()
        val senderToAccount: Map<String, AccountId> =
            links.associate { it.senderId to it.accountId }

        var transactionsCreated = 0
        var itemsQuarantined = 0
        var newTemplates = 0
        val previousTemplateIds = HashSet<String>()

        for ((idx, row) in rows.withIndex()) {
            val message = with(smsMessageMapper) { row.toDomain() }
            val template = when (val r = discover(message)) {
                is Either.Left -> {
                    _progress.trySend(
                        ScanProgress(
                            processed = idx + 1,
                            total = rows.size,
                            newTemplatesDiscovered = newTemplates,
                            transactionsCreated = transactionsCreated,
                            itemsQuarantined = itemsQuarantined,
                        )
                    )
                    continue
                }
                is Either.Right -> r.value
            }
            if (previousTemplateIds.add(template.id.value.toString())) {
                newTemplates++
            }

            when (val r = route(message, template, senderToAccount)) {
                is Either.Left -> { /* swallow individual-row failures, continue */ }
                is Either.Right -> when (r.value) {
                    is RouteOutcome.Created -> transactionsCreated++
                    is RouteOutcome.Quarantined -> itemsQuarantined++
                    is RouteOutcome.Blacklisted -> { /* drop */ }
                }
            }

            _progress.trySend(
                ScanProgress(
                    processed = idx + 1,
                    total = rows.size,
                    newTemplatesDiscovered = newTemplates,
                    transactionsCreated = transactionsCreated,
                    itemsQuarantined = itemsQuarantined,
                )
            )
        }

        val newWatermark = rows.maxOfOrNull { it.dateEpochMillis } ?: watermark
        if (newWatermark > watermark) {
            watermarks.write(newWatermark)
        }

        ScanSummary(
            newMessagesProcessed = rows.size,
            transactionsCreated = transactionsCreated,
            itemsQuarantined = itemsQuarantined,
            newTemplatesDiscovered = newTemplates,
            durationMillis = System.currentTimeMillis() - started,
        ).right()
    }
}
