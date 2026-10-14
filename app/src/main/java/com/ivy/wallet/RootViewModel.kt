package com.ivy.wallet

import android.content.Intent
import android.os.SystemClock
import androidx.biometric.BiometricPrompt
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ivy.base.legacy.SharedPrefs
import com.ivy.base.legacy.Theme
import com.ivy.base.legacy.stringRes
import com.ivy.base.model.TransactionType
import com.ivy.data.db.dao.read.SettingsDao
import com.ivy.data.repository.LegalRepository
import com.ivy.frp.test.TestIdlingResource
import com.ivy.legacy.IvyWalletCtx
import com.ivy.legacy.utils.ioThread
import com.ivy.legacy.utils.readOnly
import com.ivy.navigation.DisclaimerScreen
import com.ivy.navigation.EditTransactionScreen
import com.ivy.navigation.MainScreen
import com.ivy.navigation.Navigation
import com.ivy.navigation.OnboardingScreen
import com.ivy.ui.R
import com.ivy.wallet.domain.deprecated.logic.notification.TransactionReminderLogic
import com.ivy.wallet.migrations.MigrationsManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
@Suppress("LongParameterList", "TooManyFunctions")
class RootViewModel @Inject constructor(
    private val ivyContext: IvyWalletCtx,
    private val nav: Navigation,
    private val settingsDao: SettingsDao,
    private val sharedPrefs: SharedPrefs,
    private val transactionReminderLogic: TransactionReminderLogic,
    private val migrationsManager: MigrationsManager,
    private val legalRepo: LegalRepository,
) : ViewModel() {

    companion object {
        const val EXTRA_ADD_TRANSACTION_TYPE = "add_transaction_type_extra"

        // Default background-lock delay in seconds when the user has not chosen one.
        const val USER_INACTIVITY_TIME_LIMIT = 60 // Time in seconds
    }

    /**
     * The user-chosen delay (seconds) before the app locks in the background. Read fresh at each
     * timer start so a change in Settings takes effect without an app restart. 0 = Instantly.
     */
    private fun lockDelaySeconds(): Int =
        sharedPrefs.getInt(SharedPrefs.LOCK_DELAY_SECONDS, USER_INACTIVITY_TIME_LIMIT)

    private var appLockEnabled = false

    private val _appLocked = MutableStateFlow<Boolean?>(null)
    val appLocked = _appLocked.readOnly()

    fun start(systemDarkMode: Boolean, intent: Intent) {
        viewModelScope.launch {
            TestIdlingResource.increment()

            ioThread {
                val theme = settingsDao.findAll().firstOrNull()?.theme
                    ?: if (systemDarkMode) Theme.DARK else Theme.LIGHT
                ivyContext.switchTheme(theme)

                ivyContext.initStartDayOfMonthInMemory(sharedPrefs = sharedPrefs)
            }

            TestIdlingResource.decrement()
        }

        viewModelScope.launch {
            TestIdlingResource.increment()

            ioThread {
                appLockEnabled = sharedPrefs.getBoolean(SharedPrefs.APP_LOCK_ENABLED, false)
                // initial app locked state
                _appLocked.value = appLockEnabled

                if (isOnboardingCompleted()) {
                    navigateOnboardedUser(intent)
                } else {
                    nav.navigateTo(OnboardingScreen)
                }
                if (!legalRepo.isDisclaimerAccepted()) {
                    nav.navigateTo(DisclaimerScreen)
                }
            }

            TestIdlingResource.decrement()
        }

        viewModelScope.launch {
            migrationsManager.executeMigrations()
        }
    }

    private fun navigateOnboardedUser(intent: Intent) {
        if (!handleSpecialStart(intent)) {
            nav.navigateTo(MainScreen)
            transactionReminderLogic.scheduleReminder()
        }
    }

    @Suppress("SwallowedException")
    private fun handleSpecialStart(intent: Intent): Boolean {
        val addTrnType: TransactionType? = try {
            intent.getSerializableExtra(EXTRA_ADD_TRANSACTION_TYPE) as? TransactionType
                ?: TransactionType.valueOf(intent.getStringExtra(EXTRA_ADD_TRANSACTION_TYPE) ?: "")
        } catch (e: IllegalArgumentException) {
            null
        }

        if (addTrnType != null) {
            nav.navigateTo(
                EditTransactionScreen(
                    initialTransactionId = null,
                    type = addTrnType
                )
            )

            return true
        }

        return false
    }

    @Suppress("EmptyFunctionBlock")
    fun handleBiometricAuthResult(
        onAuthSuccess: () -> Unit = {}
    ): BiometricPrompt.AuthenticationCallback {
        return object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                Timber.d(stringRes(R.string.authentication_succeeded))
                unlockApp()
                onAuthSuccess()
            }

            override fun onAuthenticationFailed() {
                Timber.d(stringRes(R.string.authentication_failed))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            }
        }
    }

    private fun isOnboardingCompleted(): Boolean {
        return sharedPrefs.getBoolean(SharedPrefs.ONBOARDING_COMPLETED, false)
    }

    // App Lock & UserInactivity --------------------------------------------------------------------
    fun isAppLockEnabled(): Boolean {
        return sharedPrefs.getBoolean(SharedPrefs.APP_LOCK_ENABLED, false)
    }

    fun isAppLocked(): Boolean {
        // by default we assume that the app is locked
        return appLocked.value ?: true
    }

    fun lockApp() {
        _appLocked.value = true
    }

    fun unlockApp() {
        _appLocked.value = false
        // The user is active again; restart the delay from zero so the next background cycle
        // waits the full chosen delay instead of locking immediately (the timer is otherwise
        // only reset on an early return, so post-lock cycles would re-lock instantly).
        resetUserInactiveTimer()
    }

    private var backgroundStartedAt: Long? = null
    private var userInactiveJob: Job? = null

    fun startUserInactiveTimeCounter() {
        if (!isAppLockEnabled() || isAppLocked() || backgroundStartedAt != null) return
        backgroundStartedAt = SystemClock.elapsedRealtime()
        val delayMillis = lockDelaySeconds().coerceAtLeast(0) * 1000L
        if (delayMillis == 0L) {
            lockApp()
        } else {
            userInactiveJob = viewModelScope.launch {
                delay(delayMillis)
                if (backgroundStartedAt != null && isAppLockEnabled()) lockApp()
            }
        }
    }

    fun checkUserInactiveTimeStatus() {
        // elapsedRealtime includes device sleep; counting coroutine ticks does not.
        val started = backgroundStartedAt
        if (started != null && isAppLockEnabled() &&
            SystemClock.elapsedRealtime() - started >= lockDelaySeconds().coerceAtLeast(0) * 1000L) {
            lockApp()
        }
        resetUserInactiveTimer()
    }

    fun resetUserInactiveTimer() {
        userInactiveJob?.cancel()
        userInactiveJob = null
        backgroundStartedAt = null
    }
}
