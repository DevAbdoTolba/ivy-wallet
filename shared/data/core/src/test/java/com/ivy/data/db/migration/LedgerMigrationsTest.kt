package com.ivy.data.db.migration

import androidx.sqlite.db.SupportSQLiteDatabase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.Test

/**
 * SQL-contract tests for the 003-loan-payment-ledger migrations (same
 * capture harness as [LoanItemMigrationsTest]).
 */
class LedgerMigrationsTest {

    private fun captureSql(migrate: (SupportSQLiteDatabase) -> Unit): List<String> {
        val statements = mutableListOf<String>()
        val db = mockk<SupportSQLiteDatabase>()
        every { db.execSQL(capture(statements)) } just runs
        migrate(db)
        return statements
    }

    // ── 135 → 136: sms_template.transferToAccountId ─────────────────────────

    @Test
    fun `migration 135 to 136 - adds nullable transfer destination column`() {
        val statements = captureSql { Migration135to136_SmsTransferDestination().migrate(it) }

        val alter = statements.single()
        alter shouldContain "ALTER TABLE `sms_template` ADD COLUMN `transferToAccountId` TEXT"
        // Nullable on purpose: NULL = ask-per-message (review path). A NOT
        // NULL DEFAULT would silently invent destinations for existing rows.
        alter shouldNotContain "NOT NULL"
        alter shouldNotContain "DEFAULT"
    }

    @Test
    fun `migration 135 to 136 - migrates from version 135 to 136`() {
        val migration = Migration135to136_SmsTransferDestination()
        migration.startVersion shouldBe 135
        migration.endVersion shouldBe 136
    }

    // ── 136 → 137: loan_records.loanItemId + legacy backfill ────────────────

    @Test
    fun `migration 136 to 137 - adds indexed nullable loanItemId column`() {
        val statements = captureSql { Migration136to137_LoanRecordItemLink().migrate(it) }

        statements.first { "ALTER TABLE" in it } shouldContain
            "ALTER TABLE `loan_records` ADD COLUMN `loanItemId` TEXT"
        // Index name must match Room's generated name for the @Entity index —
        // a mismatch fails schema validation on first open.
        statements.first { "CREATE INDEX" in it } shouldContain
            "`index_loan_records_loanItemId` ON `loan_records` (`loanItemId`)"
    }

    @Test
    fun `migration 136 to 137 - backfills settled entries with dashed-uuid synthetic records`() {
        val statements = captureSql { Migration136to137_LoanRecordItemLink().migrate(it) }

        val insert = statements.first { "INSERT INTO loan_records" in it }
        // Dashed 8-4-4-4-12 UUID form — the 130→131 dashless-randomblob crash
        // (repaired in 134→135) must never be repeated.
        insert shouldContain
            "substr(u,1,8)||'-'||substr(u,9,4)||'-'||substr(u,13,4)||'-'||substr(u,17,4)||'-'||substr(u,21,12)"
        insert shouldContain "lower(hex(randomblob(16))) AS u"
        // Only settled entries; idempotent re-run inserts nothing.
        insert shouldContain "isSettled = 1"
        insert shouldContain "NOT EXISTS"
        insert shouldContain "lr.loanItemId = li.id"
        // Synthetic records are DECREASE, non-interest, wallet-less (no
        // transactions may be fabricated — design decision D6).
        insert shouldContain "'DECREASE'"
        insert shouldContain "'Legacy settled entry'"
    }

    @Test
    fun `migration 136 to 137 - migrates from version 136 to 137`() {
        val migration = Migration136to137_LoanRecordItemLink()
        migration.startVersion shouldBe 136
        migration.endVersion shouldBe 137
    }
}
