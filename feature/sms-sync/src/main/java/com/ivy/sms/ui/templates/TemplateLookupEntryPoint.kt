package com.ivy.sms.ui.templates

import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SmsTemplateRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Lets the mapping screen fetch a template directly from the repository as a
 * safety net when the ViewModel-side load gets shadowed by a Compose /
 * lifecycle race. This is a deliberate redundancy — both paths write into the
 * same VM state via `seedFromScreen` so whichever lands first wins.
 *
 * Also exposes the pending-item repo so the screen can look up the specific
 * message the user tapped (when navigated with a `pendingItemId`) and render
 * THAT body in the chip canvas instead of the cluster's first-ever sample.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TemplateLookupEntryPoint {
    fun templateRepo(): SmsTemplateRepository
    fun pendingRepo(): PendingReviewItemRepository
}
