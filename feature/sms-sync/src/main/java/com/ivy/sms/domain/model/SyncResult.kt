package com.ivy.sms.domain.model

sealed interface SyncResult {
    data object PermissionMissing : SyncResult

    data class Completed(
        val newMessagesProcessed: Int,
        val transactionsCreated: Int,
        val itemsQuarantined: Int,
        val durationMillis: Long,
    ) : SyncResult

    data class PartiallyCompleted(
        val newMessagesProcessed: Int,
        val transactionsCreated: Int,
        val itemsQuarantined: Int,
    ) : SyncResult
}

enum class SyncTrigger { APP_LAUNCH, MANUAL_MENU, FIRST_SCAN_AFTER_PERMISSION }
