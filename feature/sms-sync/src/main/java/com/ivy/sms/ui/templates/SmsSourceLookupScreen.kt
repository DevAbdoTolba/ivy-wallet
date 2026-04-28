package com.ivy.sms.ui.templates

import android.provider.Telephony
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.model.TransactionId
import com.ivy.data.repository.TransactionRepository
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
    val entry = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            SmsSourceLookupEntryPoint::class.java,
        )
    }

    var status by remember { mutableStateOf<String>("Loading…") }
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
            status = "Source SMS:"
            body = resolved
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.TopStart) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = status)
            body?.let { Text(text = it) }
        }
    }
}
