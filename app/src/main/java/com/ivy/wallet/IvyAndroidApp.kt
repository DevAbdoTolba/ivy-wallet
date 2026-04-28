package com.ivy.wallet

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.ivy.base.legacy.appContext
import com.ivy.sms.startup.SmsSyncAppStartup
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.HiltAndroidApp
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import timber.log.Timber.DebugTree
import javax.inject.Inject

/**
 * Created by iliyan on 24.02.18.
 */
@HiltAndroidApp
class IvyAndroidApp : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        appContext = this

        if (BuildConfig.DEBUG) {
            Timber.plant(DebugTree())
        }

        EntryPointAccessors.fromApplication(this, SmsSyncStartupEntryPoint::class.java)
            .smsSyncAppStartup()
            .scheduleLaunchScan()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SmsSyncStartupEntryPoint {
        fun smsSyncAppStartup(): SmsSyncAppStartup
    }
}
