# Phase 1 Data Model — SMS Extraction Engine

**Feature**: 002-sms-extraction-engine
**Date**: 2026-04-27
**DB version**: 131 → **132**

This document defines all new persisted state, the migration that introduces it, and the in-memory domain types that wrap it. Three new Room entities, one new index, one new field on the existing `TransactionMetadata` value class, and one new DataStore key.

---

## 1. New Room entities

### 1.1 `sms_template`

Represents an algorithmically-discovered SMS shape after Drain clustering, plus any user-applied configuration.

| Column | Type | Notes |
|--------|------|-------|
| `id`                    | TEXT (UUID) | PK |
| `pattern`               | TEXT        | The clustered template body, with `<*>` placeholders inline (e.g. `"Purchase of <*> at <*> on <*>. Ref: <*>"`) |
| `wildcardSlotsJson`     | TEXT        | Serialized `List<WildcardSlot>` describing each wildcard's position, surrounding context snippet, and current mapping (one of `Amount`, `Merchant`, `DateTime`, `Reference`, `Ignored`, or `Unmapped`) |
| `state`                 | TEXT        | One of `UNMAPPED`, `ACTIVE`, `BLACKLISTED`, `PENDING_REVIEW` (an enum encoded as the string name) |
| `classification`        | TEXT        | Nullable — one of `INCOME`, `EXPENSE`, `TRANSFER`. NULL until the user classifies the template. |
| `senderIdHint`          | TEXT        | The sender ID associated with the messages that produced this template (denormalized; the *authoritative* sender→wallet link lives in `sender_account_link`) |
| `firstSeenEpochMillis`  | INTEGER     | When this template was first observed in the inbox |
| `lastSeenEpochMillis`   | INTEGER     | Updated each time a message matches this template |
| `matchCount`            | INTEGER     | Total messages that have ever matched this template (informational) |

**State machine** (governs the `state` column):

```
            ┌──────────────────────────┐
            │       UNMAPPED           │     (initial state on first discovery)
            └─────┬──────────────┬─────┘
                  │              │
   user maps ≥1   │              │  user toggles
   wildcard +     │              │  Blacklist on the
   classifies     │              │  template
                  ▼              ▼
            ┌──────────┐   ┌──────────────┐
            │  ACTIVE  │   │ BLACKLISTED  │
            └────┬─────┘   └──────┬───────┘
                 │                │
                 │  user toggles  │
                 │  Blacklist on  │
                 ▼                ▼
            ┌──────────────────────────┐
            │       BLACKLISTED        │
            └──────────────────────────┘
```

`PENDING_REVIEW` is a *transient* state for templates that have queued items but have not been mapped yet; it is set on insert when at least one message is routed to quarantine and cleared (back to `UNMAPPED`) when the queue empties.

### 1.2 `sender_account_link`

The authoritative wallet ↔ sender mapping. **`senderId` carries a UNIQUE index** so the database itself enforces FR-016 (1 sender → at most 1 wallet).

| Column | Type | Notes |
|--------|------|-------|
| `senderId`     | TEXT (PK)   | The SMS sender as reported by the OS (e.g. `"ChaseAlerts"`, `"+15551234567"`) |
| `accountId`    | TEXT (UUID) | FK to `account.id` (no DB-level FK constraint to match existing project conventions; integrity enforced in repository) |
| `linkedAtEpochMillis` | INTEGER | When the user created the link |

The single-column primary key on `senderId` *is* the uniqueness guarantee — no separate index needed. `accountId` is non-PK and unconstrained, so a wallet may hold many sender links (FR-013).

### 1.3 `pending_review_item`

A queued SMS awaiting user resolution.

