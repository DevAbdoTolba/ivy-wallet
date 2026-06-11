package com.ivy.sms.ui.permission

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Live READ_SMS permission state plus a launcher for the system dialog.
 * [state] recomputes on every grant result and on every ON_RESUME (catches
 * grants made in system settings while the app was backgrounded).
 */
data class SmsPermissionHandle(
    val state: PermissionState,
    val request: () -> Unit,
)

/**
 * Reusable READ_SMS permission hook — the inline counterpart of
 * [PermissionGate]. The gate keeps the full-screen denied UI for the
 * surviving template/review routes; modal surfaces (the SMS setup sheet)
 * use this directly so the permission ask happens in place.
 */
@Composable
fun rememberSmsPermission(
    onStateChanged: (PermissionState) -> Unit = {},
): SmsPermissionHandle {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val latestOnStateChanged by rememberUpdatedState(onStateChanged)

    fun current(): PermissionState {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return PermissionState.Granted
        val activity = context as? Activity
        val rationale = activity?.shouldShowRequestPermissionRationale(Manifest.permission.READ_SMS) == true
        return if (rationale) PermissionState.Denied else PermissionState.PermanentlyDenied
    }

    var state by remember { mutableStateOf(current()) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        state = if (granted) PermissionState.Granted else current()
        latestOnStateChanged(state)
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val refreshed = current()
                if (refreshed != state) {
                    state = refreshed
                    latestOnStateChanged(refreshed)
                }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    return SmsPermissionHandle(
        state = state,
        request = { launcher.launch(Manifest.permission.READ_SMS) },
    )
}
