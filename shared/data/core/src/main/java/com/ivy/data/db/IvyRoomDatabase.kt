package com.ivy.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.AutoMigrationSpec
import androidx.room.migration.Migration
import com.ivy.data.db.dao.read.*
import com.ivy.data.db.dao.write.*
import com.ivy.data.db.entity.*
import com.ivy.data.db.migration.*
import com.ivy.domain.db.RoomTypeConverters
import com.ivy.domain.db.migration.*

@Database(
    entities = [
        AccountEntity::class, TransactionEntity::class, CategoryEntity::class,
        SettingsEntity::class, PlannedPaymentRuleEntity::class,
        UserEntity::class, ExchangeRateEntity::class, BudgetEntity::class,
        LoanEntity::class, LoanRecordEntity::class, LoanItemEntity::class,
        TagEntity::class, TagAssociationEntity::class,
        SmsTemplateEntity::class, SenderAccountLinkEntity::class, PendingReviewItemEntity::class
    ],
    autoMigrations = [
        AutoMigration(
            from = 121,
            to = 122,
            spec = IvyRoomDatabase.DeleteSEMigration::class
        )
    ],
    version = 134,
    exportSchema = true
)
@TypeConverters(RoomTypeConverters::class)
abstract class IvyRoomDatabase : RoomDatabase() {
    abstract val accountDao: AccountDao
    abstract val transactionDao: TransactionDao
    abstract val categoryDao: CategoryDao
    abstract val budgetDao: BudgetDao
    abstract val plannedPaymentRuleDao: PlannedPaymentRuleDao
    abstract val settingsDao: SettingsDao
    abstract val userDao: UserDao
    abstract val exchangeRatesDao: ExchangeRatesDao
    abstract val loanDao: LoanDao
    abstract val loanRecordDao: LoanRecordDao
    abstract val loanItemDao: LoanItemDao
    abstract val tagDao: TagDao
    abstract val tagAssociationDao: TagAssociationDao

    abstract val writeAccountDao: WriteAccountDao
    abstract val writeTransactionDao: WriteTransactionDao
    abstract val writeCategoryDao: WriteCategoryDao
    abstract val writeBudgetDao: WriteBudgetDao
    abstract val writePlannedPaymentRuleDao: WritePlannedPaymentRuleDao
    abstract val writeSettingsDao: WriteSettingsDao
    abstract val writeExchangeRatesDao: WriteExchangeRatesDao
    abstract val writeLoanDao: WriteLoanDao
    abstract val writeLoanRecordDao: WriteLoanRecordDao
    abstract val writeLoanItemDao: WriteLoanItemDao
    abstract val writeTagDao: WriteTagDao
    abstract val writeTagAssociationDao: WriteTagAssociationDao

    abstract val readSmsTemplateDao: ReadSmsTemplateDao
    abstract val readSenderAccountLinkDao: ReadSenderAccountLinkDao
    abstract val readPendingReviewItemDao: ReadPendingReviewItemDao
    abstract val writeSmsTemplateDao: WriteSmsTemplateDao
    abstract val writeSenderAccountLinkDao: WriteSenderAccountLinkDao
    abstract val writePendingReviewItemDao: WritePendingReviewItemDao

    companion object {
        const val DB_NAME = "ivywallet.db"

        fun migrations(): Array<Migration> = arrayOf(
            Migration105to106_TrnRecurringRules(),
            Migration106to107_Wishlist(),
            Migration107to108_Sync(),
            Migration108to109_Users(),
            Migration109to110_PlannedPayments(),
            Migration110to111_PlannedPaymentRule(),
            Migration111to112_User_testUser(),
            Migration112to113_ExchangeRates(),
            Migration113to114_Multi_Currency(),
            Migration114to115_Category_Account_Icons(),
            Migration115to116_Account_Include_In_Balance(),
            Migration116to117_SalteEdgeIntgration(),
            Migration117to118_Budgets(),
            Migration118to119_Loans(),
            Migration119to120_LoanTransactions(),
            Migration120to121_DropWishlistItem(),
            Migration122to123_ExchangeRates(),
            Migration123to124_LoanIncludeDateTime(),
            Migration124to125_LoanEditDateTime(),
            Migration125to126_Tags(),
            Migration126to127_LoanRecordType(),
            Migration127to128_PaidForDateRecord(),
            Migration128to129_DeleteIsDeleted(),
            Migration129to130_LoanIncludeNote(),
            Migration130to131_LoanChecklist(),
            Migration131to132_SmsExtraction(),
            Migration132to133_SmsRoleRedesign(),
            Migration133to134_TemplateName()
        )

        @Suppress("SpreadOperator")
        fun create(applicationContext: Context): IvyRoomDatabase {
            return Room
                .databaseBuilder(
                    applicationContext,
                    IvyRoomDatabase::class.java,
                    DB_NAME
                )
                .addMigrations(*migrations())
                .build()
        }
    }

    @DeleteColumn(tableName = "accounts", columnName = "seAccountId")
    @DeleteColumn(tableName = "transactions", columnName = "seTransactionId")
    @DeleteColumn(tableName = "transactions", columnName = "seAutoCategoryId")
    @DeleteColumn(tableName = "categories", columnName = "seCategoryName")
    class DeleteSEMigration : AutoMigrationSpec
}
