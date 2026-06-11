package com.ivy.data.di

import com.ivy.data.db.dao.read.ReadPendingReviewItemDao
import com.ivy.data.db.dao.read.ReadSenderAccountLinkDao
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Lets Composables outside the Hilt graph (e.g. the legacy AccountModal in
 * `temp/legacy-code`) resolve a wallet's linked SMS sender — and its pending
 * review count for the SMS row badge — without taking a direct dependency on
 * `feature:sms-sync`. Use via
 * `EntryPointAccessors.fromApplication(ctx, SenderLinkLookupEntryPoint::class.java)`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SenderLinkLookupEntryPoint {
    fun readSenderAccountLinkDao(): ReadSenderAccountLinkDao
    fun readPendingReviewItemDao(): ReadPendingReviewItemDao
}
