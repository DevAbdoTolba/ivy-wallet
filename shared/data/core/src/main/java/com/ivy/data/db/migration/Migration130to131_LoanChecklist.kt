package com.ivy.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration130to131_LoanChecklist : Migration(130, 131) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `loan_items` (`contactId` TEXT NOT NULL, `amount` REAL NOT NULL, `title` TEXT NOT NULL, `isSettled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `id` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`contactId`) REFERENCES `loans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_items_contactId` ON `loan_items` (`contactId`)")
        
        // Migrate legacy balances to loan_items.
        // The random id must be in the dashed 8-4-4-4-12 UUID form, otherwise
        // UUID.fromString throws when Room reads the row back.
        db.execSQL("""
            INSERT INTO loan_items (id, contactId, amount, title, isSettled, createdAt)
            SELECT
                substr(u,1,8)||'-'||substr(u,9,4)||'-'||substr(u,13,4)||'-'||substr(u,17,4)||'-'||substr(u,21,12),
                id,
                amount,
                'Legacy Balance',
                0,
                strftime('%s', 'now') * 1000
            FROM (SELECT id, amount, lower(hex(randomblob(16))) AS u FROM loans WHERE amount != 0)
        """.trimIndent())
    }
}
