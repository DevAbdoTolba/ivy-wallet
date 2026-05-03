package com.ivy.sms.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
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

    private val watermarkKey = longPreferencesKey("sms.watermark.epochMillis")
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

    fun observeReviewedTotal(): Flow<Int> =
        dataStore.data.map { it[reviewedTotalKey] ?: 0 }

    fun observeTemplatesMappedTotal(): Flow<Int> =
        dataStore.data.map { it[templatesMappedTotalKey] ?: 0 }

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
