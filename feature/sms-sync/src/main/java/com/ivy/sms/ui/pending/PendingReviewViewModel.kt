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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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

    fun setWalletFilter(walletId: com.ivy.data.model.AccountId?) {
        if (activeWalletFilter == walletId) return
        activeWalletFilter = walletId
    }

    init {
        // Drain pending items only when the SET of active template ids actually
        // changes — not on every observeAll emit. The previous version re-ran
        // for every upsert (including matchCount-only refreshes), which fanned
        // out into 268 pending × 2 templates = 536 route calls per emit and
        // explained the 2247 AMOUNT_NOT_PARSEABLE log lines. distinctUntilChanged
        // on the active-id set means drainPending fires only when a template
        // moves into or out of ACTIVE — which IS the situation that can free
        // stuck items.
        viewModelScope.launch {
            templateRepo.observeAll()
                .map { all -> all.filter { it.state == TemplateState.ACTIVE }.map { it.id }.toSet() }
                .distinctUntilChanged()
                .collect { _ -> drainPending() }
        }
    }

    /**
     * For every queued pending item, route via that item's OWN (re-fetched)
     * template — picks up the latest state if it just transitioned to ACTIVE,
     * but stays a single route() per item rather than the previous
     * pending × candidates fan-out. The "first-ever item stuck" case the
     * fan-out was meant to handle is already covered by [MapTemplateUseCase]'s
     * post-save reprocess, which routes each pending item via its own template
     * the moment its template gets mapped — so this drain just needs to handle
     * later state changes (e.g., a template flipping back to ACTIVE after
     * being un-blacklisted).
     */
    private suspend fun drainPending() {
        try {
            val items = pendingRepo.findAll().getOrNull().orEmpty()
            if (items.isEmpty()) return
            val links = senderRepo.findAll().getOrNull().orEmpty()
            val senderToAccount = links.associate { it.senderId to it.accountId }
            for (item in items) {
                val tpl = templateRepo.findById(item.template.id).getOrNull() ?: continue
                if (tpl.state != TemplateState.ACTIVE) continue
                val outcome = route(item.sms, tpl, senderToAccount).getOrNull()
                if (outcome is RouteOutcome.Created) {
                    pendingRepo.dismiss(item.id)
                    prefs.incrementReviewedTotal()
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
        val discovered = prefs.observeDiscoveredTotal().collectAsState(initial = 0)
        // Reactive sender filter: subscribing to senderRepo.observeAll() avoids
        // the previous launch-then-set-state race where the screen rendered
        // unfiltered global items briefly and never recovered if viewModelScope
        // hiccupped on the user's device. With Flow-backed state, the moment
        // links land we get the right Set immediately.
        val allLinks = senderRepo.observeAll().collectAsState(initial = emptyList())
        val activeFilter = activeWalletFilter
        val sendersForWallet: Set<String>? = activeFilter?.let { wid ->
            allLinks.value.filter { it.accountId == wid }.map { it.senderId }.toSet()
        }
        val templateById = templates.value.associateBy { it.id.value.toString() }
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
            discoveredTotal = discovered.value,
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
