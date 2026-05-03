package com.ivy.sms.ui.wallet

import com.ivy.data.repository.AccountRepository
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SenderAccountLinkRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Bundles every read the WalletSmsConfigScreen needs into a single
 * Hilt EntryPoint so the screen can fetch data directly via Compose's
 * produceState — bypassing the ViewModel lifecycle entirely.
 *
 * Why this exists: the project's NavigationRoot clears the entire
 * ViewModelStore on every screen change (see NavigationRoot.kt). That
 * means the WalletSmsConfigViewModel is rebuilt from scratch every time
 * the user back-navigates to this screen, and its initial state is
 * always empty. The async DB reload eventually lands but the user sees
 * a flash of "No SMS chat is linked" or a loading card in the meantime,
 * which feels broken.
 *
 * By reading the data directly in the @Composable here, the screen
 * shows the actual linked sender the moment the DAO returns — and the
 * VM is only used for write actions (syncNow, unlink).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WalletConfigDataEntryPoint {
    fun accountRepository(): AccountRepository
    fun senderLinkRepository(): SenderAccountLinkRepository
    fun pendingRepository(): PendingReviewItemRepository
}
