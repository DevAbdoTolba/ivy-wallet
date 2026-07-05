# Phase 1 Data Model: 003-loan-payment-ledger

**Date**: 2026-07-05 · **Plan**: [plan.md](./plan.md) · **Research**: [research.md](./research.md)

Room database: `IvyRoomDatabase` — version **135 → 137** (two additive migrations, ordered to
match the build order: Project C ships 136, Project A ships 137).

---

## 1. Changed tables

### 1.1 `sms_template` (migration 135→136)

| Column | Type | New? | Notes |
|---|---|---|---|
| `transferToAccountId` | `TEXT` NULL | **NEW** | UUID string of the default destination wallet for Transfer-role templates. NULL = "ask each time" (quarantine path). Never equal to the route-time source account at use time — checked in the use case, not the schema (source varies per sender link). |

```sql
ALTER TABLE sms_template ADD COLUMN transferToAccountId TEXT;
```

- No index (template count is tiny; lookups are by PK).
- No backfill: all existing templates get NULL → existing transfer templates start quarantining
  to `TRANSFER_NEEDS_DESTINATION` instead of writing ghosts. That is the intended behavior flip.
- Domain: `SmsTemplate.transferToAccountId: AccountId?`; mapped in `SmsTemplateMapper`
  (`asString()`/`roleFromString()` untouched — this is a column, not a slot role).
- Backup: `sms_template` already round-trips through the SMS backup extension; the new nullable
  column joins the serialized form. Old backups (missing key) decode as NULL — safe.

### 1.2 `loan_records` (migration 136→137)

