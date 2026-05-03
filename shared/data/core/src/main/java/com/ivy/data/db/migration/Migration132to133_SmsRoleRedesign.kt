package com.ivy.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 2026-04-28 SMS extraction redesign:
 *  - sms_template: drops `classification` column; adds `exampleBody`. Existing rows are
 *    discarded (template state is regeneratable from the device inbox on the next scan).
 *  - sender_account_link: adds `historicalLowerBoundEpochMillis` and `watermarkEpochMillis`
 *    so each linked sender owns its own scan window. Replaces the global per-template
 *    watermark / lowerBound. Existing rows are discarded — the user is expected to
 *    re-link senders via the new per-wallet picker.
 *  - pending_review_item: dropped (regenerated on next scan).
 *  - transactions: adds `currentTotal` and `transactionFee` TEXT columns for the new
 *    metadata fields populated by Tier 1 capture.
 *
 * Discarding the SMS-specific rows is safe because (a) the device SMS inbox is the
 * source of truth for messages and (b) Tier 1 transactions already exist as normal
 * transaction rows and survive untouched.
 */
class Migration132to133_SmsRoleRedesign : Migration(132, 133) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // sms_template: drop and recreate without `classification`, with `exampleBody`.
        db.execSQL("DROP TABLE IF EXISTS `sms_template`")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sms_template` (
                `id`                    TEXT    NOT NULL PRIMARY KEY,
                `pattern`               TEXT    NOT NULL,
                `exampleBody`           TEXT    NOT NULL,
                `wildcardSlotsJson`     TEXT    NOT NULL,
                `state`                 TEXT    NOT NULL,
                `senderIdHint`          TEXT    NOT NULL,
                `firstSeenEpochMillis`  INTEGER NOT NULL,
                `lastSeenEpochMillis`   INTEGER NOT NULL,
                `matchCount`            INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        // sender_account_link: drop and recreate with per-sender watermark + lower bound.
        db.execSQL("DROP TABLE IF EXISTS `sender_account_link`")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sender_account_link` (
                `senderId`                          TEXT    NOT NULL PRIMARY KEY,
                `accountId`                         TEXT    NOT NULL,
                `linkedAtEpochMillis`               INTEGER NOT NULL,
                `historicalLowerBoundEpochMillis`   INTEGER,
                `watermarkEpochMillis`              INTEGER
            )
            """.trimIndent()
        )
        // Per FR-016 + 2026-04-28 redesign, a wallet may hold at most one sender:
        // accountId is now UNIQUE in addition to senderId being PK.
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_sender_account_link_accountId` ON `sender_account_link` (`accountId`)"
        )

        // pending_review_item: drop and recreate identically (regenerated on next scan).
        db.execSQL("DROP INDEX IF EXISTS `index_pending_review_item_dedupKey`")
        db.execSQL("DROP INDEX IF EXISTS `index_pending_review_item_templateId`")
        db.execSQL("DROP TABLE IF EXISTS `pending_review_item`")
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `pending_review_item` (
                `id`                       TEXT    NOT NULL PRIMARY KEY,
                `dedupKey`                 TEXT    NOT NULL,
                `senderId`                 TEXT    NOT NULL,
                `body`                     TEXT    NOT NULL,
                `messageEpochMillis`       INTEGER NOT NULL,
                `templateId`               TEXT    NOT NULL,
                `quarantineReason`         TEXT    NOT NULL,
                `enqueuedAtEpochMillis`    INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_pending_review_item_dedupKey` ON `pending_review_item` (`dedupKey`)"
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_pending_review_item_templateId` ON `pending_review_item` (`templateId`)"
        )

        // transactions: add the two new metadata columns (Current Total + Transaction Fee).
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `smsCurrentTotal` TEXT")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `smsTransactionFee` TEXT")
    }
}
