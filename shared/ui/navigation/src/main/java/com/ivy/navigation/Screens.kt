package com.ivy.navigation

import com.ivy.base.legacy.Transaction
import com.ivy.base.model.TransactionType
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import java.util.UUID

data object MainScreen : Screen {
    override val isLegacy: Boolean
        get() = true
}

data object OnboardingScreen : Screen {
    override val isLegacy: Boolean
        get() = true
}

data class CSVScreen(
    val launchedFromOnboarding: Boolean
) : Screen {
    override val isLegacy: Boolean
        get() = true
}

data class EditTransactionScreen(
    val initialTransactionId: UUID?,
    val type: TransactionType,
    // extras
    val accountId: UUID? = null,
    val categoryId: UUID? = null
) : Screen {
    override val isLegacy: Boolean
        get() = true
}

data class TransactionsScreen(
    val accountId: UUID? = null,
    val categoryId: UUID? = null,
    val unspecifiedCategory: Boolean? = false,
    val transactionType: TransactionType? = null,
    val accountIdFilterList: List<UUID> = persistentListOf(),
    val transactions: List<Transaction> = persistentListOf()
) : Screen {
    override val isLegacy: Boolean
        get() = true
}

data class PieChartStatisticScreen(
    val type: TransactionType,
    val filterExcluded: Boolean = true,
    val accountList: ImmutableList<UUID> = persistentListOf(),
    val transactions: ImmutableList<Transaction> = persistentListOf(),
    val treatTransfersAsIncomeExpense: Boolean = false
) : Screen {
    override val isLegacy: Boolean
        get() = true
}

data class EditPlannedScreen(
    val plannedPaymentRuleId: UUID?,
    val type: TransactionType,
    val amount: Double? = null,
    val accountId: UUID? = null,
    val categoryId: UUID? = null,
    val title: String? = null,
    val description: String? = null,
) : Screen {
    override val isLegacy: Boolean
        get() = true

    fun mandatoryFilled(): Boolean {
        return amount != null && amount > 0.0 &&
                accountId != null
    }
}

data object BalanceScreen : Screen {
    override val isLegacy: Boolean
        get() = true
}

data object PlannedPaymentsScreen : Screen {
    override val isLegacy: Boolean
        get() = true
}

data object CategoriesScreen : Screen {
    override val isLegacy: Boolean
        get() = true
}

data object SettingsScreen : Screen {
    override val isLegacy: Boolean
        get() = true
}

data class ImportScreen(
    val launchedFromOnboarding: Boolean
) : Screen {
    override val isLegacy: Boolean
        get() = true
}

data object ReportScreen : Screen {
    override val isLegacy: Boolean
        get() = true
}

data object BudgetScreen : Screen {
    override val isLegacy: Boolean
        get() = true
}

data object LoansScreen : Screen {
    override val isLegacy: Boolean
        get() = true
}

data object SearchScreen : Screen {
    override val isLegacy: Boolean
        get() = true
}

data class LoanDetailsScreen(
    val loanId: UUID
) : Screen {
    override val isLegacy: Boolean
        get() = true
}

data object ExchangeRatesScreen : Screen {
    override val isLegacy: Boolean
        get() = true
}

data object FeaturesScreen : Screen

data object AttributionsScreen : Screen

data object ContributorsScreen : Screen

data object ReleasesScreen : Screen

data object DisclaimerScreen : Screen

data object PollScreen : Screen

/**
 * The SMS templates list. [walletId] scopes the list to templates whose
 * sender is linked to that wallet — opened from a wallet's SMS config the
 * user must only see THAT wallet's templates. Null = the global list
 * (reached from the Home overflow menu). The user reported wallet-X's
 * templates showing up while working inside wallet-Y precisely because the
 * per-wallet "Templates" button navigated here with no scope.
 */
data class SmsExtractionScreen(
    val walletId: String? = null,
) : Screen {
    override val isLegacy: Boolean = false
}

data object PendingReviewScreen : Screen {
    override val isLegacy: Boolean = false
}

/**
 * Per-wallet variant of the pending review queue — filters to items whose
 * sender is linked to [walletId]. The wallet config screen routes here so
 * each wallet has its own scoped queue, separate from the global review.
 */
data class WalletPendingReviewScreen(val walletId: String) : Screen {
    override val isLegacy: Boolean = false
}

data class TemplateMappingScreen(
    val templateId: String,
    /**
     * Optional pending-item id the user tapped to reach this screen. When
     * set, the mapping screen shows THAT message's body in the chip canvas
     * instead of `template.exampleBody` (which is the first-ever sample of
     * the cluster). The user reported that tapping an SMS saying "+40 EGP"
     * could open the mapper rendered around a sibling SMS like "+89 EGP",
     * making it impossible to act on the specific message they cared about.
     * Roles and pattern still come from the template — only the visible
     * body changes.
     */
    val pendingItemId: String? = null,
    /**
     * Wallet the user opened this template from. When set, the mapping
     * screen and its save reprocess scope to pending items linked to THIS
     * wallet — even if the sender is/was linked to a different wallet.
     * Without this, mapping a template in wallet-2 could silently route
     * wallet-1's stale pending items into wallet-2 (the user's cross-wallet
     * leak report).
     */
    val walletId: String? = null,
) : Screen {
    override val isLegacy: Boolean = false
}

data class LinkSenderToWalletScreen(
    val senderId: String,
) : Screen {
    override val isLegacy: Boolean = false
}

data class SmsSourceLookupScreen(
    val transactionId: String,
) : Screen {
    override val isLegacy: Boolean = false
}

/** Per-wallet "Link SMS chat" picker — shows top-10 senders + free-text input. */
data class WalletSmsLinkScreen(
    val walletId: String,
) : Screen {
    override val isLegacy: Boolean = false
}

/** Per-wallet config surface — last-sync status, Sync now, templates, pending review. */
data class WalletSmsConfigScreen(
    val walletId: String,
) : Screen {
    override val isLegacy: Boolean = false
}