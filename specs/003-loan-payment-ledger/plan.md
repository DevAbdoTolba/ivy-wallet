# Implementation Plan: Unified Loan Payment Ledger, Resilient App Lock & SMS Transfer Destinations

**Branch**: `003-loan-payment-ledger` | **Date**: 2026-07-05 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/003-loan-payment-ledger/spec.md`

## Summary

Three sub-projects shipped in strict order, each independently verifiable:

- **Project B — App lock overlay (US1–US2, FR-001..004).** Stop tearing down the composition tree on lock. `RootActivity` composes `NavigationRoot` unconditionally and, when locked, draws `AppLockedScreen` as an opaque, input-consuming overlay above it. Add a "Lock after" setting (Instantly / 1 / 5 / 15 min, default 1 min) replacing the hardcoded 60 s in `RootViewModel`. Guard `EditTransactionViewModel.start()` so re-entry into composition can never reset a populated screen.
- **Project C — SMS transfer destinations (US3–US4, FR-005..010).** `sms_template` gains nullable `transferToAccountId` (migration 135→136). `CreateTransactionFromSmsUseCase` stops defaulting `toAccount = fromAccount`: with a saved destination it builds a real two-sided transfer; without one it returns a typed failure that `RouteSmsUseCase` quarantines as new reason `TRANSFER_NEEDS_DESTINATION`. The pending-review card gets a destination picker + "remember for this template". A one-shot versioned repair (same pattern as `RenormalizePersistedSmsDataUseCase`) converts existing ghost self-transfer rows into review items by deleting them and re-driving `ReprocessHistoricalUseCase` over the affected senders/date range.
- **Project A — Loan payment ledger (US5–US7, FR-011..025).** `loan_records` gains nullable `loanItemId` (migration 136→137, with index + synthetic legacy records for already-settled entries). Entry paid amount and settled state become derived from records. Tapping an entry's circle opens a pre-filled payment sheet; confirming runs a new `PayLoanEntryUseCase` that creates the record **and** its balance-affecting transaction through the existing `LoanTransactionsCore` machinery. "Link to loan" on review cards and transaction detail creates a record that adopts the existing transaction (sets `loanId`/`loanRecordId` on it) and never spawns a second one. Loan "paid" = sum of records only.

All five product decisions (D1–D8) were approved on the 2026-07-05 design report; no open clarifications.

## Technical Context

**Language/Version**: Kotlin (project default), 100% Jetpack Compose
**Primary Dependencies**: Hilt, ArrowKt (`Either` + `either { }`), Room, Material3, Coroutines/Flow — no new libraries
**Storage**: Room — existing `IvyRoomDatabase`, currently **v135** (`shared/data/core/.../db/IvyRoomDatabase.kt`). This feature adds **135→136** (`sms_template.transferToAccountId`) and **136→137** (`loan_records.loanItemId` + index + legacy-record backfill). Lock delay stored in `SharedPrefs` next to the existing `APP_LOCK_ENABLED` key.
**Testing**: JUnit + Kotest for use cases, mappers, migrations (in-memory Room); Paparazzi for the new/changed Compose surfaces (payment sheet, entry card states, review destination card). Migration tests follow `LoanItemMigrationsTest.kt` conventions.
**Target Platform**: Android, sideloaded personal build. No new permissions.
**Project Type**: Mobile, multi-module Android Gradle project (existing modules only — no new module)
**Performance Goals**: Lock/unlock adds no perceptible frame cost (overlay is a single full-screen surface). Payment sheet opens < 200 ms from tap. Ghost repair is one-shot and runs inside the existing SyncSms mutex without blocking first frame.
**Constraints**: Preserve every 2026-06 SMS invariant (FR-025): raw-body dedupKey identity, frozen ACTIVE/BLACKLISTED templates, per-sender watermarks (`historicalLowerBound ?: globalLowerBound ?: 0L`), single shared aligner in `CreateTransactionFromSmsUseCase`, `@Singleton` + dedup mutex on transaction creation. One real-world payment = exactly one `Transaction` row, always. No gradle invocations by the agent — build/test commands are handed to the owner (see quickstart.md).
**Scale/Scope**: Single user. ~20 modified files + ~8 new files across `app`, `feature/settings`, `feature/sms-sync`, `feature/loans`, `shared/data/core`. Two Room migrations. No new screens — one new sheet (payment), one extended sheet (link-to-loan), extensions to two existing screens.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Compliance Plan |
|-----------|-----------------|
| **I. Pragmatic Simplicity (Grug Brained)** | Reuse beats invention everywhere: the payment ledger reuses `loan_records` + `LoanTransactionsCore` instead of a new payments table; ghost repair reuses `ReprocessHistoricalUseCase` instead of synthesizing pending items from transaction rows; lock fix is one overlay in one file instead of migrating every form to ViewModel state; lock delay reuses `SharedPrefs`. The only new columns are two nullable foreign keys. |
| **II. Layered Unidirectional Architecture** | New logic lands as use cases (`PayLoanEntryUseCase`, `UnsettleLoanEntryUseCase`, `LinkTransactionToLoanUseCase`, `RepairGhostTransfersUseCase`) called from ViewModels; Composables stay dumb (sheet renders state, emits events). Entity↔domain mapping extended in existing mappers (`SmsTemplateMapper`, loan mappers) — no UI reaching into DAOs. |
| **III. Type-Safe Error Handling** | `CreateTransactionFromSmsUseCase` returns a typed outcome for the missing-destination case (consumed by `RouteSmsUseCase` as a quarantine reason — not an exception). Payment/link use cases return `Either<String, T>`; validation failures (zero/negative/over-remaining amount, missing wallet) are `Left` values surfaced as inline sheet errors. |
| **IV. Main-Safe Operations** | All DAO writes, migrations-adjacent repairs, and the reprocess drive run under `withContext(dispatchers.io)` per the existing `AppCoroutinesDispatchers` pattern. The lock overlay changes composition only — no IO. Payment sheet confirmation is a single suspend call; UI shows progress state from the ViewModel. |
| **V. Testing & Quality Assurance** | Unit: migration 135→136 & 136→137 (including legacy-record backfill idempotence), route-to-quarantine on missing destination, self-transfer write-guard, ghost-repair one-shot versioning, paid-amount derivation, direction inference (4-row table), entry-payment cap, cascade deletes both directions. Paparazzi: entry card (unsettled / partial / settled / legacy-settled), payment sheet, review card with destination picker. On-device UAT script in quickstart.md with `logs/capture.bat` titles per story. |

**Initial result: PASS** — no violations, no Complexity Tracking entries needed. Re-checked after Phase 1 design: **still PASS** (design added no new modules, no new abstractions beyond the four use cases, no exception-based flows).

## Project Structure

### Documentation (this feature)

```text
specs/003-loan-payment-ledger/
├── plan.md              # This file
├── research.md          # Phase 0 — decisions: overlay mechanics, repair strategy, legacy backfill, direction inference
├── data-model.md        # Phase 1 — migrations 135→136, 136→137; derivation rules; state transitions
├── quickstart.md        # Phase 1 — build/test commands (owner-run), per-story UAT scripts
├── contracts/           # Phase 1 — behavior contracts between modules
│   ├── app-lock-overlay.md          # RootActivity/RootViewModel lock semantics + settings key
│   ├── sms-transfer-destination.md  # Template field, quarantine reason, write-guard, repair
│   └── loan-payment-ledger.md       # Pay/Unsettle/Link use cases, derivation, cascade rules
├── checklists/
│   └── requirements.md  # From /speckit-specify — all green
└── tasks.md             # Created later by /speckit-tasks (NOT this command)
```

### Source Code (repository root) — new and modified files only

```text
# ── Project B: app lock overlay ─────────────────────────────────────────────
app/src/main/java/com/ivy/wallet/
├── RootActivity.kt                    # MOD — setContent: always compose NavigationRoot; overlay AppLockedScreen when locked;
│                                      #        remove the when(appLocked) branch swap (lines ~99-155)
├── RootViewModel.kt                   # MOD — USER_INACTIVITY_TIME_LIMIT → read from SharedPrefs (LOCK_DELAY_SECONDS);
│                                      #        "Instantly" = lock in onPause without timer
└── ui/applocked/AppLockedScreen.kt    # MOD — self-contained input blocking: consume all pointer input, BackHandler,
                                       #        clear focus/IME on lock

