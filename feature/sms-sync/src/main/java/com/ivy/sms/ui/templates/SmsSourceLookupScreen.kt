package com.ivy.sms.ui.templates

import android.provider.Telephony
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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.model.TransactionId
import com.ivy.data.repository.TransactionRepository
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.navigation.navigation
import com.ivy.wallet.ui.theme.components.BackButtonType
import com.ivy.wallet.ui.theme.components.IvyToolbar
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.withContext
import java.util.UUID

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SmsSourceLookupEntryPoint {
    fun transactionRepository(): TransactionRepository
    fun dispatchers(): DispatchersProvider
}

@Composable
fun SmsSourceLookupScreen(transactionId: String) {
    val context = LocalContext.current
    val nav = navigation()
    val entry = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            SmsSourceLookupEntryPoint::class.java,
        )
    }

    var status by remember { mutableStateOf("Loading…") }
    var body by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(transactionId) {
        val txId = runCatching { TransactionId(UUID.fromString(transactionId)) }.getOrNull()
        if (txId == null) {
            status = "Invalid transaction id."
            return@LaunchedEffect
        }
        val transaction = entry.transactionRepository().findById(txId)
        val meta = transaction?.metadata
        val sender = meta?.smsSourceSenderId
        val timestamp = meta?.smsSourceTimestamp?.toEpochMilli()
        val dedupKey = meta?.smsSourceDedupKey
        if (sender == null || timestamp == null || dedupKey == null) {
            status = "No SMS source recorded for this transaction."
            return@LaunchedEffect
        }
        val resolved = withContext(entry.dispatchers().io) {
            runCatching {
                context.contentResolver.query(
                    Telephony.Sms.Inbox.CONTENT_URI,
                    arrayOf(Telephony.Sms.BODY),
                    "${Telephony.Sms.ADDRESS} = ? AND ${Telephony.Sms.DATE} = ?",
                    arrayOf(sender, timestamp.toString()),
                    null,
                )?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            }.getOrNull()
        }
        if (resolved.isNullOrBlank()) {
            status = "Original SMS no longer available in the device inbox."
        } else {
            status = "Source SMS"
            body = resolved
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(UI.colors.pure)
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            IvyToolbar(
                onBack = { nav.back() },
                backButtonType = BackButtonType.BACK,
            ) {
                Spacer(Modifier.width(16.dp))
                Text(
                    text = "SMS source",
                    style = UI.typo.h2.style(
                        color = UI.colors.pureInverse,
                        fontWeight = FontWeight.ExtraBold,
                    ),
                )
            }

            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = status,
                    style = UI.typo.b1.style(
                        color = UI.colors.pureInverse,
                        fontWeight = FontWeight.ExtraBold,
                    ),
                )
                body?.let { content ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(UI.shapes.r4)
                            .background(UI.colors.medium)
                            .padding(16.dp),
                    ) {
                        Text(
                            text = content,
                            style = UI.typo.b2.style(
                                color = UI.colors.pureInverse,
                                fontWeight = FontWeight.Medium,
                            ),
                        )
                    }
                }
            }
        }
    }
}