| Column | Type | New? | Notes |
|---|---|---|---|
| `loanItemId` | `TEXT` NULL | **NEW** | UUID string of the `loan_items` row this payment targets. NULL = loan-level payment (today's semantics). Indexed. |

```sql
ALTER TABLE loan_records ADD COLUMN loanItemId TEXT;
CREATE INDEX IF NOT EXISTS index_loan_records_loanItemId ON loan_records(loanItemId);
```

**Backfill (same migration, after the ALTER)** — synthetic records for legacy settled entries
(research R6). One DECREASE record per settled entry that has no entry-targeted record yet:

```sql
INSERT INTO loan_records (id, loanId, loanItemId, amount, note, dateTime,
                          interest, accountId, convertedAmount, loanRecordType,
                          isSynced, isDeleted)
SELECT
  -- dash-formatted UUID (lesson from Migration134to135_RepairLoanItemIds: randomblob-style
  -- dashless ids crash UUID.fromString) — build via hex(randomblob()) segments:
  lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || substr(hex(randomblob(2)),2)
        || '-' || substr('89ab', abs(random()) % 4 + 1, 1) || substr(hex(randomblob(2)),2)
        || '-' || hex(randomblob(6))),
  li.contactId,                 -- loan id (column is named contactId, references loans.id)
  li.id,
  li.amount,
  'Legacy settled entry',
  li.createdAt,
  0,                            -- interest = false
  NULL,                         -- no wallet — no transaction exists for it
  NULL,
  'DECREASE',
  1, 0
FROM loan_items li
WHERE li.isSettled = 1
  AND NOT EXISTS (SELECT 1 FROM loan_records lr WHERE lr.loanItemId = li.id);
```

- Idempotent via the `NOT EXISTS` guard (re-run inserts nothing).
- `accountId = NULL` + no `transactions.loanRecordId` pointing back ⇒ renders as
  "settled — no transaction" (derived legacy badge, no marker column).
- FK discipline: no SQLite FK constraint added (would force table rebuild). Referential
  integrity enforced in the write path — entry deletion nulls `loanItemId` via DAO update
  (research R7).

### 1.3 Unchanged tables relied upon

- `transactions`: existing `loanId`, `loanRecordId`, `smsSourceDedupKey` columns are the link
  substrate. SMS-created rows keep `loanId/loanRecordId = NULL` at creation; set at link time.
- `loan_items`: schema untouched. `isSettled` becomes **read-mostly**: still read for backup
  compat and as the legacy badge input, no longer written by the payment flow (only by legacy
  un-settle, which flips it after deleting the synthetic record).

## 2. Derivation rules (single source of truth)

| Derived value | Formula | Computed in |
|---|---|---|
| Entry `paidAmount` | `SUM(loan_records.amount) WHERE loanItemId = entry.id AND loanRecordType = 'DECREASE' AND interest = 0` | `LoanRecordDao.sumByLoanItemId` → `LoanRepository` |
| Entry `isSettledDerived` | `paidAmount >= entry.amount` (tolerance: currency-minor-unit epsilon) | domain layer (`DisplayLoanItem`) |
| Entry `isLegacySettled` | has ≥1 record with `loanItemId = entry.id` whose id has **no** `transactions.loanRecordId` match AND note = legacy marker path | domain layer |
| Entry `remaining` | `max(0, amount - paidAmount)` — pre-fill for the payment sheet; cap for FR-015 | `PayLoanEntryUseCase` (same txn scope as insert) |
| Loan `amountPaid` | `SUM(records.amount) WHERE loanId = loan.id AND loanRecordType='DECREASE' AND interest=0` — **records only**, settled entries no longer added (removes today's hybrid at `LoanDetailsViewModel.kt:449/505` and `LoanViewModel.kt:452-465`) | both loan ViewModels via repository |
| Loan `totalAmount` | unchanged: sum of entry amounts, falling back to `loan.amount` when the loan has no entries | unchanged code path |
| Transfer destination at route time | `template.transferToAccountId`, validated: non-null, ≠ source, account exists — else typed `MissingTransferDestination` outcome | `CreateTransactionFromSmsUseCase` |

## 3. State transitions

### Loan entry lifecycle

```text
              pay(part)                    pay(rest)
 UNSETTLED ──────────────► PARTIAL ──────────────────► SETTLED (derived)
    │  ▲                     │  ▲                         │
    │  └── unsettle-confirm ─┘  └── unsettle-confirm ─────┘
    │      (delete records + their transactions,
    │       balances restored — FR-016)
    │
    └── LEGACY-SETTLED (from migration backfill):
        unsettle-confirm deletes synthetic record only, flips isSettled=0,
        dialog copy: "no transaction to remove"
 delete entry (any state, has payments) → records keep amount, loanItemId := NULL (FR-019)
```

### SMS transfer message lifecycle (new/changed paths only)

```text
 matched ACTIVE transfer template
   ├─ destination set & valid ──► Transfer committed (from=linked wallet, to=destination)   [US3.1]
   ├─ destination NULL ─────────► PENDING (TRANSFER_NEEDS_DESTINATION)                      [US3.2]
   │        └─ user picks wallet ─► Transfer committed; if "remember" → write destination
   │                                to template → future messages take the top branch      [US3.3-4]
   └─ destination == source or account deleted ──► PENDING (TRANSFER_NEEDS_DESTINATION)    [US3.5-6]

 ghost rows (pre-137 data): RepairGhostTransfersUseCase (one-shot, versioned)
   find → delete → ReprocessHistorical(sender, from=minDate-1) → messages re-enter above    [US4]
```

### Link-to-loan lifecycle

```text
 existing transaction (SMS or manual), no loan link
   └─ Link to loan → pick loan → direction inference (pure fn, 4 rows):
        INCREASE outcome: record(loanId, amount, ref=transaction) — no allocation step
        DECREASE outcome: optional allocation → 0..n records? NO — one record; allocation
                          sets loanItemId of THAT record (single entry) or auto-split:
                          oldest-first allocation MAY split into multiple records whose
                          amounts sum to the transaction amount (each ≤ its entry remaining;
                          remainder becomes a loan-level record)
   invariants: no new transaction row; wallet balances unchanged (SC-006);
               transaction.loanId/loanRecordId set (primary record's id when split)
   transaction later deleted → all records referencing it deleted, totals recompute (FR-024)
```

## 4. Entity ↔ domain ↔ view mapping additions

| Layer | Type | Additions |
|---|---|---|
| Entity | `SmsTemplateEntity` | `transferToAccountId: String?` |
| Entity | `LoanRecordEntity` | `loanItemId: UUID?` |
| Domain | `SmsTemplate` | `transferToAccountId: AccountId?` |
| Domain | `LoanRecord` (legacy model) | `loanItemId: UUID?` passthrough |
| Domain (new) | `CreateFromSmsOutcome` | sealed: `Created(tx)` / `MissingTransferDestination` (feeds quarantine) |
| Domain (new) | `EntryPaymentRequest` | entryId, amount, accountId, dateTime — input of `PayLoanEntryUseCase` |
| ViewState | `DisplayLoanItem` | `paidAmount`, `remaining`, `settledDerived`, `legacySettled`, `payments: List<PaymentRow>` (amount, wallet, date, transactionId?) |
| ViewState | pending review item | `needsDestination: Boolean`, chosen destination, remember flag |

## 5. Validation rules (enforced in domain layer, `Either`-style)

- Entry payment: `0 < amount ≤ remaining`; wallet required (loan default pre-filled, pick
  forced when loan has none); date defaults to now. Violations → `Left` with user-readable
  reason; sheet shows inline error, confirm disabled (FR-015, US5.4-5).
- Self-transfer: refused at write on every path (FR-009) — guard in
  `CreateTransactionFromSmsUseCase` plus assertion in the resolve path.
- Link: transaction must not already carry a `loanRecordId`; loan must exist; allocation
  amounts must sum to the transaction amount with each part ≤ its entry remaining.
- Un-settle: requires records to delete (or legacy flag); confirmation event carries the
  count of transactions that will be removed for honest dialog copy.