shared/base/src/main/java/com/ivy/base/legacy/
└── SharedPrefs.kt                     # MOD — add LOCK_DELAY_SECONDS key (default 60)

feature/settings/src/main/java/com/ivy/settings/
├── SettingsScreen.kt                  # MOD — "Lock after" row (visible when lock enabled): Instantly/1/5/15 min
└── SettingsViewModel.kt               # MOD — read/write LOCK_DELAY_SECONDS; new SettingsEvent

feature/edit-transaction/src/main/java/com/ivy/transaction/
├── EditTransactionScreen.kt           # MOD — hardening only: keep LaunchedEffect but start() becomes idempotent
└── EditTransactionViewModel.kt        # MOD — start() no-ops if this screen instance already loaded (FR-004 insurance)

# ── Project C: SMS transfer destinations ────────────────────────────────────
shared/data/core/src/main/java/com/ivy/data/
├── db/entity/SmsTemplateEntity.kt     # MOD — + transferToAccountId: String? column
├── db/IvyRoomDatabase.kt              # MOD — version 135 → 137, register both migrations
└── db/migration/
    ├── Migration135to136_SmsTransferDestination.kt   # NEW
    └── Migration136to137_LoanRecordItemLink.kt       # NEW (Project A, listed once here)

feature/sms-sync/src/main/java/com/ivy/sms/
├── domain/model/SmsTemplate.kt        # MOD — + transferToAccountId: AccountId?
├── domain/model/QuarantineReason.kt   # MOD — + TRANSFER_NEEDS_DESTINATION (wherever the enum lives today)
├── data/SmsTemplateMapper.kt          # MOD — map new column both ways
├── data/SmsTemplateRepository.kt      # MOD — expose destination update (for "remember" checkbox)
├── domain/usecase/CreateTransactionFromSmsUseCase.kt  # MOD — Transfer branch: use template destination or return
│                                      #        typed MissingTransferDestination outcome; write-guard: never persist
│                                      #        fromAccount == toAccount (lines ~239-252 today)
├── domain/usecase/RouteSmsUseCase.kt  # MOD — quarantine on MissingTransferDestination
├── domain/usecase/ResolvePendingItemUseCase.kt        # MOD — accept destination override when resolving
├── domain/usecase/RepairGhostTransfersUseCase.kt      # NEW — one-shot versioned (SharedPrefs/DataStore version key),
│                                      #        runs inside SyncSms mutex: find ghost rows → delete → re-drive
│                                      #        ReprocessHistoricalUseCase for affected senders/date range
├── ui/templates/TemplateMappingScreen.kt   # MOD — "Transfer to (optional)" wallet row when a Transfer role is assigned
├── ui/templates/TemplateMappingViewModel.kt # MOD — hold/persist destination; pass through MapTemplateUseCase
├── domain/usecase/MapTemplateUseCase.kt     # MOD — accept optional destination
├── ui/pending/PendingReviewScreen.kt   # MOD — needs-destination card: From (fixed) / To (picker) / remember checkbox
└── ui/pending/PendingReviewViewModel.kt # MOD — resolve-with-destination event; write-back on remember

