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
    private var expandedTemplateIds by mutableStateOf<Set<SmsTemplateId>>(emptySet())
    private var matchingByTemplate by mutableStateOf<Map<SmsTemplateId, List<String>>>(emptyMap())
    private var loadingTemplateIds by mutableStateOf<Set<SmsTemplateId>>(emptySet())

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
            expandedTemplateIds = expandedTemplateIds,
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
            is TemplateListEvent.ToggleTemplateExpanded -> toggleTemplate(event.id)
        }
    }

    private fun toggleTemplate(id: SmsTemplateId) {
        if (id in expandedTemplateIds) {
            expandedTemplateIds = expandedTemplateIds - id
            return
        }
        expandedTemplateIds = expandedTemplateIds + id
        if (matchingByTemplate.containsKey(id)) return
        loadingTemplateIds = loadingTemplateIds + id
        viewModelScope.launch {
            val tpl = templateRepo.findById(id).getOrNull()
            val bodies = if (tpl != null) {
                findMatching(tpl).getOrNull().orEmpty().map { it.body }
            } else {
                emptyList()
            }
            matchingByTemplate = matchingByTemplate + (id to bodies)
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
