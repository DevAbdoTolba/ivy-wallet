package com.ivy.sms.startup

import com.ivy.base.threading.DispatchersProvider
import com.ivy.sms.domain.model.SyncResult
import com.ivy.sms.domain.model.SyncTrigger
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
    dispatchers: DispatchersProvider,
) : SmsSyncAppStartup {

    private val applicationScope = CoroutineScope(SupervisorJob() + dispatchers.io)
    private val _syncResult = MutableStateFlow<SyncResult?>(null)

    override val syncResult: StateFlow<SyncResult?> = _syncResult.asStateFlow()

    override fun scheduleLaunchScan() {
        applicationScope.launch {
            try {
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
