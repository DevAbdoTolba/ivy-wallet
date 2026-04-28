package com.ivy.sms.ui.pending

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import com.ivy.sms.data.PendingReviewItemRepository
import com.ivy.sms.data.SmsTemplateRepository
import com.ivy.sms.domain.model.PendingReviewItemId
import com.ivy.sms.domain.model.SmsTemplateId
import com.ivy.sms.domain.usecase.BlacklistTemplateUseCase
import com.ivy.sms.domain.usecase.ResolvePendingItemUseCase
import com.ivy.ui.ComposeViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.launch
import javax.inject.Inject

@Stable
@HiltViewModel
class PendingReviewViewModel @Inject constructor(
    private val pendingRepo: PendingReviewItemRepository,
    private val templateRepo: SmsTemplateRepository,
    private val resolve: ResolvePendingItemUseCase,
    private val blacklist: BlacklistTemplateUseCase,
) : ComposeViewModel<PendingReviewViewState, PendingReviewEvent>() {

    private val expanded = mutableStateOf<Set<String>>(emptySet())

    @Composable
    override fun uiState(): PendingReviewViewState {
        val items = pendingRepo.observeAllRaw().collectAsState(initial = emptyList())
        val templates = templateRepo.observeAll().collectAsState(initial = emptyList())
        val templateById = templates.value.associateBy { it.id.value.toString() }
        val rows = items.value.mapNotNull { e ->
            val tpl = templateById[e.templateId] ?: return@mapNotNull null
            PendingItemRowViewState(
                id = e.id,
                itemId = PendingReviewItemId(java.util.UUID.fromString(e.id)),
                templateId = tpl.id,
                senderId = e.senderId,
                body = e.body,
                templatePattern = tpl.pattern,
                timestamp = e.messageEpochMillis,
                reason = e.quarantineReason,
                expanded = e.id in expanded.value,
            )
        }.toImmutableList()
        return PendingReviewViewState(items = rows.ifEmpty { persistentListOf() })
    }

    override fun onEvent(event: PendingReviewEvent) {
        when (event) {
            is PendingReviewEvent.Dismiss -> {
                viewModelScope.launch { resolve.dismiss(event.itemId) }
            }
            is PendingReviewEvent.MapTemplate -> { /* nav handled by Screen */ }
            is PendingReviewEvent.Blacklist -> {
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
