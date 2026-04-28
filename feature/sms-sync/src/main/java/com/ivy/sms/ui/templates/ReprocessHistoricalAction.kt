package com.ivy.sms.ui.templates

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.usecase.ReprocessHistoricalUseCase
import com.ivy.sms.domain.usecase.ReprocessPreview
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ReprocessHistoricalEntryPoint {
    fun reprocess(): ReprocessHistoricalUseCase
}

@Composable
internal fun ReprocessHistoricalAction(templateId: SmsTemplateId) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val useCase = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ReprocessHistoricalEntryPoint::class.java,
        ).reprocess()
    }

    var preview by remember { mutableStateOf<ReprocessPreview?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    TextButton(onClick = {
        scope.launch {
            useCase.preview(templateId).fold(
                { error = it },
                { preview = it },
            )
        }
    }) {
        Text("Re-process historical messages")
    }

    val p = preview
    if (p != null) {
        AlertDialog(
            onDismissRequest = { preview = null },
            title = { Text("Re-process ${p.matchingMessages} messages") },
            text = {
                Text("${p.newTransactionsToCreate} would create new transactions; the rest are already imported and will be skipped.")
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        useCase.confirm(templateId, templateId.value.toString()).fold(
                            { error = it },
                            { r ->
                                resultMessage = "Created ${r.transactionsCreated} new transactions; ${r.itemsQuarantined} sent to review."
                            },
                        )
                        preview = null
                    }
                }) { Text("Re-process") }
            },
            dismissButton = {
                TextButton(onClick = { preview = null }) { Text("Cancel") }
            },
        )
    }

    resultMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { resultMessage = null },
            title = { Text("Done") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { resultMessage = null }) { Text("OK") }
            },
        )
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Error") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { error = null }) { Text("OK") }
            },
        )
    }
}