# ── Project A: loan payment ledger ──────────────────────────────────────────
shared/data/core/src/main/java/com/ivy/data/
├── db/entity/LoanRecordEntity.kt      # MOD — + loanItemId: UUID? (indexed)
├── db/dao/read/LoanRecordDao.kt       # MOD — + findAllByLoanItemId, sumByLoanItemId, findAllWithItemForLoan
├── db/dao/write/WriteLoanRecordDao.kt # MOD — + deleteAllByLoanItemId (if not present)
└── repository/LoanRepository.kt       # MOD — entry-payment queries (paid sums per entry, records per entry)

feature/loans/src/main/java/com/ivy/loans/
├── loandetails/LoanDetailsViewModel.kt # MOD — paid = records only (drop settledItemsAmount from the sum,
│                                       #        lines ~449/505 today); entry paid/settled derivation; events for
│                                       #        payment sheet, unsettle-confirm, history expand
├── loandetails/LoanDetailsScreenState.kt # MOD — per-entry DisplayLoanItem gains paidAmount/derived-settled/history
├── loandetails/events/LoanDetailsScreenEvent.kt # MOD — OnEntryCircleTap, OnConfirmEntryPayment, OnConfirmUnsettle, …
├── loandetails/LoanDetailsScreen.kt    # MOD — wire payment sheet + unsettle confirm + expanded history
├── loandetails/ui/LoanItemCard.kt      # MOD — states: unsettled / partial (half circle + progress) / settled /
│                                       #        legacy-settled ("settled — no transaction"); expandable payments
├── loandetails/ui/EntryPaymentSheet.kt # NEW — IvyModal + existing AmountModal; pre-filled remaining/wallet/today
├── loandetails/ui/LinkToLoanSheet.kt   # NEW — two-step: pick loan → allocate (auto oldest-first / manual / none)
└── loan/LoanViewModel.kt               # MOD — calculateAmountPaidAndTotalAmount → records-only (lines ~452-465 today)

