package com.ivy.sms.ui.templates

import com.ivy.sms.data.SmsTemplateRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Lets the mapping screen fetch a template directly from the repository as a
 * safety net when the ViewModel-side load gets shadowed by a Compose /
 * lifecycle race. This is a deliberate redundancy — both paths write into the
 * same VM state via `seedFromScreen` so whichever lands first wins.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TemplateLookupEntryPoint {
    fun templateRepo(): SmsTemplateRepository
}
