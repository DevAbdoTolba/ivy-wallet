package com.ivy.sms.ui.templates

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ivy.design.l0_system.UI
import com.ivy.design.l0_system.style
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.usecase.ReprocessHistoricalUseCase
import com.ivy.sms.domain.usecase.ReprocessPreview
import com.ivy.wallet.ui.theme.Blue
import com.ivy.wallet.ui.theme.Gray
import com.ivy.wallet.ui.theme.Green
import com.ivy.wallet.ui.theme.Red
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(UI.shapes.r4)
            .background(Blue.copy(alpha = 0.12f))
            .clickable {
                scope.launch {
                    useCase.preview(templateId).fold(
                        { error = it },
                        { preview = it },
                    )
                }
            }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Re-process historical messages",
            style = UI.typo.b2.style(
                color = Blue,
                fontWeight = FontWeight.Bold,
            ),
        )
    }

    val p = preview
    if (p != null) {
        AlertDialog(
            onDismissRequest = { preview = null },
            title = {
                Text(
                    text = "Re-process ${p.matchingMessages} messages",
                    style = UI.typo.h2.style(
                        color = UI.colors.pureInverse,
                        fontWeight = FontWeight.ExtraBold,
                    ),
                )
            },
            text = {
                Text(
                    text = "${p.newTransactionsToCreate} would create new transactions; the rest are already imported and will be skipped.",
                    style = UI.typo.b2.style(
                        color = UI.colors.gray,
                        fontWeight = FontWeight.Medium,
                    ),
                )
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
                }) { Text("Re-process", color = Green) }
            },
            dismissButton = {
                TextButton(onClick = { preview = null }) { Text("Cancel", color = Gray) }
            },
            containerColor = UI.colors.pure,
        )
    }

    resultMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { resultMessage = null },
            title = {
                Text(
                    text = "Done",
                    style = UI.typo.h2.style(
                        color = UI.colors.pureInverse,
                        fontWeight = FontWeight.ExtraBold,
                    ),
                )
            },
            text = {
                Text(
                    text = msg,
                    style = UI.typo.b2.style(
                        color = UI.colors.gray,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { resultMessage = null }) { Text("OK", color = Green) }
            },
            containerColor = UI.colors.pure,
        )
    }

    error?.let { msg ->
        AlertDialog(
            onDismissRequest = { error = null },
            title = {
                Text(
                    text = "Error",
                    style = UI.typo.h2.style(
                        color = Red,
                        fontWeight = FontWeight.ExtraBold,
                    ),
                )
            },
            text = {
                Text(
                    text = msg,
                    style = UI.typo.b2.style(
                        color = UI.colors.gray,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { error = null }) { Text("OK", color = Red) }
            },
            containerColor = UI.colors.pure,
        )
    }

}