| Column | Type | Notes |
|--------|------|-------|
| `id`                | TEXT (UUID) | PK |
| `dedupKey`          | TEXT        | `address + ":" + date + ":" + body-sha256` — UNIQUE (prevents the same SMS being queued twice) |
| `senderId`          | TEXT        | Denormalized for queue display |
| `body`              | TEXT        | Raw SMS body — needed to render the queue item |
| `messageEpochMillis`| INTEGER     | The original `date` from the system inbox |
| `templateId`        | TEXT        | FK-style reference to `sms_template.id` (the discovered template that didn't match an Active mapping) |
| `quarantineReason`  | TEXT        | Enum-as-string: `TEMPLATE_NOT_MAPPED`, `AMOUNT_NOT_PARSEABLE`, `SENDER_NOT_LINKED`, `CURRENCY_MISMATCH` |
| `enqueuedAtEpochMillis` | INTEGER | For sorting / housekeeping |

**Note**: per FR-034, `pending_review_item` rows are NOT included in the backup — they are derivable on next scan.

---

## 2. Migration `131 → 132`

File: `shared/data/core/src/main/java/com/ivy/data/db/migration/Migration131to132_SmsExtraction.kt`

```sql
-- sms_template
CREATE TABLE IF NOT EXISTS sms_template (
    id                    TEXT    NOT NULL PRIMARY KEY,
    pattern               TEXT    NOT NULL,
    wildcardSlotsJson     TEXT    NOT NULL,
    state                 TEXT    NOT NULL,
    classification        TEXT,
    senderIdHint          TEXT    NOT NULL,
    firstSeenEpochMillis  INTEGER NOT NULL,
    lastSeenEpochMillis   INTEGER NOT NULL,
    matchCount            INTEGER NOT NULL DEFAULT 0
);

-- sender_account_link  (PK on senderId enforces 1:1 sender→wallet)
CREATE TABLE IF NOT EXISTS sender_account_link (
    senderId              TEXT    NOT NULL PRIMARY KEY,
    accountId             TEXT    NOT NULL,
    linkedAtEpochMillis   INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_sender_account_link_accountId
    ON sender_account_link(accountId);

-- pending_review_item
CREATE TABLE IF NOT EXISTS pending_review_item (
    id                       TEXT    NOT NULL PRIMARY KEY,
    dedupKey                 TEXT    NOT NULL UNIQUE,
    senderId                 TEXT    NOT NULL,
    body                     TEXT    NOT NULL,
    messageEpochMillis       INTEGER NOT NULL,
    templateId               TEXT    NOT NULL,
    quarantineReason         TEXT    NOT NULL,
    enqueuedAtEpochMillis    INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pending_review_item_templateId
    ON pending_review_item(templateId);
```

The migration is **purely additive** — no existing table is altered. AccountEntity remains unchanged at v131 schema.

---

## 3. Modified existing entity

### 3.1 `TransactionMetadata` (in `shared/data/model/src/main/kotlin/com/ivy/data/model/Transaction.kt`)

Add two optional fields:

| Field | Type | Notes |
|-------|------|-------|
| `smsSourceDedupKey` | `String?` | The `dedupKey` of the originating SMS (NOT the raw body — privacy-preserving and re-derivable) |
| `smsTemplateId`     | `UUID?`   | The `SmsTemplate.id` that produced this transaction; FK-style reference |

The Room mapping is via the existing JSON-on-disk strategy used for `TransactionMetadata` (it is already serialized to a single column in `transaction` row — verified by the codebase scan). No table-level migration is needed for this change because the metadata column is JSON; old rows will deserialize with both fields = `null`.

**Backward compat**: kotlinx.serialization is configured with `ignoreUnknownKeys = true` project-wide, and these two fields are nullable with default `null`. Old metadata JSON loads cleanly. New metadata JSON loaded by an old client (e.g., during import) ignores the extra keys.

---

## 4. DataStore additions

A new key-value pair stored in the existing app-level DataStore:

| Key | Type | Purpose |
|-----|------|---------|
| `sms.watermark.epochMillis` | `Long?` | The `date` of the most recent SMS the reconciliation pipeline has processed. Initially `null`; updated atomically at the end of each scan. |
| `sms.scan.period.lowerBoundEpochMillis` | `Long` | The lower bound of the user-chosen historical scan period. Used by "Scan further back…" to compute the gap. |

DataStore is preferred over Room here: these are scalar settings, not relational data. The project already uses DataStore for similar app-level preferences.

---

## 5. Domain types (in-memory, in `feature:sms-sync/.../domain/model`)

These are the wrappers that `feature:sms-sync` exposes to its UI and to other modules. Mappers live in the data layer.

```
data class SmsMessage(
    val dedupKey: String,
    val senderId: String,
    val body: String,
    val timestamp: Instant,
)

sealed interface ScanPeriod {
    object LastWeek    : ScanPeriod
    object LastMonth   : ScanPeriod
    object LastQuarter : ScanPeriod
    object LastYear    : ScanPeriod
    object AllTime     : ScanPeriod
}

data class SmsTemplate(
    val id: SmsTemplateId,
    val pattern: String,                       // with inline <*> markers
    val wildcardSlots: List<WildcardSlot>,     // ordered left-to-right
    val state: TemplateState,                  // UNMAPPED / ACTIVE / BLACKLISTED / PENDING_REVIEW
    val classification: TransactionClassification?,  // nullable until user sets
    val senderIdHint: String,
    val firstSeen: Instant,
    val lastSeen: Instant,
    val matchCount: Int,
)

data class WildcardSlot(
    val id: WildcardId,
    val positionInPattern: Int,
    val contextSnippet: String,                // ~20 chars around the <*> for the accessible-list-item label (kept even though Q5 is OOS — cheap to compute)
    val mapping: WildcardMapping,
)

sealed interface WildcardMapping {
    object Unmapped : WildcardMapping
    object Amount   : WildcardMapping
    object Merchant : WildcardMapping
    object DateTime : WildcardMapping
    object Reference: WildcardMapping
    object Ignored  : WildcardMapping
}

enum class TemplateState  { UNMAPPED, ACTIVE, BLACKLISTED, PENDING_REVIEW }
enum class TransactionClassification { INCOME, EXPENSE, TRANSFER }

data class SenderAccountLink(
    val senderId: String,
    val accountId: AccountId,
    val linkedAt: Instant,
)

data class PendingReviewItem(
    val id: PendingReviewItemId,
    val sms: SmsMessage,
    val template: SmsTemplate,
    val quarantineReason: QuarantineReason,
    val enqueuedAt: Instant,
)

enum class QuarantineReason {
    TEMPLATE_NOT_MAPPED,
    AMOUNT_NOT_PARSEABLE,
    SENDER_NOT_LINKED,
    CURRENCY_MISMATCH,
}
```

Inline-value types (`SmsTemplateId`, `WildcardId`, `PendingReviewItemId`) are `@JvmInline value class` wrappers around `UUID` to follow the existing project convention for typed IDs (`AccountId`, `TransactionId`, etc.).

---

## 6. Validation rules (enforced in domain / repository)

| Rule | Where enforced |
|------|----------------|
| A template is `ACTIVE` only if `classification != null` AND at least one wildcard's mapping is `Amount` | `MapTemplateUseCase` rejects save with a descriptive `Either.Left` otherwise |
| `senderId` is unique across `sender_account_link` (FR-016) | DB-level: PK on `senderId`. UI-level: pre-write check returns `Either.Left("LINK_CONFLICT: …")` with the conflicting wallet's name |
| `dedupKey` is unique across `pending_review_item` (FR-006) | DB-level: UNIQUE constraint on column |
| When a template transitions to `BLACKLISTED`, all `pending_review_item` rows where `templateId = template.id` are deleted in the same DB transaction | `BlacklistTemplateUseCase` |
| When a template transitions from `UNMAPPED → ACTIVE`, all queued `pending_review_item` rows for that template are processed via `RouteSmsUseCase` (which will now Tier 1 them) and removed | `MapTemplateUseCase` |
| Transactions auto-created from SMS carry `metadata.smsSourceDedupKey` and `metadata.smsTemplateId` (FR-030) | `CreateTransactionFromSmsUseCase` |

---

## 7. Storage size estimate (sanity check)

Single-user, ~1 year of SMS, ~30 unique templates discovered:
- `sms_template`: ~30 rows × ~500 bytes per row (pattern + JSON wildcards) ≈ **15 KB**
- `sender_account_link`: ~5 rows × ~80 bytes ≈ **400 bytes**
- `pending_review_item`: typically 0–10 rows in steady state ≈ **< 5 KB**
- DataStore: 2 Longs ≈ **24 bytes**

Total feature footprint in DB: well under 100 KB. Negligible compared to existing transaction history.
