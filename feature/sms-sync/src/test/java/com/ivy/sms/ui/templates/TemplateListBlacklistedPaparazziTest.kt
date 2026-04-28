package com.ivy.sms.ui.templates

import com.google.testing.junit.testparameterinjector.TestParameter
import com.google.testing.junit.testparameterinjector.TestParameterInjector
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.ui.testing.PaparazziScreenshotTest
import com.ivy.ui.testing.PaparazziTheme
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(TestParameterInjector::class)
class TemplateListBlacklistedPaparazziTest(
    @TestParameter
    private val theme: PaparazziTheme,
) : PaparazziScreenshotTest() {

    @Test
    fun blacklistedRow_rendersWithBadge() {
        snapshot(theme) {
            TemplateListPreview(
                state = TemplateListViewState(
                    templates = persistentListOf(
                        TemplateRowViewState(SmsTemplateId(UUID.randomUUID()), "OTP <*>", TemplateState.BLACKLISTED, 12),
                        TemplateRowViewState(SmsTemplateId(UUID.randomUUID()), "Spent <*>", TemplateState.ACTIVE, 7),
                    ),
                ),
            )
        }
    }
}
