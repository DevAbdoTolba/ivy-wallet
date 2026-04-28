package com.ivy.data.db.migration

import android.database.sqlite.SQLiteConstraintException
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.ivy.data.db.IvyRoomDatabase
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class Migration131to132Test {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        IvyRoomDatabase::class.java,
        listOf(IvyRoomDatabase.DeleteSEMigration()),
        FrameworkSQLiteOpenHelperFactory(),
    )

    private val migration = Migration131to132_SmsExtraction()

    @Test
    fun createsThreeTables() {
        helper.createDatabase(TestDb, 131).close()
        val newDb = helper.runMigrationsAndValidate(TestDb, 132, true, migration)

        // smsTemplate table exists
        newDb.query("SELECT * FROM sms_template").use { c ->
            c.columnNames.toSet().contains("pattern") shouldBe true
            c.columnNames.toSet().contains("wildcardSlotsJson") shouldBe true
            c.columnNames.toSet().contains("state") shouldBe true
        }

        // sender_account_link table exists
        newDb.query("SELECT * FROM sender_account_link").use { c ->
            c.columnNames.toSet().contains("senderId") shouldBe true
            c.columnNames.toSet().contains("accountId") shouldBe true
        }

        // pending_review_item table exists with required columns
        newDb.query("SELECT * FROM pending_review_item").use { c ->
            c.columnNames.toSet().contains("dedupKey") shouldBe true
            c.columnNames.toSet().contains("templateId") shouldBe true
            c.columnNames.toSet().contains("quarantineReason") shouldBe true
        }

        newDb.close()
    }

    @Test
    fun senderIdPk_rejectsDuplicateInserts() {
        helper.createDatabase(TestDb, 131).close()
        val newDb = helper.runMigrationsAndValidate(TestDb, 132, true, migration)

        val insert =
            "INSERT INTO sender_account_link (senderId, accountId, linkedAtEpochMillis) VALUES (?, ?, ?)"

        newDb.compileStatement(insert).run {
            bindString(1, "ChaseAlerts")
            bindString(2, UUID.randomUUID().toString())
            bindLong(3, 1_000L)
            executeInsert()
        }

        val threwOnDuplicate = try {
            newDb.compileStatement(insert).run {
                bindString(1, "ChaseAlerts")
                bindString(2, UUID.randomUUID().toString())
                bindLong(3, 2_000L)
                executeInsert()
            }
            false
        } catch (e: SQLiteConstraintException) {
            true
        }

        threwOnDuplicate shouldBe true
        newDb.close()
    }

    companion object {
        private const val TestDb = "migration-test-131-132"
    }
}
