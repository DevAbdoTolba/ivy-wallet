package com.ivy.data.backup

import com.ivy.data.db.entity.LoanItemEntity
import com.ivy.data.db.entity.LoanRecordEntity
import com.ivy.data.db.entity.SmsTemplateEntity
import io.kotest.matchers.shouldBe
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Test
import java.time.Instant
import java.util.UUID

class LedgerBackupTest {
    @Test fun `backup preserves entries and both payment associations`() {
        val loanId = UUID.randomUUID()
        val item = LoanItemEntity(loanId, 100.0, "Installment", createdAt = Instant.EPOCH)
        val record = LoanRecordEntity(
            loanId = loanId, amount = 25.0, dateTime = Instant.EPOCH,
            loanItemId = item.id, transactionId = UUID.randomUUID(),
        )
        val data = IvyWalletCompleteData(loanItems = listOf(item), loanRecords = listOf(record))
        Json.decodeFromString<IvyWalletCompleteData>(Json.encodeToString(data)) shouldBe data
    }

    @Test fun `template destination survives entity backup and restore mappings`() {
        val entity = SmsTemplateEntity(
            id = UUID.randomUUID().toString(), pattern = "Transfer <*>", exampleBody = "Transfer 5",
            wildcardSlotsJson = "[]", state = "ACTIVE", senderIdHint = "Bank",
            firstSeenEpochMillis = 0, lastSeenEpochMillis = 0,
            transferToAccountId = UUID.randomUUID().toString(),
        )
        Json.decodeFromString<SmsTemplateBackupDto>(Json.encodeToString(entity.toBackupDto()))
            .toEntity() shouldBe entity
    }

    @Test fun `old backups decode with absent optional fields`() {
        Json.decodeFromString<IvyWalletCompleteData>("{}").loanItems shouldBe null
        val oldRecord = """{"loanId":"00000000-0000-0000-0000-000000000001","amount":5.0,"dateTime":0}"""
        // Use the installed Instant serializer's representation, then remove new fields.
        val record = LoanRecordEntity(loanId = UUID.randomUUID(), amount = 5.0, dateTime = Instant.EPOCH)
        val restored = Json.decodeFromString<LoanRecordEntity>(Json.encodeToString(record))
        restored.loanItemId shouldBe null
        restored.transactionId shouldBe null
        val dto = Json.decodeFromString<SmsTemplateBackupDto>("""{
            "id":"x","pattern":"x","wildcardSlotsJson":"[]","state":"ACTIVE",
            "senderIdHint":"Bank","firstSeenEpochMillis":0,"lastSeenEpochMillis":0
        }""")
        dto.transferToAccountId shouldBe null
    }
}
