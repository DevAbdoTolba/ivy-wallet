package com.ivy.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 2026-07-05 — Unifies the loan payment ledger (design decision D1:C).
 *
 * `loan_records` gains a nullable `loanItemId` pointing at the checklist entry
 * (`loan_items` row) a payment targets. NULL = loan-level payment (the historical
 * semantics). From this version on, an entry's paid amount and settled state are
 * DERIVED from its records — `loan_items.isSettled` is no longer written by the
 * payment flow.
 *
 * Backfill: one synthetic DECREASE record per already-settled entry that has no
 * entry-targeted record yet. Loan "paid" totals become records-only after this
 * feature (removing the old settled-items + records hybrid double-count), so
 * without the backfill every previously settled entry's amount would silently
 * vanish from the paid total on update. Synthetic records carry no wallet and
 * no transaction (D6 forbids retroactive transactions — they'd corrupt
 * balances); having no matching transactions.loanRecordId row is exactly what
 * renders the "settled — no transaction" legacy badge.
 *
 * Idempotent via the NOT EXISTS guard. UUIDs are generated in dashed 8-4-4-4-12
 * form directly — the 130→131 dashless-randomblob crash (repaired in 134→135)
 * is not repeated here.
 */
class Migration136to137_LoanRecordItemLink : Migration(136, 137) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `loan_records` ADD COLUMN `loanItemId` TEXT")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_loan_records_loanItemId` " +
                "ON `loan_records` (`loanItemId`)"
        )

        db.execSQL(
            """
            INSERT INTO loan_records (
                id, loanId, loanItemId, amount, note, dateTime,
                interest, accountId, convertedAmount, loanRecordType,
                isSynced, isDeleted
            )
            SELECT
                substr(u,1,8)||'-'||substr(u,9,4)||'-'||substr(u,13,4)||'-'||substr(u,17,4)||'-'||substr(u,21,12),
                contactId,
                itemId,
                amount,
                'Legacy settled entry',
                createdAt,
                0,
                NULL,
                NULL,
                'DECREASE',
                1, 0
            FROM (
                SELECT
                    li.contactId AS contactId,
                    li.id AS itemId,
                    li.amount AS amount,
                    li.createdAt AS createdAt,
                    lower(hex(randomblob(16))) AS u
                FROM loan_items li
                WHERE li.isSettled = 1
                  AND NOT EXISTS (
                      SELECT 1 FROM loan_records lr WHERE lr.loanItemId = li.id
                  )
            )
            """.trimIndent()
        )
    }
}
