package com.ivy.sms.ui.pending

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.data.SmsWatermarkPreferences
import com.ivy.sms.domain.model.PendingReviewItemId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.usecase.BlacklistTemplateUseCase
import com.ivy.sms.domain.usecase.ResolvePendingItemUseCase
import com.ivy.sms.domain.usecase.RouteOutcome
import com.ivy.sms.domain.usecase.RouteSmsUseCase
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@Stable
@HiltViewModel
class PendingReviewViewModel @Inject constructor(
    private val pendingRepo: PendingReviewItemRepository,
    private val templateRepo: SmsTemplateRepository,
    private val senderRepo: SenderAccountLinkRepository,
    private val resolve: ResolvePendingItemUseCase,
    private val route: RouteSmsUseCase,
    private val blacklist: BlacklistTemplateUseCase,
    private val prefs: SmsWatermarkPreferences,
) : ComposeViewModel<PendingReviewViewState, PendingReviewEvent>() {

    private val expanded = mutableStateOf<Set<String>>(emptySet())

    /**
     * Optional wallet filter. When non-null, [uiState] only emits items
     * whose sender is linked to this wallet — that's how the per-wallet
     * review queue (reachable from the wallet config screen) shows just
     * the messages that affect THAT wallet, separate from the global
     * "Review all" queue.
     */
    private var activeWalletFilter by mutableStateOf<com.ivy.data.model.AccountId?>(null)
    private var walletSenders by mutableStateOf<Set<String>?>(null)

    fun setWalletFilter(walletId: com.ivy.data.model.AccountId?) {
        if (activeWalletFilter == walletId) return
        activeWalletFilter = walletId
        if (walletId == null) {
            walletSenders = null
            return
        }
        viewModelScope.launch {
            val links = senderRepo.findByAccountId(walletId).getOrNull().orEmpty()
            walletSenders = links.map { it.senderId }.toSet()
        }
    }

    init {
        // Re-drain every time the templates table changes — fires on VM init AND
        // whenever the user maps a template elsewhere and navigates back. The
        // latch-once approach previously here missed re-mappings done while this
        // VM stayed alive across navigation, which is exactly when the user is
        // most confused ("I just mapped it, why is it still here?").
        viewModelScope.launch {
            templateRepo.observeAll().collect { templates ->
                drainPending(templates.filter { it.state == TemplateState.ACTIVE })
            }
        }
    }

    /**
     * For every queued pending item, try every active template from the same
     * sender — not just the template the item was originally quarantined under.
     *
     * This fixes the "first-ever pending item is stuck" bug: if Drain clustered
     * the original SMS into Template A but the user mapped a similar-looking
     * Template B, the existing per-templateId drain would never resolve A's
     * pending item. Walking *all* same-sender ACTIVE templates lets B's mapping
     * pick up A's stranded item too. extractWildcardValues already returns null
     * if the body doesn't actually align with the template's literals, so we
     * can't false-positive here.
     */
    private suspend fun drainPending(activeTemplates: List<com.ivy.sms.domain.model.SmsTemplate>) {
        try {
            val items = pendingRepo.findAll().getOrNull().orEmpty()
            if (items.isEmpty()) return
            val links = senderRepo.findAll().getOrNull().orEmpty()
            val senderToAccount = links.associate { it.senderId to it.accountId }
            for (item in items) {
                val candidates = activeTemplates.filter {
                    it.senderIdHint == item.sms.senderId
                }
                for (tpl in candidates) {
                    val outcome = route(item.sms, tpl, senderToAccount).getOrNull()
                    if (outcome is RouteOutcome.Created) {
                        pendingRepo.dismiss(item.id)
                        prefs.incrementReviewedTotal()
                        break
                    }
                }
            }
        } catch (t: Throwable) {
            Timber.w(t, "PendingReview drain failed")
        }
    }

    @Composable
    override fun uiState(): PendingReviewViewState {
        val items = pendingRepo.observeAllRaw().collectAsState(initial = emptyList())
        val templates = templateRepo.observeAll().collectAsState(initial = emptyList())
        val reviewed = prefs.observeReviewedTotal().collectAsState(initial = 0)
        val mapped = prefs.observeTemplatesMappedTotal().collectAsState(initial = 0)
        val templateById = templates.value.associateBy { it.id.value.toString() }
        // For per-wallet view: limit to senders linked to the active wallet.
        val sendersForWallet: Set<String>? = walletSenders
        val rows = items.value.mapNotNull { e ->
            if (sendersForWallet != null && e.senderId !in sendersForWallet) return@mapNotNull null
            val tpl = templateById[e.templateId] ?: return@mapNotNull null
            val rolesByPosition = tpl.wildcardSlots.associate { it.positionInPattern to it.role }
            PendingItemRowViewState(
                id = e.id,
                itemId = PendingReviewItemId(java.util.UUID.fromString(e.id)),
                templateId = tpl.id,
                senderId = e.senderId,
                body = e.body,
                templatePattern = tpl.pattern,
                wildcardRolesByPosition = rolesByPosition,
                timestamp = e.messageEpochMillis,
                reason = e.quarantineReason,
                expanded = e.id in expanded.value,
            )
        }.toImmutableList()
        return PendingReviewViewState(
            items = rows.ifEmpty { persistentListOf() },
            reviewedTotal = reviewed.value,
            templatesMappedTotal = mapped.value,
            scopedToWallet = activeWalletFilter != null,
        )
    }

    override fun onEvent(event: PendingReviewEvent) {
        when (event) {
            is PendingReviewEvent.Dismiss -> {
                viewModelScope.launch {
                    resolve.dismiss(event.itemId)
                    prefs.incrementReviewedTotal()
                }
            }
            is PendingReviewEvent.MapTemplate -> { /* nav handled by Screen */ }
            is PendingReviewEvent.IgnoreForever -> {
                viewModelScope.launch { blacklist.enable(event.templateId) }
            }
            is PendingReviewEvent.ToggleExpand -> {
                expanded.value = if (event.id in expanded.value) {
                    expanded.value - event.id
                } else {
                    expanded.value + event.id
                }
            }
        }
    }
}
