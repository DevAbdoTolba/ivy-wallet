package com.ivy.wallet

import android.os.SystemClock
import com.ivy.base.legacy.SharedPrefs
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RootViewModelLockTest {
    private var elapsed = 0L
    private var delaySeconds = 60
    private var enabled = true
    private lateinit var viewModel: RootViewModel

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } answers { elapsed }
        val prefs = mockk<SharedPrefs> {
            every { getBoolean(SharedPrefs.APP_LOCK_ENABLED, false) } answers { enabled }
            every { getInt(SharedPrefs.LOCK_DELAY_SECONDS, any()) } answers { delaySeconds }
        }
        viewModel = RootViewModel(mockk(), mockk(), mockk(), prefs, mockk(), mockk(), mockk())
        viewModel.unlockApp()
    }

    @After fun tearDown() {
        viewModel.resetUserInactiveTimer()
        unmockkStatic(SystemClock::class)
        Dispatchers.resetMain()
    }

    @Test fun `instant delay locks synchronously`() {
        delaySeconds = 0
        viewModel.startUserInactiveTimeCounter()
        viewModel.isAppLocked() shouldBe true
    }

    @Test fun `device sleep counts even when coroutine never ticks`() {
        viewModel.startUserInactiveTimeCounter()
        elapsed = 61_000
        viewModel.checkUserInactiveTimeStatus()
        viewModel.isAppLocked() shouldBe true
    }

    @Test fun `quick return starts a fresh delay next time`() {
        viewModel.startUserInactiveTimeCounter()
        elapsed = 50_000
        viewModel.checkUserInactiveTimeStatus()
        viewModel.isAppLocked() shouldBe false
        viewModel.startUserInactiveTimeCounter()
        elapsed = 70_000
        viewModel.checkUserInactiveTimeStatus()
        viewModel.isAppLocked() shouldBe false
    }

    @Test fun `all configured delays are honored at the boundary`() {
        for (seconds in listOf(60, 300, 900)) {
            delaySeconds = seconds
            viewModel.unlockApp()
            viewModel.startUserInactiveTimeCounter()
            elapsed += seconds * 1000L
            viewModel.checkUserInactiveTimeStatus()
            viewModel.isAppLocked() shouldBe true
        }
    }

    @Test fun `disabling lock takes effect without restarting the app`() {
        enabled = false
        viewModel.startUserInactiveTimeCounter()
        elapsed = 1_000_000
        viewModel.checkUserInactiveTimeStatus()
        viewModel.isAppLocked() shouldBe false
    }
}
