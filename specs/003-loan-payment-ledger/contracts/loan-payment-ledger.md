# Contract: Loan Payment Ledger (Project A)

**Provider**: `feature/loans` (use cases + UI) + `shared/data/core` (schema/repository)
**Consumers**: `feature/sms-sync` (review-card link action), `feature/edit-transaction`
(transaction-detail link action), loan screens

## Core invariant (the iron rule)

**One real-world payment = exactly one `Transaction` row.**
- `PayLoanEntryUseCase` CREATES the transaction (with the record).
- `LinkTransactionToLoanUseCase` ADOPTS an existing transaction (record only, no new row,
  no balance change — SC-006).
- Nothing else creates loan money. `toggleLoanItemSettled`-style flag flips are removed from
  the payment path.

## Use case signatures (domain layer, all `Either<String, T>`, all main-safe)

```kotlin
// @Singleton, mutex-guarded (double-tap race, US5.8)
PayLoanEntryUseCase(req: EntryPaymentRequest): Either<String, PaymentReceipt>
// req: entryId, amount, accountId, dateTime
// validates: 0 < amount <= remaining (computed in same txn scope); account exists
// effects: loan_record(loanItemId=entryId, DECREASE, non-interest) +
//          transaction via LoanTransactionsCore.createMainTransaction (direction from loan type)

UnsettleLoanEntryUseCase(entryId): Either<String, UnsettleReceipt>
// preflight(entryId) → counts records + transactions for honest dialog copy (FR-016)
// effects: delete entry's records AND their transactions (balances restored);
//          legacy synthetic record (no transaction) → delete record + flip isSettled=0

LinkTransactionToLoanUseCase(transactionId, loanId, allocation): Either<String, LinkReceipt>
// allocation: AutoOldestFirst | Manual(entryId → amount) | LoanLevelOnly
// preconditions: transaction has no loanRecordId; loan exists
// direction = inferDirection(transactionFlow, loanType)  // pure fn, 4 rows, exhaustive test
//   moneyOut × Lent      → INCREASE (no allocation step offered)
//   moneyOut × Borrowed  → DECREASE (allocation eligible)
//   moneyIn  × Lent      → DECREASE (allocation eligible)
//   moneyIn  × Borrowed  → INCREASE (no allocation step offered)
// effects: 1..n loan_records (split allowed oldest-first, each ≤ entry remaining, remainder
//          loan-level; parts sum EXACTLY to transaction amount); transaction gains
//          loanId + loanRecordId (primary record); NO new transaction
```

## Derivation contract (what UI may assume)

- `DisplayLoanItem.paidAmount` = sum of its DECREASE non-interest records (repository query,
  not UI math). `settledDerived = paidAmount >= amount (epsilon)`. `remaining = amount - paid`.
- Loan header paid = records-only sum. After migration 137's synthetic backfill, legacy
  settled entries contribute exactly their old amounts — the visible paid total does NOT
  change across the app update (regression test on migration).
- Legacy badge ("settled — no transaction"): entry whose records have no matching
  `transactions.loanRecordId` row. Excluded from payment-history jump-links.

## UI contracts

- **EntryPaymentSheet** (`feature/loans`): opens from entry circle tap; pre-filled
  {remaining, loan default wallet, today}; amount editable via existing `AmountModal`;
  confirm disabled + inline reason on invalid input; ONE sheet instance max (VM gate).
  2-tap happy path (SC-002).
- **LoanItemCard** states: unsettled / partial (half circle + progress + "paid X · left Y") /
  settled (strike + check) / legacy-settled (settled + badge). Expand affordance when
  records exist: rows {amount, wallet, date, tx-link}.
- **LinkToLoanSheet** (`feature/loans`, hosted via events from sms-sync review card and
  edit-transaction detail, same hosting pattern as SmsSetupSheet on MainScreen):
  step 1 loan list (+ "New loan from this transaction"), step 2 allocation (only when
  direction = DECREASE and loan has open entries): AutoOldestFirst preview / manual pick /
  LoanLevelOnly. CTA copy states the rule: "Link — no new transaction".
- Un-settle: circle tap on settled entry → confirm dialog with real counts
  ("Delete 2 payments and 2 transactions?" / legacy: "…no transaction to remove").

## Cascade rules (both directions)

| Trigger | Effect |
|---|---|
| Un-settle confirm | entry's records + their transactions deleted together |
| Transaction (with loanRecordId) deleted anywhere in the app | its record(s) deleted; loan totals recompute (FR-024) — verify existing `LoanTransactionsCore` coverage, extend deletion path if gap |
| Entry deleted with payments | records kept, `loanItemId := NULL` (loan-level), transactions untouched (FR-019) |
| Loan deleted | existing cascade (`loan_items` ON DELETE CASCADE; records per existing behavior) unchanged |

## Consumer obligations

- `feature/sms-sync` review card and `feature/edit-transaction` detail MUST route link actions
  through `LinkTransactionToLoanUseCase` only (no direct record writes) and MUST NOT offer
  linking when the transaction already has `loanRecordId`.
- No consumer may write `loan_items.isSettled` except `UnsettleLoanEntryUseCase`'s legacy path.
