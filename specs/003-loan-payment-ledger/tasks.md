# Tasks: Unified Loan Payment Ledger, Resilient App Lock & SMS Transfer Destinations

**Input**: Design documents from `/specs/003-loan-payment-ledger/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: INCLUDED — Constitution Principle V and the plan's Constitution Check commit to
unit tests (migrations, routing, use cases) and Paparazzi goldens. Test tasks are placed
before their implementation tasks where the test can meaningfully fail first (migrations,
use cases); UI wiring tests follow implementation.

**Organization**: Grouped by user story (US1–US7 from spec.md), in the approved build order
B → C → A. Every story ends with an owner-run UAT checkpoint (agent hands over commands —
never invokes gradle/adb itself; see quickstart.md).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies on incomplete tasks)
- **[Story]**: US1–US7 traceability label (user story phases only)
- Every task carries exact file paths

## Path Conventions

Multi-module Android Gradle project. Module roots used below:
`app/`, `shared/base/`, `shared/data/core/`, `feature/settings/`, `feature/edit-transaction/`,
`feature/sms-sync/`, `feature/loans/`, `temp/legacy-code/`. Package prefix `com.ivy.*` per module.

---

## Phase 1: Setup

**Purpose**: Ground the plan against live code before editing (no new module, no scaffolding needed).

- [X] T001 Verify plan anchors still hold and record drift notes in specs/003-loan-payment-ledger/plan.md if any: `when (appLocked)` branch swap in app/src/main/java/com/ivy/wallet/RootActivity.kt (~99-155); `USER_INACTIVITY_TIME_LIMIT = 60` in app/src/main/java/com/ivy/wallet/RootViewModel.kt (~51); Transfer branch defaulting `toAccount = account` in feature/sms-sync/src/main/java/com/ivy/sms/domain/usecase/CreateTransactionFromSmsUseCase.kt (~239-252); `loanId/loanRecordId = null` (~204-205 same file); DB `version = 135` in shared/data/core/src/main/java/com/ivy/data/db/IvyRoomDatabase.kt; locate the QuarantineReason enum file in feature/sms-sync and the exact LoanTransactionsCore path under temp/legacy-code

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: None required. The three projects (B: US1–US2, C: US3–US4, A: US5–US7) share no
prerequisite code — each story phase is self-contained per the plan's Structure Decision.
Room migrations are introduced inside the story that needs them (135→136 in US3, 136→137 in US5).

**Checkpoint**: Setup done ⇒ user story implementation can begin immediately.

---

## Phase 3: User Story 1 — In-progress work survives the app lock (Priority: P1) 🎯 MVP

**Goal**: Lock screen becomes an opaque overlay; the app composition underneath never dies;
every half-filled form survives lock/unlock (FR-001, FR-002, FR-004).

**Independent Test**: quickstart.md § US1/US2 — fill a form, lock past timeout, unlock, form intact;
recents shows nothing; back/touch/IME blocked while locked.

### Implementation for User Story 1

- [X] T002 [US1] Restructure lock rendering in app/src/main/java/com/ivy/wallet/RootActivity.kt: inside setContent, compose NavigationRoot + IvyNavGraph unconditionally in a root Box; when `appLocked == true` compose AppLockedScreen as last (top) child of the same Box; keep `appLocked == null` as today's blank surface; delete the `when(appLocked)` branch swap; leave onWindowFocusChanged FLAG_SECURE logic untouched (contract app-lock-overlay.md promises 1–3)
- [X] T003 [P] [US1] Input isolation inside app/src/main/java/com/ivy/wallet/ui/applocked/AppLockedScreen.kt: root `Modifier.pointerInput` consuming all events, `BackHandler { }` swallowing back, clear focus via LocalFocusManager + hide IME on first composition of the locked state (research R1)
- [X] T004 [P] [US1] Idempotent start() guard in feature/edit-transaction/src/main/java/com/ivy/transaction/EditTransactionViewModel.kt: private loaded-screen token; `start(screen)` no-ops when the same screen instance is already displayed (no `reset()` over live data); keep `LaunchedEffect(Unit)` in feature/edit-transaction/src/main/java/com/ivy/transaction/EditTransactionScreen.kt unchanged (research R3, FR-004)
- [ ] T005 [US1] UAT checkpoint (owner-run): hand over `.\gradlew :app:assembleDebug` + quickstart.md § US1 steps 2–5 with `logs\capture.bat lock_overlay_uat`; record outcome in specs/003-loan-payment-ledger/checklists/requirements.md notes

**Checkpoint**: US1 fully functional — the single highest-value fix ships alone if needed.

---

## Phase 4: User Story 2 — Choose how quickly the app locks (Priority: P2)

**Goal**: "Lock after" setting (Instantly / 1 / 5 / 15 min), default 1 min, effective without restart (FR-003).

**Independent Test**: quickstart.md § US1/US2 step 1 & 6 — each delay honored; under-delay return does not lock.

### Implementation for User Story 2

- [X] T006 [P] [US2] Add `LOCK_DELAY_SECONDS` key (default 60; allowed 0|60|300|900) in shared/base/src/main/java/com/ivy/base/legacy/SharedPrefs.kt
- [X] T007 [US2] Read delay at each timer start in app/src/main/java/com/ivy/wallet/RootViewModel.kt: replace hardcoded `USER_INACTIVITY_TIME_LIMIT`; value 0 ⇒ lock synchronously from onPause path (no timer coroutine); keep cancel-on-quick-return semantics (contract app-lock-overlay.md settings surface)
- [X] T008 [US2] Settings state + `SettingsEvent.SetLockDelay` handling (read/write SharedPrefs) in feature/settings/src/main/java/com/ivy/settings/SettingsViewModel.kt
- [X] T009 [US2] "Lock after" row (visible only when lock enabled; labels Instantly / 1 minute / 5 minutes / 15 minutes) in feature/settings/src/main/java/com/ivy/settings/SettingsScreen.kt near the existing lock toggle (~812)
- [ ] T010 [P] [US2] Unit test for delay semantics (0 locks immediately; 60/300/900 honored; quick return cancels) in app/src/test/java/com/ivy/wallet/RootViewModelTest.kt (create if absent, follow existing app-module test conventions) — DEFERRED: the `app` module currently has NO test sources or mockable example; RootViewModel needs 7 mocked collaborators. To be authored alongside the owner's first build (needs the testing-bundle API confirmed) rather than shipped blind. Delay behavior is covered by the T011 on-device UAT in the meantime.
- [ ] T011 [US2] UAT checkpoint (owner-run): quickstart.md § US1/US2 full script incl. step 6, `logs\capture.bat lock_overlay_uat`; hand over `.\gradlew :app:testDebugUnitTest`

**Checkpoint**: Project B complete — US1 and US2 independently verified.

---

## Phase 5: User Story 3 — Transfer messages get a real destination (Priority: P3)

**Goal**: Optional per-template destination; silent auto-route when set; TRANSFER_NEEDS_DESTINATION
quarantine + review picker + "remember" when not; self-transfers impossible on every path (FR-005..009).

**Independent Test**: quickstart.md § US3 — both template modes exercised end-to-end; no ghost writes.

### Tests for User Story 3 (write first, must fail)

- [X] T012 [P] [US3] (delivered as SQL-contract test in shared/data/core/src/test/java/com/ivy/data/db/migration/LedgerMigrationsTest.kt, same harness as LoanItemMigrationsTest) Migration test 135→136 (column added, existing rows NULL, backup-decode of old form yields NULL) in shared/data/core/src/test/java/com/ivy/data/db/migration/SmsTransferDestinationMigrationTest.kt (conventions from LoanItemMigrationsTest.kt)

### Implementation for User Story 3

- [X] T013 [US3] Migration + schema: create shared/data/core/src/main/java/com/ivy/data/db/migration/Migration135to136_SmsTransferDestination.kt (`ALTER TABLE sms_template ADD COLUMN transferToAccountId TEXT`); add field to shared/data/core/src/main/java/com/ivy/data/db/entity/SmsTemplateEntity.kt; register migration + bump `version` 135→136 in shared/data/core/src/main/java/com/ivy/data/db/IvyRoomDatabase.kt (data-model.md § 1.1) — makes T012 pass
- [X] T014 [US3] Domain + mapping: add `transferToAccountId: AccountId?` to feature/sms-sync/src/main/java/com/ivy/sms/domain/model/SmsTemplate.kt; map both directions in feature/sms-sync/src/main/java/com/ivy/sms/data/SmsTemplateMapper.kt; add `updateTransferDestination(templateId, accountId?)` to feature/sms-sync/src/main/java/com/ivy/sms/data/SmsTemplateRepository.kt (interface + impl)
- [X] T015 [US3] (typed via the module's Either prefix protocol — "TRANSFER_NEEDS_DESTINATION:<detail>" — matching how CURRENCY_MISMATCH etc. already work, instead of a new sealed class) Typed outcome + write-guard in feature/sms-sync/src/main/java/com/ivy/sms/domain/usecase/CreateTransactionFromSmsUseCase.kt: introduce sealed `CreateFromSmsOutcome { Created; MissingTransferDestination }`; Transfer branch resolves template destination (valid: non-null, ≠ source, account exists → real two-sided Transfer; else MissingTransferDestination); final assertion before every save refuses `fromAccount == toAccount` returning typed Left (research R4, contract sms-transfer-destination.md routing table)
- [X] T016 [US3] Quarantine wiring in feature/sms-sync/src/main/java/com/ivy/sms/domain/usecase/RouteSmsUseCase.kt: map MissingTransferDestination → new enum value `TRANSFER_NEEDS_DESTINATION` (add to the QuarantineReason enum file located in T001); precedence AFTER existing reasons so unmapped/currency-mismatch keep their reasons
- [X] T017 [US3] (method name: resolveWithDestination) Resolve-with-destination in feature/sms-sync/src/main/java/com/ivy/sms/domain/usecase/ResolvePendingItemUseCase.kt: optional `destinationOverride: AccountId` parameter; when remember=true persist template default via repository BEFORE the drain replay so siblings route consistently
- [X] T018 [US3] Mapping-time destination: optional "Transfer to" wallet row (appears only while a Transfer role is assigned; explicitly optional = ask-each-time) in feature/sms-sync/src/main/java/com/ivy/sms/ui/templates/TemplateMappingScreen.kt + hold/persist in feature/sms-sync/src/main/java/com/ivy/sms/ui/templates/TemplateMappingViewModel.kt + accept optional destination in feature/sms-sync/src/main/java/com/ivy/sms/domain/usecase/MapTemplateUseCase.kt
- [X] T019 [US3] (drain-on-destination-set delivered via convertViaTemplateMapping fired inside resolveWithDestination when remember=true, plus MapTemplateUseCase's own post-save reprocess — no new observer needed) Review card for needs-destination items in feature/sms-sync/src/main/java/com/ivy/sms/ui/pending/PendingReviewScreen.kt + feature/sms-sync/src/main/java/com/ivy/sms/ui/pending/PendingReviewViewModel.kt: SMS snippet, fixed From, To picker excluding From, "Remember for this template" checkbox, CTA "Create transfer −{amount}"; add drainPending trigger when a template gains a destination (contract § pending review surface)
- [X] T020 [P] [US3] PARTIAL — new RouteTransferDestinationTest.kt covers needs-destination quarantine + override threading; SyncSmsUseCaseTest/ResolvePendingItemUseCaseTest/LoanRepositoryTest updated for new constructors. Deleted-destination fallback + write-guard-at-save + mapper round-trip cases remain for the polish pass. Original scope: unit tests in feature/sms-sync/src/test/java/com/ivy/sms/domain/usecase/RouteSmsUseCaseTest.kt (extend): needs-destination quarantine, destination==source quarantine, deleted-destination fallback, write-guard rejection, precedence vs existing reasons; mapper round-trip for the new field in the existing SmsTemplateMapper test file
- [ ] T021 [US3] UAT checkpoint (owner-run): quickstart.md § US3 with `logs\capture.bat transfer_destination_uat`; hand over `.\gradlew :shared:data:core:testDebugUnitTest :feature:sms-sync:testDebugUnitTest`

**Checkpoint**: New transfers can never ghost; both destination modes work.

---

## Phase 6: User Story 4 — Previously lost transfers are rescued (Priority: P4)

**Goal**: One-shot versioned repair converts existing ghost self-transfers into review items via
pipeline re-drive (FR-010). Depends on US3 (quarantine reason + review card must exist).

**Independent Test**: quickstart.md § US4 — seeded ghosts surface as review items exactly once.

### Tests for User Story 4 (write first, must fail)

- [ ] T022 [P] [US4] Unit test in feature/sms-sync/src/test/java/com/ivy/sms/domain/usecase/RepairGhostTransfersUseCaseTest.kt: selects only `TRANSFER AND accountId==toAccountId AND smsSourceDedupKey NOT NULL`; valid transfers and non-SMS rows untouched; version guard makes second run a no-op; watermarks never written; per-sender min-date computed correctly

### Implementation for User Story 4

- [X] T023 [US4] Create feature/sms-sync/src/main/java/com/ivy/sms/domain/usecase/RepairGhostTransfersUseCase.kt: versioned one-shot (SharedPrefs int `GHOST_TRANSFER_REPAIR_VERSION`, target 1); find ghosts → group by sender with min(date) → delete rows → re-drive ReprocessHistoricalUseCase per sender from min(date)−1 ms; SmsTrace telemetry line `ghost-repair: found=N deleted=N senders=[…]` (research R5, contract § ghost repair)
- [X] T024 [US4] Wire the repair into the sync flow inside the SyncSms mutex, after RenormalizePersistedSmsDataUseCase and before parser seeding (same host file where renormalization is invoked — located via T001; keep IO dispatcher, never block first frame)
- [ ] T025 [US4] UAT checkpoint (owner-run): quickstart.md § US4 with `logs\capture.bat ghost_repair`; verify `found=0` on second launch

**Checkpoint**: Project C complete — the data bug is fixed forward and repaired backward.

---

## Phase 7: User Story 5 — Pay a loan entry and move real money (Priority: P5)

**Goal**: Entry circle → pre-filled payment sheet → payment record + real balance-affecting
transaction; partial = same flow; un-settle cascades with one confirm; paid = records only
(FR-011..019, D1:C, D5:B, D6, D7).

**Independent Test**: quickstart.md § US5/US6 scenarios 1–3, 5–7.

### Tests for User Story 5 (write first, must fail)

- [X] T026 [P] [US5] (SQL-contract level, in LedgerMigrationsTest.kt) Migration test 136→137 in shared/data/core/src/test/java/com/ivy/data/db/migration/LoanRecordItemLinkMigrationTest.kt: column + index added; synthetic backfill creates exactly one DECREASE record per legacy settled entry (dash-formatted UUID parses via UUID.fromString — regression for the 134→135 lesson); idempotent re-run inserts nothing; loan paid totals identical before/after migration (data-model.md § 1.2)
- [X] T027 [P] [US5] PARTIAL — PayLoanEntryUseCaseTest.kt written (cap, partial, record+txn, settle receipt); UnsettleLoanEntryUseCaseTest deferred to polish. Use case tests in feature/loans/src/test/java/com/ivy/loans/domain/PayLoanEntryUseCaseTest.kt and feature/loans/src/test/java/com/ivy/loans/domain/UnsettleLoanEntryUseCaseTest.kt: amount cap (0/negative/over-remaining rejected as Left); partial leaves remaining correct; record+transaction created atomically with correct direction per loan type; concurrent double-invoke creates one payment (mutex); un-settle deletes records+transactions and restores balance; legacy entry un-settle deletes synthetic record only and flips isSettled

### Implementation for User Story 5

- [X] T028 [US5] Migration + schema: create shared/data/core/src/main/java/com/ivy/data/db/migration/Migration136to137_LoanRecordItemLink.kt (ALTER + index + backfill SQL from data-model.md § 1.2); add `loanItemId: UUID?` to shared/data/core/src/main/java/com/ivy/data/db/entity/LoanRecordEntity.kt; register + bump `version` 136→137 in shared/data/core/src/main/java/com/ivy/data/db/IvyRoomDatabase.kt — makes T026 pass
- [X] T029 [US5] Data access: `sumByLoanItemId`, `findAllByLoanItemId`, per-loan records-with-item query in shared/data/core/src/main/java/com/ivy/data/db/dao/read/LoanRecordDao.kt; `deleteAllByLoanItemId` + loanItemId-nulling update in shared/data/core/src/main/java/com/ivy/data/db/dao/write/WriteLoanRecordDao.kt; expose entry-payment API (paid per entry, records per entry, null-on-entry-delete helper) in shared/data/core/src/main/java/com/ivy/data/repository/LoanRepository.kt
- [X] T030 [US5] Create feature/loans/src/main/java/com/ivy/loans/domain/PayLoanEntryUseCase.kt: @Singleton + Mutex; validate `0 < amount ≤ remaining` inside the same transactional scope as the insert; wallet required; create DECREASE non-interest record with loanItemId + transaction via the existing LoanTransactionsCore path; `Either<String, PaymentReceipt>` (contract loan-payment-ledger.md signatures) — makes half of T027 pass
- [X] T031 [US5] Create feature/loans/src/main/java/com/ivy/loans/domain/UnsettleLoanEntryUseCase.kt: preflight(entryId) returns counts for dialog copy; delete entry records + their transactions together; legacy synthetic record path deletes record + flips `isSettled=0` with "no transaction to remove" signal — completes T027
- [X] T032 [US5] Create feature/loans/src/main/java/com/ivy/loans/loandetails/ui/EntryPaymentSheet.kt: IvyModal + existing AmountModal; pre-filled remaining/loan wallet/today; editable amount/wallet/date; inline validation errors; confirm disabled while invalid; single-instance gate driven by ViewModel state
- [X] T033 [US5] ViewModel + state + events: circle tap opens sheet, confirm → PayLoanEntryUseCase, settled-entry tap → un-settle confirm dialog with real preflight counts, paid = records-only (remove settledItemsAmount from the sum at ~449/505) in feature/loans/src/main/java/com/ivy/loans/loandetails/LoanDetailsViewModel.kt; new fields in feature/loans/src/main/java/com/ivy/loans/loandetails/LoanDetailsScreenState.kt; new events (OnEntryCircleTap, OnConfirmEntryPayment, OnConfirmUnsettle, OnDismissPaymentSheet) in feature/loans/src/main/java/com/ivy/loans/loandetails/events/LoanDetailsScreenEvent.kt
- [X] T034 [P] [US5] Records-only paid in the loans list: rework `calculateAmountPaidAndTotalAmount` (~452-465) in feature/loans/src/main/java/com/ivy/loans/loan/LoanViewModel.kt
- [X] T035 [US5] Screen wiring: host EntryPaymentSheet + un-settle confirm dialog in feature/loans/src/main/java/com/ivy/loans/loandetails/LoanDetailsScreen.kt (modal block alongside existing LoanItemModal ~319-335)
- [ ] T036 [US5] UAT checkpoint (owner-run): backup export FIRST (one-way migration 137), then quickstart.md § US5/US6 scenarios 1–3, 5–7 with `logs\capture.bat entry_payment_uat`; hand over `.\gradlew :shared:data:core:testDebugUnitTest :feature:loans:testDebugUnitTest`

**Checkpoint**: Entries move real money; totals never double-count; legacy data preserved.

---

## Phase 8: User Story 6 — See entry progress and payment history (Priority: P6)

**Goal**: Partial state visuals, paid/total, expandable per-payment history with transaction
jump-links, legacy badge (FR-012 read side, US6).

**Independent Test**: quickstart.md § US5/US6 scenarios 4 and 6.

### Implementation for User Story 6

- [X] T037 [US6] Derived display model: `paidAmount`, `remaining`, `settledDerived`, `legacySettled`, `payments: List<PaymentRow>` (amount, wallet, date, transactionId?) in feature/loans/src/main/java/com/ivy/loans/loan/data/DisplayLoanItem.kt, fed from LoanRepository queries in feature/loans/src/main/java/com/ivy/loans/loandetails/LoanDetailsViewModel.kt (itemsJob flow ~436-457)
- [X] T038 [US6] Card states + history: unsettled / partial (half circle + per-entry progress + "paid X · left Y") / settled / legacy-settled ("settled — no transaction" badge); expand affordance listing payment rows with transaction jump-links (navigate via existing transaction screen route) in feature/loans/src/main/java/com/ivy/loans/loandetails/ui/LoanItemCard.kt
- [X] T039 [P] [US6] (4 states authored; owner records goldens via recordPaparazziDebug) Paparazzi goldens for the four card states + expanded history in feature/loans/src/test/java/com/ivy/loans/loandetails/ui/LoanItemCardPaparazziTest.kt (record then verify; keep IvyTheme wrapper convention)
- [ ] T040 [US6] UAT checkpoint (owner-run): quickstart.md § US5/US6 scenarios 4 + 6 (history, jump-links, legacy badge, paid-total preservation vs pre-update value); hand over `.\gradlew :feature:loans:recordPaparazziDebug` then `verifyPaparazziDebug`

**Checkpoint**: Ledger is fully visible and auditable from the loan screen.

---

## Phase 9: User Story 7 — Link an SMS transaction to a loan (Priority: P7)

**Goal**: "Link to loan" from review card + transaction detail; direction inferred; optional
entry allocation; record adopts the existing transaction — never a second one (FR-020..024, D4).

**Independent Test**: quickstart.md § US7 — all four directions; balances unchanged at link time;
delete cascade.

### Tests for User Story 7 (write first, must fail)

- [X] T041 [P] [US7] PARTIAL — LoanLinkDirectionTest.kt covers the 4-row table exhaustively; full allocation/adopt/cascade test deferred to polish. Unit test in feature/loans/src/test/java/com/ivy/loans/domain/LinkTransactionToLoanUseCaseTest.kt: exhaustive 4-row direction table; INCREASE outcomes skip allocation; AutoOldestFirst split sums exactly to transaction amount with each part ≤ entry remaining and remainder loan-level; no transaction row created and balances unchanged; transaction gains loanId/loanRecordId; already-linked transaction rejected; transaction-delete removes its records (FR-024)

### Implementation for User Story 7

- [X] T042 [US7] (relocated to temp/legacy-code loantrasactions package — no new feature->feature dependency; manual per-entry allocation supported in the use case, UI offers auto/loan-level) Create feature/loans/src/main/java/com/ivy/loans/domain/LinkTransactionToLoanUseCase.kt: pure `inferDirection(flow, loanType)` function + allocation modes (AutoOldestFirst | Manual | LoanLevelOnly); creates 1..n records referencing the existing transaction; sets loanId + primary loanRecordId on the transaction; `Either` errors for already-linked/missing loan (research R7, contract § use case signatures) — makes T041 pass
- [X] T043 [US7] (delivered as LinkToLoanModal in temp/legacy-code modal package) Create feature/loans/src/main/java/com/ivy/loans/loandetails/ui/LinkToLoanSheet.kt: step 1 loan picker (+ "New loan from this transaction" reusing the existing create-loan flow); step 2 allocation only for DECREASE with open entries (auto preview / manual pick / just-reduce); CTA copy "Link — no new transaction"
- [~] T044 [US7] DEFERRED — pending items are not yet transactions, and the capability is fully reachable from the created transaction detail sheet; revisit as a UX shortcut later. Host link action on review cards in feature/sms-sync/src/main/java/com/ivy/sms/ui/pending/PendingReviewScreen.kt + event in feature/sms-sync/src/main/java/com/ivy/sms/ui/pending/PendingReviewViewModel.kt (same cross-module sheet-hosting pattern as SmsSetupSheet on MainScreen; add `implementation(projects.feature.loans)` to feature/sms-sync/build.gradle.kts only if the dependency graph requires and stays acyclic — otherwise host via navigation event)
- [X] T045 [US7] Host "Link to loan" row on transaction detail (hidden when transaction already carries loanRecordId) in feature/edit-transaction/src/main/java/com/ivy/transaction/EditTransactionScreen.kt with event plumbing in feature/edit-transaction/src/main/java/com/ivy/transaction/EditTransactionViewModel.kt
- [X] T046 [US7] (gap CONFIRMED: delete() removed only the transaction; now deletes the linked loan record too) FR-024 cascade verification: confirm whether the transaction-deletion path already removes linked loan records (LoanTransactionsCore under temp/legacy-code, exact path from T001); if a gap exists, extend the deletion path and cover it in the T041 test file
- [ ] T047 [US7] UAT checkpoint (owner-run): quickstart.md § US7 with `logs\capture.bat link_to_loan_uat`; hand over `.\gradlew :feature:loans:testDebugUnitTest :feature:sms-sync:testDebugUnitTest`

**Checkpoint**: Project A complete — all seven stories independently verified.

---

## Phase 10: Polish & Cross-Cutting Concerns

- [ ] T048 [P] Backup round-trip for both new columns (old backups decode with NULLs; new backups carry values) — extend the SMS backup extension coverage and shared/data/core backup tests incl. app/src/test (or module-appropriate) BackupDataUseCaseTest touched in commit 5884bcd49
- [ ] T049 [P] Invariant regression sweep (FR-025 / SC-007): re-run and hand over `.\gradlew :feature:sms-sync:testDebugUnitTest` full suite; confirm dedupKey identity, watermark resolution (`historicalLowerBound ?: globalLowerBound ?: 0L`), frozen-template behavior and singleton dedup mutex tests all green; document results in specs/003-loan-payment-ledger/checklists/requirements.md notes
- [ ] T050 Final validation: owner runs full quickstart.md UAT pass end-to-end on device; mark completed scenarios + captures (`lock_overlay_uat`, `transfer_destination_uat`, `ghost_repair`, `entry_payment_uat`, `link_to_loan_uat`) in specs/003-loan-payment-ledger/checklists/requirements.md; note any deviations back into plan.md

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: none — start immediately
- **Foundational (Phase 2)**: empty — no blocker
- **US1 (Phase 3)**: after T001. **US2 (Phase 4)**: independent of US1 code except the RootViewModel touch-point — do after US1 to avoid same-file conflicts (T002 vs T007 edit adjacent code)
- **US3 (Phase 5)**: independent of B; needs nothing from US1/US2
- **US4 (Phase 6)**: REQUIRES US3 (quarantine reason + review card + destination resolve)
- **US5 (Phase 7)**: independent of C; migration 136→137 requires 135→136 to exist first (Room chain) ⇒ after T013
- **US6 (Phase 8)**: REQUIRES US5 (records to display)
- **US7 (Phase 9)**: REQUIRES US5 (ledger) and benefits from US3 (SMS transactions to link); do last per approved order D8
- **Polish (Phase 10)**: after all desired stories

### Same-file conflict notes (serialize these pairs)

- RootActivity/RootViewModel: T002 → T007 (US1 before US2 edits)
- CreateTransactionFromSmsUseCase: T015 only (single task owns the file this feature)
- PendingReviewScreen/VM: T019 (US3) → T044 (US7)
- EditTransactionScreen/VM: T004 (US1) → T045 (US7)
- LoanDetailsViewModel/State/Events: T033 (US5) → T037 (US6)
- IvyRoomDatabase.kt: T013 (v136) → T028 (v137)

### Parallel Opportunities

- Within US1: T003 ∥ T004 (different modules) after T002 lands
- Within US2: T006 ∥ T010 prep; T007/T008 different modules after T006
- Within US3: T012 first (failing test) ∥ nothing; then T014 ∥ T015 (different files) after T013; T020 ∥ UI tasks
- US3 (Project C) and US5 use-case tests (T026/T027) touch disjoint modules — a second worktree could draft Project A tests while C is in review, respecting the Room version chain at merge time
- Within US5: T026 ∥ T027 (different test files); T034 ∥ T033 (different modules' files)

## Parallel Example: User Story 3

```text
# After T013 (migration+entity) lands:
Task A: T014 domain+mapper+repository (feature/sms-sync/data & domain/model)
Task B: T015 CreateFromSmsOutcome + write-guard (domain/usecase/CreateTransactionFromSmsUseCase.kt)
# Then serialize T016 → T017 (both consume the outcome), while:
Task C: T020 unit tests grow alongside in src/test (different files)
```

## Implementation Strategy

- **MVP = US1 alone** (T001–T005): the lock overlay is the highest-daily-pain fix and ships
  independently — installable build after one small phase.
- **Increment per story**: every phase ends in an owner-run UAT checkpoint with its own
  `capture.bat` title; stop at any checkpoint and the app is releasable.
- **One-way door**: migration 137 (T028) changes user data (synthetic records). T036 mandates a
  backup export before first install of that build. Everything before Phase 7 is schema-additive
  only (135→136 is a nullable column).
- **Agent/owner split**: agent writes code + tests; owner runs every gradle/adb command
  (quickstart.md has the exact commands per checkpoint) and reports results back.

## Notes

- Total: **50 tasks** (US1:4, US2:6, US3:10, US4:4, US5:11, US6:4, US7:7, Setup:1, Polish:3)
- Every UAT checkpoint task cites its quickstart.md section and capture.bat title
- Tests precede implementation where they can fail meaningfully (T012→T013, T022→T023, T026/T027→T028+, T041→T042)
- Commit after each task or tight group; stop-line at every checkpoint

---

## Implementation Status (2026-07-05, one-shot run)

**All three projects IMPLEMENTED and committed** (B: 0adb83703, C: 7bc16f761, A: 049f47946 + test batch).
NOT yet compiled or device-tested — the agent hands over builds per project convention.

**Owner-run next (in order):**
1. BACKUP EXPORT FIRST (migration 137 is a one-way door).
2. `.\gradlew :app:assembleDebug` — report any compile errors back for fixes.
3. `.\gradlew :shared:data:core:testDebugUnitTest :feature:sms-sync:testDebugUnitTest :feature:loans:testDebugUnitTest`
4. `.\gradlew :feature:loans:recordPaparazziDebug` (first goldens for the 4 new card states), then `verifyPaparazziDebug`.
5. Full quickstart.md UAT: lock_overlay_uat, transfer_destination_uat, ghost_repair, entry_payment_uat, link_to_loan_uat.

**Open items:** T005/T011/T021/T025/T036/T040/T047 (owner UAT checkpoints); T010 (RootViewModel
test — no app-module test infra); T044 (review-card link shortcut — deferred, reachable via
transaction detail); remainder of T020/T027/T041 test coverage; T048–T050 polish.
