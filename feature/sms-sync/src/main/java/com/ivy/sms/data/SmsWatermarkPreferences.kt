package com.ivy.sms.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.base.threading.DispatchersProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsWatermarkPreferences @Inject constructor(
    private val dataStore: DataStore<Preferences>,
    private val dispatchers: DispatchersProvider,
) {

    /**
     * LEGACY global watermark — superseded by the per-sender watermark on
     * [com.ivy.sms.domain.model.SenderAccountLink], which is the actual read
     * bound (strictly-greater semantics: it stores the newest processed DATE
     * and scans read only DATE > watermark). Still advanced after each scan
     * and read once per scan as the fallback bound for links that predate
     * per-link watermarks. Never reset on sync-now — the old reset forced
     * full rescans of every sender and enabled duplicate transactions.
     */
    private val watermarkKey = longPreferencesKey("sms.watermark.epochMillis")

    /**
     * Global scan-period lower bound. Fallback for links without their own
     * [com.ivy.sms.domain.model.SenderAccountLink.historicalLowerBound]
     * (which the wallet sync-period sheet writes per sender).
     */
    private val lowerBoundKey = longPreferencesKey("sms.scan.period.lowerBoundEpochMillis")

    /**
     * Cumulative counter of pending-review items the user has resolved or
     * dismissed. Used by [com.ivy.sms.ui.pending.PendingReviewScreen]'s
     * progress hero so the user sees "you've reviewed X messages so far"
     * across app sessions instead of a number that resets on every
     * back-navigation. Strictly monotonic — only ever incremented.
     */
    private val reviewedTotalKey = intPreferencesKey("sms.review.totalResolved")

    /** Cumulative count of templates the user has mapped from Unmapped → Active. */
    private val templatesMappedTotalKey = intPreferencesKey("sms.review.totalTemplatesMapped")

    /**
     * Cumulative count of pending-review items EVER discovered by the parser
     * — incremented on every successful `pendingRepo.enqueue` and never
     * decremented. Drives the review-screen hero's denominator so the bar
     * reflects "X of Y total reviewed" with Y monotonically growing, even
     * when items disappear from the queue via blacklist/clear (which the
     * resolved-counter doesn't catch).
     */
    private val discoveredTotalKey = intPreferencesKey("sms.review.totalDiscovered")

    /**
     * Highest [com.ivy.sms.data.NORMALIZER_VERSION] whose one-shot
     * renormalization pass has completed over persisted templates/pending
     * bodies. Null on installs that have never run the pass. Written only
     * AFTER the pass finishes, so an interrupted pass re-runs (the normalizer
     * is idempotent, re-running is safe).
     */
    private val normalizerAppliedVersionKey = intPreferencesKey("sms.normalizer.appliedVersion")

    fun observeReviewedTotal(): Flow<Int> =
        dataStore.data.map { it[reviewedTotalKey] ?: 0 }

    fun observeTemplatesMappedTotal(): Flow<Int> =
        dataStore.data.map { it[templatesMappedTotalKey] ?: 0 }

    fun observeDiscoveredTotal(): Flow<Int> =
        dataStore.data.map { it[discoveredTotalKey] ?: 0 }

    suspend fun incrementDiscoveredTotal(by: Int = 1): Either<String, Unit> = withContext(dispatchers.io) {
        runCatching {
            dataStore.edit { prefs ->
                prefs[discoveredTotalKey] = (prefs[discoveredTotalKey] ?: 0) + by
            }
        }.fold(
            onSuccess = { Unit.right() },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }

    suspend fun incrementReviewedTotal(by: Int = 1): Either<String, Unit> = withContext(dispatchers.io) {
        runCatching {
            dataStore.edit { prefs ->
                prefs[reviewedTotalKey] = (prefs[reviewedTotalKey] ?: 0) + by
            }
        }.fold(
            onSuccess = { Unit.right() },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }

    suspend fun incrementTemplatesMappedTotal(): Either<String, Unit> = withContext(dispatchers.io) {
        runCatching {
            dataStore.edit { prefs ->
                prefs[templatesMappedTotalKey] = (prefs[templatesMappedTotalKey] ?: 0) + 1
            }
        }.fold(
            onSuccess = { Unit.right() },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }

    suspend fun normalizerAppliedVersion(): Either<String, Int?> = withContext(dispatchers.io) {
        runCatching {
            dataStore.data.first()[normalizerAppliedVersionKey]
        }.fold(
            onSuccess = { it.right() },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }

    suspend fun writeNormalizerAppliedVersion(version: Int): Either<String, Unit> = withContext(dispatchers.io) {
        runCatching {
            dataStore.edit { it[normalizerAppliedVersionKey] = version }
        }.fold(
            onSuccess = { Unit.right() },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }

    suspend fun read(): Either<String, Long?> = withContext(dispatchers.io) {
        runCatching {
            dataStore.data.first()[watermarkKey]
        }.fold(
            onSuccess = { it.right() },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }

    suspend fun write(epochMillis: Long): Either<String, Unit> = withContext(dispatchers.io) {
        runCatching {
            dataStore.edit { it[watermarkKey] = epochMillis }
        }.fold(
            onSuccess = { Unit.right() },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }

    /**
     * Per-sender auto-route switch (default true). When false, Tier-1 matches
     * from that sender are quarantined for review instead of silently becoming
     * transactions — see [com.ivy.sms.domain.usecase.RouteSmsUseCase]. Stored
     * here (not on the Room link entity) deliberately: no migration needed and
     * the preference survives an unlink/relink cycle.
     */
    private fun autoRouteKey(senderId: String) =
        booleanPreferencesKey("sms.autoRoute.$senderId")

    suspend fun autoRouteEnabled(senderId: String): Either<String, Boolean> = withContext(dispatchers.io) {
        runCatching {
            dataStore.data.first()[autoRouteKey(senderId)] ?: true
        }.fold(
            onSuccess = { it.right() },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }

    suspend fun writeAutoRoute(senderId: String, enabled: Boolean): Either<String, Unit> = withContext(dispatchers.io) {
        runCatching {
            dataStore.edit { it[autoRouteKey(senderId)] = enabled }
        }.fold(
            onSuccess = { Unit.right() },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }

    suspend fun scanLowerBound(): Either<String, Long?> = withContext(dispatchers.io) {
        runCatching {
            dataStore.data.first()[lowerBoundKey]
        }.fold(
            onSuccess = { it.right() },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }

    suspend fun writeLowerBound(epochMillis: Long): Either<String, Unit> = withContext(dispatchers.io) {
        runCatching {
            dataStore.edit { it[lowerBoundKey] = epochMillis }
        }.fold(
            onSuccess = { Unit.right() },
            onFailure = { "STORAGE_ERROR:${it.message}".left() },
        )
    }
}
