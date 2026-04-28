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
import com.ivy.sms.domain.model.TransactionClassification
import com.ivy.sms.domain.model.WildcardId
import com.ivy.sms.domain.model.WildcardMapping
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
    fun mappingScreen_threeWildcards_classificationPicked() {
        snapshot(theme) {
            TemplateMappingPreview(
                state = TemplateMappingViewState(
                    templateId = SmsTemplateId(UUID.randomUUID()),
                    pattern = "Purchase of <*> at <*> on <*>",
                    wildcards = persistentListOf(
                        WildcardChip(WildcardId(UUID.randomUUID()), 2, WildcardMapping.Amount),
                        WildcardChip(WildcardId(UUID.randomUUID()), 4, WildcardMapping.Merchant),
                        WildcardChip(WildcardId(UUID.randomUUID()), 6, WildcardMapping.DateTime),
                    ),
                    classification = TransactionClassification.EXPENSE,
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
        Text("Tap each <*> in the message")
        Text(state.pattern)
        state.wildcards.forEach { Text("• ${it.mapping}") }
        Text("Classification: ${state.classification?.name ?: "—"}")
    }
}
