package com.ivy.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration131to132_SmsExtraction : Migration(131, 132) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sms_template` (
                `id`                    TEXT    NOT NULL PRIMARY KEY,
                `pattern`               TEXT    NOT NULL,
                `wildcardSlotsJson`     TEXT    NOT NULL,
                `state`                 TEXT    NOT NULL,
                `classification`        TEXT,
                `senderIdHint`          TEXT    NOT NULL,
                `firstSeenEpochMillis`  INTEGER NOT NULL,
                `lastSeenEpochMillis`   INTEGER NOT NULL,
                `matchCount`            INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )

        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `sender_account_link` (
                `senderId`              TEXT    NOT NULL PRIMARY KEY,
                `accountId`             TEXT    NOT NULL,
                `linkedAtEpochMillis`   INTEGER NOT NULL
            )
            """.trimIndent()
        )

        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_sender_account_link_accountId` ON `sender_account_link` (`accountId`)"
        )

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

        // SMS-source metadata on existing transactions table
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `smsSourceDedupKey` TEXT")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `smsTemplateId` TEXT")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `smsSourceSenderId` TEXT")
        db.execSQL("ALTER TABLE `transactions` ADD COLUMN `smsSourceTimestamp` INTEGER")
    }
}
