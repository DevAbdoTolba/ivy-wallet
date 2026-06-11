package com.ivy.sms.ui.permission

import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
    val permission = rememberSmsPermission(onStateChanged = onStateChanged)
    var hasAutoLaunched by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        onStateChanged(permission.state)
        if (permission.state != PermissionState.Granted && !hasAutoLaunched) {
            hasAutoLaunched = true
            permission.request()
        }
    }

    when (permission.state) {
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
                    onClick = permission.request,
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
