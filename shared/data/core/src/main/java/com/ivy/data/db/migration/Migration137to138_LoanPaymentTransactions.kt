package com.ivy.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Each allocation retains the transaction it belongs to, including secondary allocations. */
class Migration137to138_LoanPaymentTransactions : Migration(137, 138) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE loan_records ADD COLUMN transactionId TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_loan_records_transactionId ON loan_records (transactionId)")
        // Only recover relationships actually recorded in v137. Secondary allocations
        // cannot safely be guessed from equal amounts, dates or titles.
        db.execSQL("""
            UPDATE loan_records SET transactionId = (
                SELECT t.id FROM transactions t
                WHERE t.loanRecordId = loan_records.id AND t.isDeleted = 0 LIMIT 1
            )
        """.trimIndent())
    }
}
