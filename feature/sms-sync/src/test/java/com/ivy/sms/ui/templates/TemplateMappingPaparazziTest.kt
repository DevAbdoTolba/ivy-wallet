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
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardRole
import com.ivy.ui.testing.PaparazziScreenshotTest
import com.ivy.ui.testing.PaparazziTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(TestParameterInjector::class)
class TemplateMappingPaparazziTest(
    @TestParameter
    private val theme: PaparazziTheme,
) : PaparazziScreenshotTest() {

    @Test
    fun mappingScreen_threeWildcards_amountAndDateBound() {
        snapshot(theme) {
            TemplateMappingPreview(
                state = TemplateMappingViewState(
                    templateId = SmsTemplateId(UUID.randomUUID()),
                    pattern = "Purchase of <*> at <*> on <*>",
                    exampleBody = "Purchase of 12.34 at Cafe on 2026-04-28",
                    wildcards = persistentListOf(
                        WildcardChip(WildcardId(UUID.randomUUID()), 2, "12.34", WildcardRole.Expense),
                        WildcardChip(WildcardId(UUID.randomUUID()), 4, "Cafe", WildcardRole.Merchant),
                        WildcardChip(WildcardId(UUID.randomUUID()), 6, "2026-04-28", WildcardRole.DateOnly),
                    ),
                ),
            )
        }
    }
}

@Composable
private fun TemplateMappingPreview(state: TemplateMappingViewState) {
    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Tap each highlighted segment in the message")
        Text(state.exampleBody.ifBlank { state.pattern })
        state.wildcards.forEach { Text("• ${it.exampleValue} → ${it.role}") }
    }
}