feature/loans/src/main/java/com/ivy/loans/domain/   # (or module's existing use-case package)
├── PayLoanEntryUseCase.kt             # NEW — validate (0 < amount ≤ remaining, wallet present) → create record
│                                      #        (loanItemId set) + transaction via LoanTransactionsCore; Either result
├── UnsettleLoanEntryUseCase.kt        # NEW — delete entry's records + their transactions (legacy: flag only)
└── LinkTransactionToLoanUseCase.kt    # NEW — infer direction (4-row table) → create record pointing at existing
                                       #        transaction (set loanId/loanRecordId on it); optional entry allocation;
                                       #        NEVER creates a transaction

# Link surfaces outside feature/loans
feature/sms-sync/.../ui/pending/PendingReviewScreen.kt  # MOD (also listed above) — "Link to loan" on resolved/created items
feature/edit-transaction/.../EditTransactionScreen.kt   # MOD (also listed above) — "Link to loan" row on transaction detail
temp/legacy-code/.../loantrasactions/LoanTransactionsCore.kt # MOD — deleting a transaction with loanRecordId also deletes
                                                             #        the record (single-event rule, FR-024) — verify existing
                                                             #        behavior first; extend only if gap confirmed

# ── Tests (mirrors) ─────────────────────────────────────────────────────────
shared/data/core/src/test/.../migration/SmsTransferDestinationMigrationTest.kt  # NEW
shared/data/core/src/test/.../migration/LoanRecordItemLinkMigrationTest.kt      # NEW (incl. legacy backfill idempotence)
feature/sms-sync/src/test/.../RouteSmsUseCaseTest.kt        # MOD — needs-destination quarantine; write-guard
feature/sms-sync/src/test/.../RepairGhostTransfersUseCaseTest.kt # NEW
feature/loans/src/test/.../PayLoanEntryUseCaseTest.kt       # NEW — cap, partial, direction, double-tap safety
feature/loans/src/test/.../LinkTransactionToLoanUseCaseTest.kt # NEW — 4 directions, no-second-transaction, cascade
feature/loans/src/test/.../LoanItemCardPaparazziTest.kt     # MOD — new states goldens
```

**Structure Decision**: No new Gradle module. Each project touches its natural existing module: B lives in `app` + `feature/settings`; C in `feature/sms-sync` + `shared/data/core`; A in `feature/loans` + `shared/data/core`, with the two cross-module link surfaces (pending review card, transaction detail) modified in place. This keeps the dependency graph unchanged except that `feature/loans`' link sheet is reached via events from `feature/sms-sync`/`feature/edit-transaction` hosts using the same hosting pattern `SmsSetupSheet` already established on `MainScreen`/`TransactionsScreen`.

## Phase Summary

- **Phase 0 (research.md)**: All product decisions were pre-made (D1–D8); research resolves the seven *technical* decision points: overlay mechanics & input blocking, lock-delay storage, idempotent `start()` guard, missing-destination as typed outcome vs exception, ghost-repair strategy (reprocess-drive vs synthesize), legacy settled-entry backfill (synthetic records), and direction-inference placement. No NEEDS CLARIFICATION remain.
- **Phase 1 (data-model.md, contracts/, quickstart.md)**: Two migrations specified column-by-column with backfill SQL and idempotence rules; three behavior contracts define what each module promises the others; quickstart gives the owner copy-paste build/test/UAT commands per story (agent never invokes gradle).
- **Phase 2 (tasks.md)**: NOT produced by this command — `/speckit-tasks` will decompose per user story in the fixed order B → C → A.
