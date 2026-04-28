package com.ivy.sms.ui.templates

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewModelScope
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.domain.model.SmsTemplate
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.model.TemplateState
import com.ivy.sms.domain.usecase.BlacklistTemplateUseCase
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
) : ComposeViewModel<TemplateListViewState, TemplateListEvent>() {

    private var onTemplateOpen: ((SmsTemplateId) -> Unit)? = null
    private var onScanFurther: (() -> Unit)? = null
    private var onPending: (() -> Unit)? = null

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

        return TemplateListViewState(
            templates = templates.value.map { it.toRow() }.toImmutableList(),
            scanProgress = progress.value,
            pendingReviewCount = pendingCount.value,
        )
    }

    override fun onEvent(event: TemplateListEvent) {
        when (event) {
            is TemplateListEvent.TemplateClicked -> onTemplateOpen?.invoke(event.id)
            TemplateListEvent.ScanFurtherBack -> onScanFurther?.invoke()
            TemplateListEvent.OpenPendingReview -> onPending?.invoke()
            is TemplateListEvent.ToggleBlacklist -> toggleBlacklist(event.id)
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

private fun SmsTemplate.toRow(): TemplateRowViewState = TemplateRowViewState(
    id = id,
    preview = pattern.take(120),
    state = state,
    matchCount = matchCount,
)
