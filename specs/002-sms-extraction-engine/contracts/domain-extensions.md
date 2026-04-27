# Contract — Domain entity extensions

**Feature**: 002-sms-extraction-engine
**Modified file**: `shared/data/model/src/main/kotlin/com/ivy/data/model/Transaction.kt`
**Spec source**: FR-013, FR-015, FR-030, FR-031

---

## 1. `TransactionMetadata` — additive fields

The existing `TransactionMetadata` value class gains two optional fields. Existing callers are unaffected (defaults are `null`).

```
data class TransactionMetadata(
    val recurringRuleId: RecurringRuleId? = null,
    val paidForDateTime: Instant? = null,
    val loanId: LoanId? = null,
    val loanRecordId: LoanRecordId? = null,

    // NEW:
    val smsSourceDedupKey: String? = null,       // hash, NOT raw body
    val smsTemplateId: SmsTemplateId? = null,    // typed UUID wrapper from feature:sms-sync
)
```

### Constraints

- Both new fields MUST be either both `null` (manually entered transaction) or both non-null (SMS-originated transaction). Mixing is invalid; enforced at construction time in `CreateTransactionFromSmsUseCase`.
- `smsSourceDedupKey` is the same composite hash used for inbox dedup (`address + ":" + date + ":" + body-sha256`). It is NOT reversible to raw SMS content.
- `SmsTemplateId` lives in `feature:sms-sync/.../domain/model`. To avoid `shared:data:model` depending on `feature:sms-sync` (which would invert the architectural direction), the field type on the metadata is the underlying `UUID?` and a typed accessor `metadata.smsTemplateIdTyped: SmsTemplateId?` is provided as an extension function in `feature:sms-sync`.

### Why a hash instead of a foreign key

- Per FR-034, raw SMS bodies are not retained anywhere outside the device inbox. There is no `sms_message` table.
- Auditability (FR-030: "tapping an auto-created transaction MUST allow the user to view the source SMS") works by looking up the matching SMS in the device inbox using the dedup key components stored alongside the hash (`metadata.smsSourceSenderId` is stored implicitly via the linked Account — the wallet's sender list narrows the candidates, then the dedup hash uniquely identifies one SMS).

Actually, to make the audit lookup robust without storing the body, two more readable fields are added alongside the hash:

```
val smsSourceSenderId: String?     = null,     // for fast inbox lookup
val smsSourceTimestamp: Instant?   = null,     // narrows the inbox query
```

Combined with the hash, a one-shot inbox lookup reliably finds the originating SMS at view-time. If the user has since deleted that SMS from the system inbox, the audit view shows "Original SMS no longer available in the device inbox" but the transaction itself is unaffected.

### Final additive set

| Field | Type | Required when |
|-------|------|---------------|
| `smsSourceDedupKey`   | `String?` | SMS-sourced |
| `smsTemplateId`       | `UUID?`   | SMS-sourced |
| `smsSourceSenderId`   | `String?` | SMS-sourced |
| `smsSourceTimestamp`  | `Instant?`| SMS-sourced |

All four are `null` for manually-entered transactions.

---

## 2. `Account` — sender ID set (read-only view)

Per FR-013, an Account "MUST be extendable to hold a set of linked SMS sender IDs." We model this as a *derived* read accessor on the Account domain class rather than a stored field on `AccountEntity`, because the authoritative storage is the `sender_account_link` table (per data-model.md).

The Account domain class itself remains structurally unchanged. A new repository method on the existing `AccountRepository`:

```
suspend fun findLinkedSenderIds(accountId: AccountId): Either<String, Set<String>>
```

…and a new repository for the link table:

```
interface SenderAccountLinkRepository {
    suspend fun findAll(): Either<String, List<SenderAccountLink>>
    suspend fun findBySenderId(senderId: String): Either<String, SenderAccountLink?>
    suspend fun findByAccountId(accountId: AccountId): Either<String, List<SenderAccountLink>>

    /** Returns Left("LINK_CONFLICT: sender already linked to wallet '<name>'")
     *  if the sender is already in another link. UI surfaces this directly. */
    suspend fun upsert(link: SenderAccountLink): Either<String, Unit>

    suspend fun delete(senderId: String): Either<String, Unit>
}
```

This keeps `Account` as a pure value object and avoids a transitive dependency from `shared:data:model` into either `feature:sms-sync` or `shared:data:core`.

---

## 3. Currency inheritance (FR-015)

No new field needed. `CreateTransactionFromSmsUseCase` constructs the new Transaction with `account.asset` (the existing currency field) — no SMS-body currency parsing path exists. Currency-mismatch detection is a pre-write *validation* step that compares regex-extracted SMS currency tokens against `account.asset.code`; on mismatch, the message is routed to quarantine with `QuarantineReason.CURRENCY_MISMATCH` instead of becoming a transaction.

This validation lives in `RouteSmsUseCase` so the policy is centralized and unit-testable in isolation from the DB.

---

## 4. Backward compatibility

- Old `Transaction` JSON in existing backups loads cleanly (all new fields default to `null`).
- New `Transaction` JSON loaded by an old client (e.g., a user on an older Ivy Wallet version restoring a new backup) discards the new fields via `ignoreUnknownKeys`, leaving the transaction marked as manually-entered — degrades gracefully.
- No DB migration is required for these metadata fields because `TransactionMetadata` is already serialized as JSON inside a single `transaction.metadata` column.
