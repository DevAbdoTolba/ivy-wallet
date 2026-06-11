package com.ivy.data.db.migration

import androidx.sqlite.db.SupportSQLiteDatabase
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import org.junit.Test

class LoanItemMigrationsTest {

    private fun captureSql(migrate: (SupportSQLiteDatabase) -> Unit): List<String> {
        val statements = mutableListOf<String>()
        val db = mockk<SupportSQLiteDatabase>()
        every { db.execSQL(capture(statements)) } just runs
        migrate(db)
        return statements
    }

    @Test
    fun `migration 130 to 131 - seeds loan items with dashed uuids`() {
        // when
        val statements = captureSql { Migration130to131_LoanChecklist().migrate(it) }

        // then: the seeded id must be in the dashed 8-4-4-4-12 form,
        // otherwise UUID-fromString throws when Room reads the row back
        val insert = statements.first { "INSERT INTO loan_items" in it }
        insert shouldContain
            "substr(u,1,8)||'-'||substr(u,9,4)||'-'||substr(u,13,4)||'-'||substr(u,17,4)||'-'||substr(u,21,12)"
        insert shouldContain "lower(hex(randomblob(16))) AS u"
        insert shouldContain "WHERE amount != 0"
    }

    @Test
    fun `migration 134 to 135 - repairs dash-less loan item ids`() {
        // when
        val statements = captureSql { Migration134to135_RepairLoanItemIds().migrate(it) }

        // then: only already-corrupted rows are touched
        val update = statements.single()
        update shouldContain "UPDATE loan_items"
        update shouldContain
            "SET id = substr(id,1,8)||'-'||substr(id,9,4)||'-'||substr(id,13,4)||'-'||substr(id,17,4)||'-'||substr(id,21,12)"
        update shouldContain "WHERE length(id) = 32 AND instr(id, '-') = 0"
    }

    @Test
    fun `migration 134 to 135 - migrates from version 134 to 135`() {
        // given
        val migration = Migration134to135_RepairLoanItemIds()

        // then
        migration.startVersion shouldBe 134
        migration.endVersion shouldBe 135
    }
}
