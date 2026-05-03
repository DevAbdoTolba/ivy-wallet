package com.ivy.data.db.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * 2026-05-02 — Adds a user-supplied `name` to `sms_template`. The name becomes
 * the resulting transaction's title (Tier 1 capture path), giving the user
 * control over how SMS-derived transactions are labelled in the rest of the app.
 *
 * Existing rows get NULL — the next time the user opens the mapping screen they
 * can type a name. Until then, the title falls back to the captured merchant
 * (preserves the previous behavior).
 */
class Migration133to134_TemplateName : Migration(133, 134) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `sms_template` ADD COLUMN `name` TEXT")
    }
}
