package com.ivy.sms.ui.pending

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.base.threading.DispatchersProvider
import com.ivy.data.model.AccountId
import com.ivy.data.repository.AccountRepository
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.data.SmsWatermarkPreferences
import com.ivy.sms.domain.model.PendingReviewItemId
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.usecase.BlacklistTemplateUseCase
import com.ivy.sms.domain.usecase.ResolvePendingItemUseCase
import com.ivy.sms.domain.usecase.RouteOutcome
import com.ivy.sms.domain.usecase.RouteSmsUseCase
import com.ivy.sms.domain.usecase.ScanInboxUseCase
import com.ivy.sms.ui.templates.WalletOption
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/** How long the in-place Undo affordance stays before the blacklist commits. */
internal const val IGNORE_UNDO_WINDOW_MILLIS = 5_000L

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
    private val scanInbox: ScanInboxUseCase,
    private val accountRepo: AccountRepository,
    private val dispatchers: DispatchersProvider,
) : ComposeViewModel<PendingReviewViewState, PendingReviewEvent>() {

    private val expanded = mutableStateOf<Set<String>>(emptySet())

    /** Wallet list for the needs-destination rows' "To" picker. */
    private val walletOptions =
        mutableStateOf<ImmutableList<WalletOption>>(persistentListOf())

    /** Per-row in-progress destination picks / remember flags / in-flight
     *  resolves for TRANSFER_NEEDS_DESTINATION rows. Keyed by the row id. */
    private val chosenDestinations = mutableStateOf<Map<String, AccountId>>(emptyMap())
    private val rememberFlags = mutableStateOf<Set<String>>(emptySet())
    private val resolvingIds = mutableStateOf<Set<String>>(emptySet())
    private val errorMsg = mutableStateOf<String?>(null)

    /**
     * Buffered "Ignore" awaiting its undo window. While set, the template's
     * items are hidden from [uiState] but nothing is persisted yet — Undo
     * just clears this and they reappear. The blacklist (which DELETES the
     * queued items via clearByTemplate) commits only after
     * [IGNORE_UNDO_WINDOW_MILLIS], or immediately if the VM dies first
     * (the custom nav clears the ViewModelStore on every screen change).
     */
    private var pendingIgnoreTemplate by mutableStateOf<SmsTemplateId?>(null)
    private var ignoreCommitJob: Job? = null

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
        // Wallet list for the needs-destination rows' "To" picker.
        viewModelScope.launch {
            walletOptions.value = accountRepo.findAll()
                .map { WalletOption(id = it.id, name = it.name.value) }
                .toImmutableList()
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
        // Live scan progress so the first sync after "Save & Sync now" is
        // visible right where the user will act on its results.
        val progress = scanInbox.progress.collectAsState(initial = null)
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
        val bufferedIgnoreId = pendingIgnoreTemplate?.value?.toString()
        var hiddenByIgnore = 0
        val rows = items.value.mapNotNull { e ->
            if (sendersForWallet != null && e.senderId !in sendersForWallet) return@mapNotNull null
            if (bufferedIgnoreId != null && e.templateId == bufferedIgnoreId) {
                // Soft-hidden while the ignore's undo window is open.
                hiddenByIgnore++
                return@mapNotNull null
            }
            val tpl = templateById[e.templateId] ?: return@mapNotNull null
            val rolesByPosition = tpl.wildcardSlots.associate { it.positionInPattern to it.role }
            val needsDestination = e.quarantineReason
                .substringBefore(':').trim() == "TRANSFER_NEEDS_DESTINATION"
            val fromId = allLinks.value.firstOrNull { it.senderId == e.senderId }?.accountId
            val chosenId = chosenDestinations.value[e.id]
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
                templateActive = tpl.state == TemplateState.ACTIVE,
                expanded = e.id in expanded.value,
                needsDestination = needsDestination,
                fromAccountId = fromId,
                fromWalletName = walletOptions.value.firstOrNull { it.id == fromId }?.name,
                chosenDestinationId = chosenId,
                chosenDestinationName = walletOptions.value.firstOrNull { it.id == chosenId }?.name,
                rememberDestination = e.id in rememberFlags.value,
                resolving = e.id in resolvingIds.value,
            )
        }.toImmutableList()
        return PendingReviewViewState(
            items = rows.ifEmpty { persistentListOf() },
            reviewedTotal = reviewed.value,
            templatesMappedTotal = mapped.value,
            discoveredTotal = discovered.value,
            scopedToWallet = activeWalletFilter != null,
            scanProgress = progress.value,
            pendingIgnoreTemplateId = pendingIgnoreTemplate,
            pendingIgnoreHiddenCount = hiddenByIgnore,
            walletOptions = walletOptions.value,
            error = errorMsg.value,
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
            is PendingReviewEvent.IgnoreForever -> bufferIgnore(event.templateId)
            PendingReviewEvent.UndoIgnore -> undoIgnore()
            is PendingReviewEvent.ToggleExpand -> {
                expanded.value = if (event.id in expanded.value) {
                    expanded.value - event.id
                } else {
                    expanded.value + event.id
                }
            }
            is PendingReviewEvent.ChooseDestination -> {
                errorMsg.value = null
                chosenDestinations.value = if (event.destinationId == null) {
                    chosenDestinations.value - event.id
                } else {
                    chosenDestinations.value + (event.id to event.destinationId)
                }
            }
            is PendingReviewEvent.ToggleRememberDestination -> {
                rememberFlags.value = if (event.id in rememberFlags.value) {
                    rememberFlags.value - event.id
                } else {
                    rememberFlags.value + event.id
                }
            }
            is PendingReviewEvent.CreateTransfer -> createTransfer(event.id, event.itemId)
        }
    }

    private fun createTransfer(rowId: String, itemId: PendingReviewItemId) {
        val destination = chosenDestinations.value[rowId]
        if (destination == null) {
            errorMsg.value = "Pick a destination wallet first"
            return
        }
        if (rowId in resolvingIds.value) return // double-tap guard
        errorMsg.value = null
        resolvingIds.value = resolvingIds.value + rowId
        viewModelScope.launch {
            try {
                val remember = rowId in rememberFlags.value
                val result = resolve.resolveWithDestination(
                    itemId = itemId,
                    destination = destination,
                    rememberForTemplate = remember,
                )
                result.fold(
                    { err ->
                        Timber.tag("SmsTrace").w("REVIEW ✗ resolveWithDestination: %s", err)
                        errorMsg.value = "Couldn't create the transfer — $err"
                    },
                    { outcome ->
                        if (outcome !is RouteOutcome.Created) {
                            Timber.tag("SmsTrace").w(
                                "REVIEW ✗ resolveWithDestination outcome=%s", outcome,
                            )
                            errorMsg.value =
                                "Couldn't create the transfer — check the destination wallet"
                        }
                        // Created: the row disappears via the reactive queue
                        // (dismiss inside the use case) — nothing else to do.
                    },
                )
            } finally {
                resolvingIds.value = resolvingIds.value - rowId
            }
        }
    }

    /**
     * Ignore with an undo window: hide the template's items immediately,
     * commit the (destructive — clearByTemplate deletes the queue rows)
     * blacklist only after [IGNORE_UNDO_WINDOW_MILLIS]. A second ignore
     * while one is buffered commits the first one right away.
     */
    private fun bufferIgnore(templateId: SmsTemplateId) {
        Timber.tag("SmsTrace").i("IGNORE_FOREVER (review, buffered) → tpl=%s", templateId.value)
        commitBufferedIgnoreNow()
        pendingIgnoreTemplate = templateId
        ignoreCommitJob = viewModelScope.launch {
            delay(IGNORE_UNDO_WINDOW_MILLIS)
            if (pendingIgnoreTemplate == templateId) pendingIgnoreTemplate = null
            commitIgnore(templateId)
        }
    }

    private fun undoIgnore() {
        Timber.tag("SmsTrace").i(
            "IGNORE_FOREVER (review) ↩ undone tpl=%s", pendingIgnoreTemplate?.value,
        )
        ignoreCommitJob?.cancel()
        ignoreCommitJob = null
        pendingIgnoreTemplate = null
    }

    private fun commitBufferedIgnoreNow() {
        val previous = pendingIgnoreTemplate ?: return
        ignoreCommitJob?.cancel()
        ignoreCommitJob = null
        pendingIgnoreTemplate = null
        viewModelScope.launch { commitIgnore(previous) }
    }

    private suspend fun commitIgnore(templateId: SmsTemplateId) {
        // NonCancellable: blacklist writes from this screen used to ride a
        // plain viewModelScope, which the custom nav's ViewModelStore clear
        // cancels mid-write — same silent no-op class as the mapping
        // screen's ignore button.
        withContext(NonCancellable) {
            blacklist.enable(templateId).onLeft { err ->
                Timber.tag("SmsTrace").w("IGNORE_FOREVER (review) ✗ %s", err)
            }
        }
    }

    override fun onCleared() {
        // The nav clears this VM on every screen change — a still-open undo
        // window must not silently drop the user's ignore. Commit on a scope
        // that survives the VM; blacklist.enable is idempotent so a race
        // with the delayed job is harmless.
        val buffered = pendingIgnoreTemplate
        if (buffered != null) {
            pendingIgnoreTemplate = null
            CoroutineScope(SupervisorJob() + dispatchers.io).launch {
                commitIgnore(buffered)
            }
        }
        super.onCleared()
    }
}
