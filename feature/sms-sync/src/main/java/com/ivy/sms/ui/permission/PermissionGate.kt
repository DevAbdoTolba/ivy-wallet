package com.ivy.sms.ui.permission

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.wallet.ui.theme.components.IvyButton
import com.ivy.wallet.ui.theme.components.IvyOutlinedButton

@Composable
fun PermissionGate(
    modifier: Modifier = Modifier,
    onStateChanged: (PermissionState) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

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
    var hasAutoLaunched by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        state = if (granted) PermissionState.Granted else current()
        onStateChanged(state)
    }

    LaunchedEffect(Unit) {
        state = current()
        onStateChanged(state)
        if (state != PermissionState.Granted && !hasAutoLaunched) {
            hasAutoLaunched = true
            launcher.launch(Manifest.permission.READ_SMS)
        }
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state = current()
                onStateChanged(state)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    when (state) {
        PermissionState.Granted -> content()
        PermissionState.Denied,
        PermissionState.PermanentlyDenied -> Box(
            modifier = modifier
                .fillMaxSize()
                .background(UI.colors.pure)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "SMS access needed",
                    style = UI.typo.h2.style(
                        color = UI.colors.pureInverse,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                    ),
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "Ivy reads bank-message SMS to discover and import transactions automatically.",
                    style = UI.typo.b2.style(
                        color = UI.colors.gray,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    ),
                )
                Spacer(Modifier.height(32.dp))
                IvyButton(
                    text = "Try again",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { launcher.launch(Manifest.permission.READ_SMS) },
                )
                Spacer(Modifier.height(12.dp))
                IvyOutlinedButton(
                    text = "Open System Settings",
                    iconStart = null,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        context.startActivity(intent)
                    },
                )
            }
        }
    }
}
