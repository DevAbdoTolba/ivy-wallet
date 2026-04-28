package com.ivy.sms.di

import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.PendingReviewItemRepositoryImpl
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.data.SenderAccountLinkRepositoryImpl
import com.ivy.sms.data.SmsInboxDataSource
import com.ivy.sms.data.SmsInboxDataSourceImpl
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.data.SmsTemplateRepositoryImpl
import com.ivy.sms.domain.usecase.SyncSmsUseCase
import com.ivy.sms.domain.usecase.SyncSmsUseCaseImpl
import com.ivy.sms.startup.SmsSyncAppStartup
import com.ivy.sms.startup.SmsSyncAppStartupImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SmsSyncModule {

    @Binds
    @Singleton
    abstract fun bindSmsTemplateRepository(impl: SmsTemplateRepositoryImpl): SmsTemplateRepository

    @Binds
    @Singleton
    abstract fun bindSenderAccountLinkRepository(impl: SenderAccountLinkRepositoryImpl): SenderAccountLinkRepository

    @Binds
    @Singleton
    abstract fun bindPendingReviewItemRepository(impl: PendingReviewItemRepositoryImpl): PendingReviewItemRepository

    @Binds
    @Singleton
    abstract fun bindSmsInboxDataSource(impl: SmsInboxDataSourceImpl): SmsInboxDataSource

    @Binds
    @Singleton
    abstract fun bindSyncSmsUseCase(impl: SyncSmsUseCaseImpl): SyncSmsUseCase

    @Binds
    @Singleton
    abstract fun bindSmsSyncAppStartup(impl: SmsSyncAppStartupImpl): SmsSyncAppStartup
}
