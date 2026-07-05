# Phase 0 Research: 003-loan-payment-ledger

**Date**: 2026-07-05 · **Plan**: [plan.md](./plan.md)

Product-level decisions (D1–D8) were made and approved via the 2026-07-05 design report
(artifact "Ivy Wallet — 5 Design Decisions"); they are restated in spec.md's Assumptions.
This document resolves the remaining **technical** decision points. All "today" facts were
verified against the code on 2026-07-05 (file:line refs below).

---

## R1. Lock overlay mechanics

**Decision**: Keep `NavigationRoot(navigation) { IvyNavGraph(screen) }` composed unconditionally
in `RootActivity.setContent`. When `appLocked == true`, compose `AppLockedScreen` *after* it
inside the same root `Box`, i.e. z-ordered on top, fully opaque. Input blocking lives in
`AppLockedScreen` itself: a root-level `Modifier.pointerInput` that consumes all events,
a `BackHandler` that swallows back, and `LocalFocusManager.clearFocus()` + IME hide on lock
transition. The `appLocked == null` (startup, undetermined) branch keeps today's blank surface.

**Rationale**: Today's `when (appLocked)` branch swap (`RootActivity.kt:99-155`) destroys the
whole app composition; every `remember {}` form field dies (`EditTransactionScreen.kt:238-266`,
`LoanItemModal.kt:34-40`). Overlay keeps the composition alive → all state survives with one
localized change. FLAG_SECURE handling in `onWindowFocusChanged` (`RootActivity.kt:298-308`)
is untouched, so recents-preview privacy is preserved.

**Alternatives considered**:
- *Migrate all form state to ViewModels / `rememberSaveable`* — rejected as the primary fix:
  touches every form in the app (only 2 `rememberSaveable` usages exist repo-wide today),
  weeks of churn, easy to miss one screen. Kept as slow background hygiene, not a prerequisite.
- *Separate lock Activity on top* — rejected: activity lifecycle interplay is heavier than a
  composable overlay and reintroduces state-restore questions for no benefit.
- *Blur/translucent cover* — rejected: opaque is simpler and leaks nothing.

**Cold start while locked**: the app composes underneath the overlay immediately; data loads
behind the cover. No special casing.

## R2. Lock delay storage & semantics

**Decision**: New `SharedPrefs.LOCK_DELAY_SECONDS: Int` (default `60`), values
`0 | 60 | 300 | 900` mapping to Instantly / 1 / 5 / 15 min. `RootViewModel` reads it in
`startUserInactiveTimeCounter()` instead of the hardcoded `USER_INACTIVITY_TIME_LIMIT = 60`
(`RootViewModel.kt:51`). `0` locks synchronously in `onPause` (no timer). Settings row is
visible only when app lock is enabled, mirroring the existing `APP_LOCK_ENABLED` toggle
pattern (`SettingsViewModel.kt:322-325`, `SettingsScreen.kt:812`).

**Rationale**: SharedPrefs is where the sibling flag already lives; DataStore migration for
one int is over-engineering (Principle I). Reading the value at timer start (not caching at
startup) makes the setting take effect without restart (FR-003).

**Alternatives considered**: DataStore (rejected — inconsistent with sibling key, more code);
arbitrary user-typed minutes (rejected — four fixed choices cover the need, simpler UI).

## R3. `start()` idempotence guard (FR-004 insurance)

**Decision**: `EditTransactionViewModel` gains a private `loadedForScreen: EditTransactionScreen?`
(or equivalent identity token). `start(screen)` no-ops when called again with the same screen
instance while state is already displayed. The `LaunchedEffect(Unit)` in
`EditTransactionScreen.kt:98-100` stays — the guard makes it harmless.

**Rationale**: With the overlay (R1) the screen never leaves composition on lock, so
`LaunchedEffect(Unit)` won't re-fire in that path; the guard is cheap insurance against *other*
recomposition paths (e.g. future nav changes) re-running `start()` → `reset()`
(`EditTransactionViewModel.kt:149-187, 827-835`) over live user data.

**Alternatives considered**: removing the `LaunchedEffect` and driving `start()` from nav
(rejected — larger refactor of a legacy screen for the same effect).

