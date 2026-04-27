# Contract — Backup / Restore extension

**Feature**: 002-sms-extraction-engine
**Modified file**: `shared/data/core/src/main/java/com/ivy/data/backup/BackupDataUseCase.kt`
**Spec source**: FR-033, FR-034 (and Q4 clarification)

---

## 1. JSON shape changes to `IvyWalletCompleteData`

Two new optional top-level keys are added to the existing `IvyWalletCompleteData` data class:

```
@Serializable
data class IvyWalletCompleteData(
    // ... existing fields unchanged: accounts, transactions, categories,
    //     budgets, loans, loanRecords, plannedPaymentRules, settings,
    //     sharedPrefs, tags, tagAssociations ...

    // NEW (additive):
    val smsTemplates: List<SmsTemplateBackupDto>? = null,
    val senderAccountLinks: List<SenderAccountLinkBackupDto>? = null,
    val smsWatermarkEpochMillis: Long? = null,        // serialized DataStore value
    val smsScanLowerBoundEpochMillis: Long? = null,   // serialized DataStore value
)
```

Both new lists default to `null`, NOT empty list — this lets the decoder distinguish "old backup with no SMS data" from "new backup with this user explicitly having no templates". Old clients reading new backups ignore the unknown keys (project Json is configured with `ignoreUnknownKeys = true`).

### 1.1 `SmsTemplateBackupDto`

```
@Serializable
data class SmsTemplateBackupDto(
    val id: String,                          // UUID
    val pattern: String,
    val wildcardSlots: List<WildcardSlotBackupDto>,
    val state: String,                       // enum name
    val classification: String?,             // enum name or null
    val senderIdHint: String,
    val firstSeenEpochMillis: Long,
    val lastSeenEpochMillis: Long,
    val matchCount: Int,
)

@Serializable
data class WildcardSlotBackupDto(
    val id: String,
    val positionInPattern: Int,
    val contextSnippet: String,
    val mapping: String,                     // "Unmapped" | "Amount" | "Merchant" | "DateTime" | "Reference" | "Ignored"
)
```

### 1.2 `SenderAccountLinkBackupDto`

```
@Serializable
data class SenderAccountLinkBackupDto(
    val senderId: String,
    val accountId: String,                   // UUID
    val linkedAtEpochMillis: Long,
)
```

---

## 2. What is explicitly NOT in the backup

Per FR-034 (Q4 clarification):

- ❌ Raw `pending_review_item` rows
- ❌ The dedup key cache
- ❌ Raw SMS body text anywhere — including inside auto-created Transactions (the `metadata.smsSourceDedupKey` field is preserved as it is just a hash; it does not include body content)

After restore, on the next launch:
1. The reconciliation scan re-derives `pending_review_item` rows from the device's current SMS inbox by re-running the routing pipeline against any messages newer than the restored watermark.
2. Existing auto-created Transactions are recognized via their `metadata.smsSourceDedupKey`, so the scan does not re-create them.

---

## 3. Encoder changes (`generateJsonBackup()`)

Add three new gathering steps, each behind a `withContext(dispatchers.io)` boundary:

```
val smsTemplates    = readSmsTemplateDao.findAll().map { it.toBackupDto() }
val senderLinks     = readSenderAccountLinkDao.findAll().map { it.toBackupDto() }
val watermark       = smsWatermarkPreferences.read().getOrNull()
val scanLowerBound  = smsWatermarkPreferences.scanLowerBound().getOrNull()
```

These slot into the existing `IvyWalletCompleteData(...)` constructor at the bottom. No restructuring of the encoder is required.

---

## 4. Decoder changes (`importBackupFile(...)`)

Conditional restoration only when the keys are present:

```
backup.smsTemplates?.let { templates ->
    writeSmsTemplateDao.replaceAll(templates.map { it.toEntity() })
}
backup.senderAccountLinks?.let { links ->
    writeSenderAccountLinkDao.replaceAll(links.map { it.toEntity() })
}
backup.smsWatermarkEpochMillis?.let { smsWatermarkPreferences.write(it) }
backup.smsScanLowerBoundEpochMillis?.let { smsWatermarkPreferences.writeLowerBound(it) }
```

The `replaceAll` semantic (delete + insert in one transaction) is consistent with how the existing decoder handles other entity types.

**Idempotency**: importing the same backup twice produces the same DB state. Importing a backup over a non-empty pre-existing SMS configuration overwrites it (this matches the existing behavior for accounts, transactions, etc.).

---

## 5. Format versioning

No `formatVersion` field is introduced in this change. Both new keys are nullable and ignored when absent, satisfying:
- Old client + old backup: works (ignores new code path entirely)
- Old client + new backup: works (`ignoreUnknownKeys = true` discards the SMS keys)
- New client + old backup: works (null → no-op restore)
- New client + new backup: works (full round-trip)

If a future SMS-related backup change is *breaking*, that is when `formatVersion: Int` is added at the top level.
