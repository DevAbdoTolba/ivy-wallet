package com.ivy.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration134to135_RepairLoanItemIds : Migration(134, 135) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // The original Migration130to131 seeded loan_items.id with
        // lower(hex(randomblob(16))) — 32 hex chars without dashes — which
        // UUID.fromString can't parse. Re-insert the dashes for those rows.
        // loan_items.id has no child foreign keys, so rewriting the PK is safe.
        db.execSQL("""
            UPDATE loan_items
            SET id = substr(id,1,8)||'-'||substr(id,9,4)||'-'||substr(id,13,4)||'-'||substr(id,17,4)||'-'||substr(id,21,12)
            WHERE length(id) = 32 AND instr(id, '-') = 0
        """.trimIndent())
    }
}