## R4. Missing transfer destination as a typed outcome (not exception, not null)

**Decision**: `CreateTransactionFromSmsUseCase`'s Transfer branch (`CreateTransactionFromSmsUseCase.kt:239-252`)
changes to: resolve destination = `template.transferToAccountId`; if present and ≠ source →
build real `Transfer(fromAccount = linked, toAccount = destination, toValue = fromValue)`;
if absent, or equal to source, or referencing a deleted account → return a typed
`CreateFromSmsOutcome.MissingTransferDestination` (sealed result alongside the success path).
`RouteSmsUseCase` maps that outcome to quarantine reason `TRANSFER_NEEDS_DESTINATION`
(new enum value next to `SENDER_NOT_LINKED` etc., `RouteSmsUseCase.kt:72-93`). A defensive
write-guard in the same use case refuses to persist *any* transfer with
`fromAccount == toAccount` regardless of path (FR-009).

**Rationale**: Today the branch silently fabricates `toAccount = account` and the read mapper
drops the row (`TransactionMapper.kt:101-104` + `getOrNull()`/`mapNotNull` in
`TransactionRepository.kt:90-93, 242, 335-342`) → invisible ghost rows. A sealed outcome keeps
the error typed (Principle III) and routes through the existing quarantine machinery instead
of a new mechanism. Deleted-destination fallback satisfies spec US3 scenario 6.

**Alternatives considered**: nullable `toAccount` on the domain `Transfer` (rejected — domain
model requires non-null `toAccount`, `Transaction.kt:51-64`, and loosening it would ripple
through every consumer); throwing (rejected — Principle III); auto-picking "first other
wallet" (rejected — silent wrong guesses are the bug we're fixing).

## R5. Ghost-transfer repair strategy

**Decision**: `RepairGhostTransfersUseCase`, one-shot and versioned (an int version key in
SharedPrefs, same pattern as `RenormalizePersistedSmsDataUseCase`), executed inside the
SyncSms mutex before parser seeding. Algorithm:

1. Query ghost rows: `transactions` where `type = TRANSFER AND accountId == toAccountId AND
   smsSourceDedupKey IS NOT NULL` (write path persisted them without validation,
   `TransactionRepository.save()` → `toEntity()`, no check).
2. Group by linked sender; record `min(date)` per sender.
3. Delete the ghost rows (their `smsSourceDedupKey`s become free — the dedup guard in
   `CreateTransactionFromSmsUseCase` no longer sees them as consumed).
4. Re-drive `ReprocessHistoricalUseCase` per affected sender from `min(date) - 1` (it already
   resolves its lower bound as `historicalLowerBound ?: globalLowerBound ?: 0L` and applies
   the standard routing pipeline). With R4 in place, the re-encountered messages route to
   `TRANSFER_NEEDS_DESTINATION` → they surface as review items. Idempotent by construction:
   second run finds zero ghost rows.

**Rationale**: Reuses the whole existing read→cluster→route pipeline instead of hand-crafting
`PendingReviewItem`s from transaction rows (which would need body/sender reconstruction the
transaction row doesn't carry). Watermarks are NOT reset (invariant preserved) — reprocess uses
its own historical bound. Spec's "not deleted" requirement refers to the *user-visible money
information*: the SMS source data still exists on device and resurfaces as a review item; the
ghost row itself was never user-visible.

**Edge — SMS deleted from inbox since**: the reprocess simply won't find it; the ghost row is
gone and nothing surfaces. Accepted: the row was already invisible and unrecoverable-by-design
before this feature; we log the per-sender rescued/expected counts via SmsTrace for the UAT
capture.

**Alternatives considered**: synthesizing pending items directly from ghost rows (rejected —
`PendingReviewItem` needs sender/body context; fabricating it duplicates pipeline logic);
flipping ghost rows' `toAccountId` to NULL + surfacing as "broken transfers" list (rejected —
new UI surface for a one-time event, violates Principle I).

## R6. Legacy settled entries → synthetic records backfill

