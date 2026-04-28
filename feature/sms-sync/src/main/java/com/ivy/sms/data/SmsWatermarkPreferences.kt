package com.ivy.sms.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.base.threading.DispatchersProvider
import kotlinx.coroutines.flow.first
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
