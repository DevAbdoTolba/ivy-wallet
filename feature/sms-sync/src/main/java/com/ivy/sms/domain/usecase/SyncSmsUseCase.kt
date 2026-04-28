package com.ivy.sms.domain.usecase

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.ivy.sms.domain.model.SyncResult
import com.ivy.sms.domain.model.SyncTrigger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

interface SyncSmsUseCase {
    suspend operator fun invoke(trigger: SyncTrigger): Either<String, SyncResult>
}

@Singleton
class SyncSmsUseCaseImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val scan: ScanInboxUseCase,
) : SyncSmsUseCase {

    private val mutex = Mutex()

    override suspend fun invoke(trigger: SyncTrigger): Either<String, SyncResult> {
        if (!hasReadSmsPermission()) {
            return SyncResult.PermissionMissing.right()
        }
        return mutex.withLock {
            try {
                when (val r = scan()) {
                    is Either.Left -> r.value.left()
                    is Either.Right -> {
                        val s = r.value
                        SyncResult.Completed(
                            newMessagesProcessed = s.newMessagesProcessed,
                            transactionsCreated = s.transactionsCreated,
                            itemsQuarantined = s.itemsQuarantined,
                            durationMillis = s.durationMillis,
                        ).right()
                    }
                }
            } catch (e: Throwable) {
                Timber.e(e, "Sync failed (trigger=$trigger)")
                "STORAGE_ERROR:${e.message ?: e::class.simpleName}".left()
            }
        }
    }

    private fun hasReadSmsPermission(): Boolean = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.READ_SMS,
    ) == PackageManager.PERMISSION_GRANTED
}
