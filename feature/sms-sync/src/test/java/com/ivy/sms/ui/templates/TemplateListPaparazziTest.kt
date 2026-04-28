package com.ivy.sms.ui.templates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import com.ivy.sms.domain.model.ScanProgress
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.ui.testing.PaparazziScreenshotTest
import com.ivy.ui.testing.PaparazziTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(TestParameterInjector::class)
class TemplateListPaparazziTest(
    @TestParameter
    private val theme: PaparazziTheme,
) : PaparazziScreenshotTest() {

    @Test
    fun emptyState() {
        snapshot(theme) {
            TemplateListPreview(
                state = TemplateListViewState(),
            )
        }
    }

    @Test
    fun scanning_withProgress() {
        snapshot(theme) {
            TemplateListPreview(
                state = TemplateListViewState(
                    scanProgress = ScanProgress(
                        processed = 12,
                        total = 50,
                        newTemplatesDiscovered = 3,
                        transactionsCreated = 1,
                        itemsQuarantined = 0,
                    ),
                ),
            )
        }
    }

    @Test
    fun mixedRows() {
        snapshot(theme) {
            TemplateListPreview(
                state = TemplateListViewState(
                    templates = persistentListOf(
                        TemplateRowViewState(SmsTemplateId(UUID.randomUUID()), "Active row", TemplateState.ACTIVE, 7),
                        TemplateRowViewState(SmsTemplateId(UUID.randomUUID()), "Unmapped 1", TemplateState.UNMAPPED, 3),
                        TemplateRowViewState(SmsTemplateId(UUID.randomUUID()), "Unmapped 2", TemplateState.UNMAPPED, 1),
                        TemplateRowViewState(SmsTemplateId(UUID.randomUUID()), "Pending review", TemplateState.PENDING_REVIEW, 2),
                    ),
                    pendingReviewCount = 2,
                ),
            )
        }
    }
}

@Composable
internal fun TemplateListPreview(state: TemplateListViewState) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("SMS Templates (preview snapshot)")
        state.scanProgress?.let {
            Text("Scanning… ${it.processed} / ${it.total}")
        }
        if (state.templates.isEmpty()) {
            Text("No templates yet — pull down or tap Sync to scan.")
        } else {
            state.templates.forEach { row ->
                Text("${row.preview} — ${row.state.name} (${row.matchCount})")
            }
        }
    }
}
