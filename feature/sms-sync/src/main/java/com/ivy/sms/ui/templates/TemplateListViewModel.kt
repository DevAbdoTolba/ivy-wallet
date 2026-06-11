package com.ivy.sms.ui.templates

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.data.model.AccountId
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SenderAccountLinkRepository
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.data.SmsWatermarkPreferences
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.usecase.ApplySyncPeriodUseCase
import com.ivy.sms.domain.usecase.BlacklistTemplateUseCase
import com.ivy.sms.domain.usecase.FindMatchingMessagesUseCase
import com.ivy.sms.domain.usecase.ScanInboxUseCase
import com.ivy.sms.startup.SmsSyncAppStartup
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

@Stable
@HiltViewModel
class TemplateListViewModel @Inject constructor(
    private val templateRepo: SmsTemplateRepository,
    private val pendingRepo: PendingReviewItemRepository,
    private val senderRepo: SenderAccountLinkRepository,
    private val scanInbox: ScanInboxUseCase,
    private val blacklist: BlacklistTemplateUseCase,
    private val findMatching: FindMatchingMessagesUseCase,
    private val applySyncPeriod: ApplySyncPeriodUseCase,
    private val watermarks: SmsWatermarkPreferences,
    private val appStartup: SmsSyncAppStartup,
) : ComposeViewModel<TemplateListViewState, TemplateListEvent>() {

    private var onTemplateOpen: ((SmsTemplateId) -> Unit)? = null
    private var onPending: (() -> Unit)? = null

    /**
     * When non-null, the list only shows templates whose `senderIdHint`
     * resolves (via [SenderAccountLinkRepository]) to this wallet. Opened
     * from a wallet's SMS config the user must only see THAT wallet's
     * templates — a global list here was the cross-wallet leak they
     * reported ("while in wallet Y, I saw wallet X's messages").
     */
    private var activeWalletFilter by mutableStateOf<String?>(null)

    fun setWalletFilter(id: String?) {
        if (activeWalletFilter != id) activeWalletFilter = id
    }

    // "Needs mapping" opens by default — it's the only group that requires
    // user action (the old Expense default hid unmapped templates one tap
    // deeper than necessary).
    private var expandedGroup by mutableStateOf<TemplateGroupKey?>(TemplateGroupKey.Unmapped)
    private var matchingByTemplate by mutableStateOf<Map<SmsTemplateId, List<String>>>(emptyMap())
    private var loadingTemplateIds by mutableStateOf<Set<SmsTemplateId>>(emptySet())
    private var displayCounts by mutableStateOf<Map<SmsTemplateId, Int>>(emptyMap())
    private var matchingModalTemplate by mutableStateOf<SmsTemplateId?>(null)
    private var matchingModalLimit by mutableStateOf(MATCHING_MODAL_PAGE_SIZE)
    private val preloadedIds = mutableSetOf<SmsTemplateId>()

    /**
     * Serialized preload queue. Row composition emits PreloadMatching events
     * for each visible template; without this they all hit findMatching at
     * once and saturate the IO dispatcher (the original "accordion lag" bug).
     * A single consumer coroutine drains the channel one id at a time, so
     * preloads happen in the background without UI stalls AND every row's
     * displayCount eventually matches what the modal will show — fixing the
     * "card says 11 but modal says 2" mismatch.
     */
    private val preloadQueue = Channel<SmsTemplateId>(capacity = Channel.UNLIMITED)

    init {
        viewModelScope.launch {
            preloadQueue.consumeAsFlow().collect { id ->
                if (id in matchingByTemplate) return@collect
                loadMatchingSync(id)
            }
        }
    }

    fun setNavigators(
        onTemplate: (SmsTemplateId) -> Unit,
        onPending: () -> Unit,
    ) {
        onTemplateOpen = onTemplate
        this.onPending = onPending
    }

    @Composable
    override fun uiState(): TemplateListViewState {
        val templates = remember(templateRepo) { templateRepo.observeAll() }
            .collectAsState(initial = emptyList())
        val pendingCount = remember(pendingRepo) { pendingRepo.observeCount() }
            .collectAsState(initial = 0)
        val progress = scanInbox.progress.collectAsState(initial = null)
        val links = remember(senderRepo) { senderRepo.observeAll() }
            .collectAsState(initial = emptyList())

        // Wallet scoping: when activeWalletFilter is set, keep only templates whose
        // senderIdHint links to that wallet. Reactive on senderRepo so a
        // freshly-linked sender shows up without a screen reload.
        val scopedSenders: Set<String>? = activeWalletFilter?.let { wid ->
            links.value
                .filter { it.accountId.value.toString() == wid }
                .map { it.senderId }
                .toSet()
        }
        val visibleTemplates = templates.value.filter { tpl ->
            scopedSenders == null || tpl.senderIdHint in scopedSenders
        }

        val rows = visibleTemplates.map { tpl ->
            tpl.toRow(
                matching = matchingByTemplate[tpl.id].orEmpty(),
                loading = tpl.id in loadingTemplateIds,
            )
        }.toImmutableList()

        return TemplateListViewState(
            templates = rows,
            scanProgress = progress.value,
            pendingReviewCount = pendingCount.value,
            expandedGroup = expandedGroup,
            displayCountByTemplate = displayCounts,
            matchingModalTemplate = matchingModalTemplate,
            matchingModalLimit = matchingModalLimit,
        )
    }

    override fun onEvent(event: TemplateListEvent) {
        when (event) {
            is TemplateListEvent.TemplateClicked -> onTemplateOpen?.invoke(event.id)
            is TemplateListEvent.ScanFurtherBack -> scanFurtherBack(event.lowerBoundEpochMillis)
            TemplateListEvent.OpenPendingReview -> onPending?.invoke()
            is TemplateListEvent.ToggleBlacklist -> toggleBlacklist(event.id)
            is TemplateListEvent.ToggleGroup -> {
                expandedGroup = if (expandedGroup == event.group) null else event.group
            }
            is TemplateListEvent.OpenMatching -> openMatching(event.id)
            TemplateListEvent.CloseMatching -> {
                matchingModalTemplate = null
                matchingModalLimit = MATCHING_MODAL_PAGE_SIZE
            }
            TemplateListEvent.LoadMoreMatching -> {
                matchingModalLimit += MATCHING_MODAL_PAGE_SIZE
            }
            is TemplateListEvent.PreloadMatching -> preloadMatching(event.id)
        }
    }

    private fun openMatching(id: SmsTemplateId) {
        matchingModalTemplate = id
        matchingModalLimit = MATCHING_MODAL_PAGE_SIZE
        if (id !in matchingByTemplate) loadMatching(id)
    }

    /**
     * Background-load each row's match count without firing N parallel
     * findMatching calls (the saturation that caused the original
     * accordion lag). Idempotent: each id queues at most once.
     */
    private fun preloadMatching(id: SmsTemplateId) {
        if (!preloadedIds.add(id)) return
        if (id in matchingByTemplate) return
        preloadQueue.trySend(id)
    }

    /** On-tap path: needs an immediate fetch, doesn't wait for the queue. */
    private fun loadMatching(id: SmsTemplateId) {
        viewModelScope.launch { loadMatchingSync(id) }
    }

    private suspend fun loadMatchingSync(id: SmsTemplateId) {
        loadingTemplateIds = loadingTemplateIds + id
        try {
            val tpl = templateRepo.findById(id).getOrNull()
            val bodies = if (tpl != null) {
                findMatching(tpl).getOrNull().orEmpty().map { it.body }
            } else {
                emptyList()
            }
            matchingByTemplate = matchingByTemplate + (id to bodies)
            displayCounts = displayCounts + (id to bodies.size)
        } finally {
            loadingTemplateIds = loadingTemplateIds - id
        }
    }

    private fun toggleBlacklist(id: SmsTemplateId) {
        viewModelScope.launch {
            val current = templateRepo.findById(id).getOrNull() ?: return@launch
            if (current.state == TemplateState.BLACKLISTED) {
                blacklist.disable(id)
            } else {
                blacklist.enable(id)
            }
        }
    }

    /**
     * "Scan further back" — moves the period lower bound back and rescans
     * the gap. Wallet-scoped lists touch only that wallet's links; the
     * global list (Home menu) extends every linked wallet plus the legacy
     * global fallback bound. The sync itself runs on the application scope
     * so navigating away can't cancel it; progress lands in [uiState] via
     * the existing scanInbox.progress mirror.
     */
    private fun scanFurtherBack(lowerBoundEpochMillis: Long) {
        viewModelScope.launch {
            try {
                val scopedWallet = activeWalletFilter?.let {
                    runCatching { AccountId(UUID.fromString(it)) }.getOrNull()
                }
                if (scopedWallet != null) {
                    applySyncPeriod(scopedWallet, lowerBoundEpochMillis)
                        .onLeft { Timber.w("TemplateList scanFurtherBack failed: $it") }
                } else {
                    val wallets = senderRepo.findAll().getOrNull().orEmpty()
                        .map { it.accountId }
                        .distinct()
                    for (wallet in wallets) {
                        applySyncPeriod(wallet, lowerBoundEpochMillis)
                            .onLeft { Timber.w("TemplateList scanFurtherBack failed: $it") }
                    }
                    // Fallback bound for links that predate per-link bounds —
                    // only ever moves BACK, like the per-link semantics.
                    val currentGlobal = watermarks.scanLowerBound().getOrNull()
                    if (currentGlobal == null || lowerBoundEpochMillis < currentGlobal) {
                        watermarks.writeLowerBound(lowerBoundEpochMillis)
                    }
                }
                appStartup.triggerManualSync()
            } catch (t: Throwable) {
                Timber.e(t, "TemplateList scanFurtherBack crashed")
            }
        }
    }
}

private fun SmsTemplate.toRow(
    matching: List<String>,
    loading: Boolean,
): TemplateRowViewState = TemplateRowViewState(
    id = id,
    name = name,
    pattern = pattern,
    exampleBody = exampleBody,
    wildcardRolesByPosition = wildcardSlots.associate { it.positionInPattern to it.role },
    state = state,
    matchCount = matchCount,
    matchingMessages = matching.toImmutableList(),
    matchingMessagesLoading = loading,
)
