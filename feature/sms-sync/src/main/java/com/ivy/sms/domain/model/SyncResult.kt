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

    /** The sync attempt ended with an error (SyncSmsUseCase Left — e.g. a
     *  failed renormalization pass — or a thrown scan). Published by
     *  [com.ivy.sms.startup.SmsSyncAppStartup] so the UI can stop showing
     *  "Syncing…" and surface [reason] instead of failing silently. */
    data class Failed(val reason: String) : SyncResult
}

enum class SyncTrigger { APP_LAUNCH, MANUAL_MENU, FIRST_SCAN_AFTER_PERMISSION }