**Decision**: Migration 136→137, after adding `loan_records.loanItemId`, **backfills one
synthetic DECREASE loan record per legacy settled entry**: for every `loan_items` row with
`isSettled = 1` that has no records, insert a `loan_records` row with `loanId = contactId`,
`loanItemId = id`, `amount = amount`, `dateTime = createdAt`, `interest = 0`,
`loanRecordType = DECREASE`, `note = 'Legacy settled entry'`, fresh UUID (dash-formatted —
lesson from `Migration134to135_RepairLoanItemIds`). No transactions are created.

**Rationale**: FR-017 makes loan "paid" = sum of records only. Without backfill, every
already-settled entry's amount would vanish from the paid total the moment the user updates —
a silent data regression. Synthetic records preserve totals exactly, make `isSettled` fully
derivable (paid ≥ amount), and honor D6's actual prohibition, which is about **transactions**
(balance corruption), not records. "Settled — no transaction" state is *derived*: a record
with no transaction row pointing at it (`transactions.loanRecordId`) renders the legacy badge —
no marker column needed.

**Consequences**: `loan_items.isSettled` stops being written by new flows (kept for backup
round-trip compat and as migration input); un-settling a legacy entry deletes the synthetic
record only and warns "no transaction to remove". Migration is idempotent: the backfill query
skips entries that already have a record with `loanItemId = id`.

**Alternatives considered**: keep hybrid paid formula for legacy only (rejected — permanent
two-source-of-truth complexity, the exact disease being cured); drop legacy paid amounts
(rejected — user-visible data loss); backfill transactions too (rejected by owner, D6).

## R7. Direction inference & cascade placement

**Decision**: The 4-row direction table (money-out/in × Lent/Borrowed → INCREASE/DECREASE +
entry-allocation eligibility) lives in **one** pure function inside
`LinkTransactionToLoanUseCase` (unit-tested exhaustively). Entry allocation is only offered
for DECREASE outcomes. Cascade rules:

- *Entry payment* (`PayLoanEntryUseCase`): record + transaction created together;
  `transactions.loanId/loanRecordId` set at creation via the existing `LoanTransactionsCore`
  path (`LoanTransactionsCore.createMainTransaction`, `LoanTransactionsCore.kt:158-207`).
- *SMS link* (`LinkTransactionToLoanUseCase`): record created **without** a transaction;
  the existing transaction row gets `loanId/loanRecordId` set (today hard-coded null at SMS
  creation, `CreateTransactionFromSmsUseCase.kt:204-205` — unchanged at creation time; set
  only at link time). Record→transaction pointer is the existing reverse lookup
  (`transactionRepository.findLoanRecordTransaction`), no new column.
- *Transaction deleted* → its loan record deleted (FR-024): verify whether
  `LoanTransactionsCore` already handles this direction; extend the transaction-deletion path
  if a gap is confirmed (flagged as a verification task, not an assumption).
- *Entry deleted with payments* → records' `loanItemId` set NULL (they become loan-level),
  transactions untouched (FR-019). `ON DELETE SET NULL` semantics implemented in the write
  path (Room FK with SET NULL would force table rebuild; the DAO update is simpler and
  equally safe because deletes flow through `LoanRepository`).
- *Entry-payment cap* (FR-015): validated inside `PayLoanEntryUseCase` against
  `remaining = amount - paidSum` computed in the same transaction scope as the insert, so
  two racing sheets cannot overshoot (the use case is `@Singleton` with a mutex, mirroring
  the `CreateTransactionFromSmsUseCase` dedup pattern).

**Rationale**: One pure function = one place the money logic can be wrong (and one test).
DAO-level SET NULL keeps the FK migration additive (no table rebuild). Mutex-guarded use case
kills the double-tap race (spec US5 scenario 8) at the domain layer instead of only debouncing
UI.

**Alternatives considered**: spreading direction logic across ViewModels (rejected — untestable
duplication); Room FK `ON DELETE SET NULL` on loanItemId (rejected — requires table rebuild in
migration for marginal benefit); UI-only double-tap debounce (rejected — protects one surface,
not the invariant).

---

**All technical unknowns resolved. No NEEDS CLARIFICATION remain.** → Phase 1.
