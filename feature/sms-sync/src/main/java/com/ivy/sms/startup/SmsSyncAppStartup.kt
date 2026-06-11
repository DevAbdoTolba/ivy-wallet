package com.ivy.sms.startup

import com.ivy.base.threading.DispatchersProvider
import com.ivy.sms.domain.model.SyncResult
import com.ivy.sms.domain.model.SyncTrigger
import com.ivy.sms.domain.usecase.RenormalizePersistedSmsDataUseCase
import com.ivy.sms.domain.usecase.SyncSmsUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

interface SmsSyncAppStartup {
    val syncResult: StateFlow<SyncResult?>
    fun scheduleLaunchScan()
    /** Fire-and-forget incremental sync triggered by the user (quick-access button).
     *  Same incremental semantics as the launch scan — only reads SMS newer than the
     *  last successful watermark. */
    fun triggerManualSync()
}

@Singleton
class SmsSyncAppStartupImpl @Inject constructor(
    private val syncSms: SyncSmsUseCase,
    private val renormalize: RenormalizePersistedSmsDataUseCase,
    dispatchers: DispatchersProvider,
) : SmsSyncAppStartup {

    private val applicationScope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val _syncResult = MutableStateFlow<SyncResult?>(null)

    override val syncResult: StateFlow<SyncResult?> = _syncResult.asStateFlow()

    override fun scheduleLaunchScan() {
        applicationScope.launch {
            try {
                // Versioned one-shot renormalization of persisted templates /
                // pending bodies, run BEFORE the launch scan. SyncSmsUseCase
                // re-invokes it inside its scan mutex (the actual ordering
                // guarantee for every scan path); this eager call additionally
                // covers launches where the sync early-returns (permission
                // missing / no linked senders) so persisted data still gets
                // normalized. Safe here: scheduleLaunchScan runs at process
                // start, before any UI can trigger a scan, and the use case is
                // internally serialized + version-stamped (repeat = no-op).
                renormalize().onLeft { Timber.w("Renormalization pass error: $it") }
                syncSms(SyncTrigger.APP_LAUNCH).fold(
                    { Timber.w("App-launch sync error: $it") },
                    { _syncResult.value = it },
                )
            } catch (t: Throwable) {
                Timber.e(t, "App-launch sync threw")
            }
        }
    }

    override fun triggerManualSync() {
        applicationScope.launch {
            try {
                syncSms(SyncTrigger.MANUAL_MENU).fold(
                    { Timber.w("Manual sync error: $it") },
                    { _syncResult.value = it },
                )
            } catch (t: Throwable) {
                Timber.e(t, "Manual sync threw")
            }
        }
    }
}
