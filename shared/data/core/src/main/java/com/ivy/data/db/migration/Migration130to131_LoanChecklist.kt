package com.ivy.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration130to131_LoanChecklist : Migration(130, 131) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `loan_items` (`contactId` TEXT NOT NULL, `amount` REAL NOT NULL, `title` TEXT NOT NULL, `isSettled` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `id` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`contactId`) REFERENCES `loans`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_loan_items_contactId` ON `loan_items` (`contactId`)")
        
        // Migrate legacy balances to loan_items
        // Using hex(randomblob(16)) for unique IDs during migration
        db.execSQL("""
            INSERT INTO loan_items (id, contactId, amount, title, isSettled, createdAt)
            SELECT 
                lower(hex(randomblob(16))),
                id, 
                amount, 
                'Legacy Balance', 
                0, 
                strftime('%s', 'now') * 1000 
            FROM loans 
            WHERE amount != 0
        """.trimIndent())
    }
}
