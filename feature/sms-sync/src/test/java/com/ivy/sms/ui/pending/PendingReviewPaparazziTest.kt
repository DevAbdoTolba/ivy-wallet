package com.ivy.sms.ui.pending

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import com.ivy.sms.domain.model.PendingReviewItemId
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.ui.testing.PaparazziScreenshotTest
import com.ivy.ui.testing.PaparazziTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(TestParameterInjector::class)
class PendingReviewPaparazziTest(
    @TestParameter
    private val theme: PaparazziTheme,
) : PaparazziScreenshotTest() {

    @Test
    fun emptyQueue() {
        snapshot(theme) {
            PendingReviewPreview(state = PendingReviewViewState())
        }
    }

    @Test
    fun mixedReasons() {
        snapshot(theme) {
            PendingReviewPreview(
                state = PendingReviewViewState(
                    items = persistentListOf(
                        row(reason = "TEMPLATE_NOT_MAPPED"),
                        row(reason = "AMOUNT_NOT_PARSEABLE"),
                        row(reason = "SENDER_NOT_LINKED"),
                    ),
                ),
            )
        }
    }

    @Test
    fun expandedItem() {
        snapshot(theme) {
            PendingReviewPreview(
                state = PendingReviewViewState(
                    items = persistentListOf(row(expanded = true)),
                ),
            )
        }
    }

    private fun row(reason: String = "TEMPLATE_NOT_MAPPED", expanded: Boolean = false): PendingItemRowViewState {
        val id = UUID.randomUUID()
        return PendingItemRowViewState(
            id = id.toString(),
            itemId = PendingReviewItemId(id),
            templateId = SmsTemplateId(UUID.randomUUID()),
            senderId = "TestBank",
            body = "Purchase of 12.34 at Coffee Shop on 04/27/26.",
            templatePattern = "Purchase of <*> at <*> on <*>",
            timestamp = 1_700_000_000_000L,
            reason = reason,
            expanded = expanded,
        )
    }
}

@Composable
private fun PendingReviewPreview(state: PendingReviewViewState) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Pending Review (preview)")
        if (state.items.isEmpty()) {
            Text("No items to review.")
        } else {
            state.items.forEach { row ->
                Text("• ${row.senderId} — ${row.reason}")
                Text(if (row.expanded) row.body else row.body.take(60))
            }
        }
    }
}
