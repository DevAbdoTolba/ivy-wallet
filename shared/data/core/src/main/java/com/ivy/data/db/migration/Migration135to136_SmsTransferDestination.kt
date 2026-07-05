package com.ivy.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 2026-07-05 — Adds an optional default transfer destination to `sms_template`.
 *
 * Transfer-role templates previously had nowhere to store WHERE the money goes,
 * so routing fabricated `toAccount = fromAccount` and the read mapper silently
 * dropped the row — an invisible "ghost" transfer (see
 * RepairGhostTransfersUseCase for the one-shot cleanup of those rows).
 *
 * NULL means "ask each time": a matching transfer message quarantines with
 * TRANSFER_NEEDS_DESTINATION and the review card collects the destination.
 * All existing rows get NULL, which deliberately flips existing transfer
 * templates from ghost-writing to the review path.
 */
class Migration135to136_SmsTransferDestination : Migration(135, 136) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `sms_template` ADD COLUMN `transferToAccountId` TEXT")
    }
}
