package com.ivy.sms.ui.templates

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.usecase.BlacklistTemplateUseCase
import com.ivy.sms.domain.usecase.FindMatchingMessagesUseCase
import com.ivy.sms.domain.usecase.ScanInboxUseCase
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import javax.inject.Inject

@Stable
@HiltViewModel
class TemplateListViewModel @Inject constructor(
    private val templateRepo: SmsTemplateRepository,
    private val pendingRepo: PendingReviewItemRepository,
    private val scanInbox: ScanInboxUseCase,
    private val blacklist: BlacklistTemplateUseCase,
    private val findMatching: FindMatchingMessagesUseCase,
) : ComposeViewModel<TemplateListViewState, TemplateListEvent>() {

    private var onTemplateOpen: ((SmsTemplateId) -> Unit)? = null
    private var onScanFurther: (() -> Unit)? = null
    private var onPending: (() -> Unit)? = null

    private var expandedGroup by mutableStateOf<TemplateGroupKey?>(TemplateGroupKey.Expense)
    private var matchingByTemplate by mutableStateOf<Map<SmsTemplateId, List<String>>>(emptyMap())
    private var loadingTemplateIds by mutableStateOf<Set<SmsTemplateId>>(emptySet())
    private var displayCounts by mutableStateOf<Map<SmsTemplateId, Int>>(emptyMap())
    private var matchingModalTemplate by mutableStateOf<SmsTemplateId?>(null)
    private var matchingModalLimit by mutableStateOf(MATCHING_MODAL_PAGE_SIZE)
    private val preloadedIds = mutableSetOf<SmsTemplateId>()

    fun setNavigators(
        onTemplate: (SmsTemplateId) -> Unit,
        onScanFurther: () -> Unit,
        onPending: () -> Unit,
    ) {
        onTemplateOpen = onTemplate
        this.onScanFurther = onScanFurther
        this.onPending = onPending
    }

    @Composable
    override fun uiState(): TemplateListViewState {
        val templates = remember(templateRepo) { templateRepo.observeAll() }
            .collectAsState(initial = emptyList())
        val pendingCount = remember(pendingRepo) { pendingRepo.observeCount() }
            .collectAsState(initial = 0)
        val progress = scanInbox.progress.collectAsState(initial = null)

        val rows = templates.value.map { tpl ->
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
            TemplateListEvent.ScanFurtherBack -> onScanFurther?.invoke()
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
     * Fire-and-forget background load that lets each row show the accurate
     * Jaccard match count next to "Show messages" without the user needing
     * to tap. Skips ids we've already kicked off so repeated row recompositions
     * don't pile up duplicate launches.
     */
    private fun preloadMatching(id: SmsTemplateId) {
        if (!preloadedIds.add(id)) return
        if (id in matchingByTemplate) return
        loadMatching(id)
    }

    private fun loadMatching(id: SmsTemplateId) {
        loadingTemplateIds = loadingTemplateIds + id
        viewModelScope.launch {
            val tpl = templateRepo.findById(id).getOrNull()
            val bodies = if (tpl != null) {
                findMatching(tpl).getOrNull().orEmpty().map { it.body }
            } else {
                emptyList()
            }
            matchingByTemplate = matchingByTemplate + (id to bodies)
            displayCounts = displayCounts + (id to bodies.size)
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
