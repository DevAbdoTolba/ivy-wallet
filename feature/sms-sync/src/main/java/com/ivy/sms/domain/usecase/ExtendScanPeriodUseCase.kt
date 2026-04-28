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
import com.ivy.sms.domain.model.ScanPeriod
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

data class ExtendScanResult(
    val newTemplatesDiscovered: Int,
)

class ExtendScanPeriodUseCase @Inject constructor(
    private val watermarks: SmsWatermarkPreferences,
    private val inbox: SmsInboxDataSource,
    private val senderRepo: SenderAccountLinkRepository,
    private val discover: DiscoverTemplatesUseCase,
    private val route: RouteSmsUseCase,
    private val smsMessageMapper: SmsMessageMapper,
    private val dispatchers: DispatchersProvider,
) {
    suspend operator fun invoke(period: ScanPeriod): Either<String, ExtendScanResult> = withContext(dispatchers.io) {
        val newLowerBound = lowerBoundFor(period)
        val currentLower = watermarks.scanLowerBound().getOrNull() ?: Long.MAX_VALUE
        if (newLowerBound >= currentLower) {
            return@withContext "VALIDATION:new period must extend further back".left()
        }

        // Read only the gap between the new lower bound and the current lower bound,
        // capped above by what we've already processed.
        val rows = when (val r = inbox.read(newLowerBound, 0L)) {
            is Either.Left -> return@withContext r.value.left()
            is Either.Right -> r.value.filter { it.dateEpochMillis < currentLower }
        }

        val links = senderRepo.findAll().getOrNull().orEmpty()
        val senderToAccount: Map<String, AccountId> = links.associate { it.senderId to it.accountId }

        val priorTemplateIds = HashSet<String>()
        var newTemplates = 0
        for (row in rows) {
            val message = with(smsMessageMapper) { row.toDomain() }
            val template = discover(message).getOrNull() ?: continue
            if (priorTemplateIds.add(template.id.value.toString())) {
                newTemplates++
            }
            route(message, template, senderToAccount)
        }

        watermarks.writeLowerBound(newLowerBound)
        ExtendScanResult(newTemplatesDiscovered = newTemplates).right()
    }

    private fun lowerBoundFor(period: ScanPeriod): Long {
        val now = Instant.now()
        return when (period) {
            ScanPeriod.AllTime -> 0L
            ScanPeriod.LastWeek -> now.minus(7, ChronoUnit.DAYS).toEpochMilli()
            ScanPeriod.LastMonth -> now.minus(30, ChronoUnit.DAYS).toEpochMilli()
            ScanPeriod.LastQuarter -> now.minus(90, ChronoUnit.DAYS).toEpochMilli()
            ScanPeriod.LastYear -> now.minus(365, ChronoUnit.DAYS).toEpochMilli()
        }
    }
}
