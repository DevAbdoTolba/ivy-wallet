# Tasks: Granular Loan Checklist

**Input**: Design documents from `specs/001-granular-loan-checklist/`
**Prerequisites**: plan.md (required), spec.md (required)

**Tests**: Included as per requirement (JUnit, Paparazzi requested in plan.md).

**Organization**: Tasks are grouped by user story to enable independent implementation and testing.

## Phase 1: Setup

**Purpose**: Initial structure and boilerplate

- [ ] T001 [P] Create `DisplayLoanItem.kt` in `feature/loans/src/main/java/com/ivy/loans/loan/data/`
- [ ] T002 [P] Create `LoanItemEntity.kt` in `shared/data/core/src/main/java/com/ivy/data/db/entity/`
- [ ] T003 [P] Create `LoanItemDao.kt` in `shared/data/core/src/main/java/com/ivy/data/db/dao/read/`
- [ ] T004 [P] Create `WriteLoanItemDao.kt` in `shared/data/core/src/main/java/com/ivy/data/db/dao/write/`

---

## Phase 2: Foundational

**Purpose**: Blocking database and repository prerequisites

- [ ] T005 Register `LoanItemDao` and `WriteLoanItemDao` in `shared/data/core/src/main/java/com/ivy/data/db/IvyRoomDatabase.kt`
- [ ] T006 Configure Hilt for new DAOs in `shared/data/core/src/main/java/com/ivy/data/di/RoomDbModule.kt`
- [ ] T007 Implement `Migration130to131_LoanChecklist.kt` in `shared/data/core/src/main/java/com/ivy/data/db/migration/`
- [ ] T008 Update `IvyRoomDatabase.kt` to include migration 130 to 131
- [ ] T009 Create `LoanRepository.kt` (if not exists) in `shared/data/core/src/main/java/com/ivy/data/repository/` with basic CRUD for `LoanItem`

**Checkpoint**: Database migrated and repository accessible

---

## Phase 3: User Story 1 - Viewing and Managing Loan Items (Priority: P1) 🎯 MVP

**Goal**: View migrated "Legacy Balance" and toggle settlement

**Independent Test**: View loan details, see "Legacy Balance", toggle checkbox, see total update.

### Tests for User Story 1

- [ ] T010 [P] [US1] Create JUnit test for `LoanItemDao` checklist logic in `shared/data/core/src/test/`
- [ ] T011 [P] [US1] Create Paparazzi test for `LoanItemCard.kt`

### Implementation for User Story 1

- [ ] T012 [P] [US1] Create `LoanItemCard.kt` in `feature/loans/src/main/java/com/ivy/loans/loandetails/ui/`
- [ ] T013 [US1] Update `LoanRepository.kt` to provide `Flow<List<LoanItem>>`
- [ ] T014 [US1] Refactor `LoanDetailsViewModel.kt` to observe `LoanItem` flow and calculate unsettled balance
- [ ] T015 [US1] Refactor `LoanDetailsScreen.kt` to display `LoanItemCard`s in a `LazyColumn`
- [ ] T016 [US1] Implement toggle settlement logic in `LoanDetailsViewModel.kt`

**Checkpoint**: US1 functional - legacy data viewable and toggleable

---

## Phase 4: User Story 2 - Adding New Loan Items (Priority: P2)

**Goal**: Add new specific items via FAB

**Independent Test**: Click FAB, enter "Coffee" and "5.00", see new item in list and balance increase.

### Implementation for User Story 2

- [ ] T017 [US2] Add "Add Loan Item" event to `LoanDetailsScreenEvent.kt`
- [ ] T018 [US2] Implement FAB in `LoanDetailsScreen.kt`
- [ ] T019 [US2] Create "Add Loan Item" modal/dialog UI
- [ ] T020 [US2] Implement `saveLoanItem` logic in `LoanDetailsViewModel.kt` with title validation (> 0 chars) and amount validation (> 0)

**Checkpoint**: US2 functional - can add new granular items

---

## Phase 5: User Story 3 - Historical Records (Priority: P3)

**Goal**: Edit and Delete items

**Independent Test**: Edit "Coffee" to "Latte", verify update. Delete "Latte", verify balance update.

### Implementation for User Story 3

- [ ] T021 [US3] Add Edit/Delete actions to `LoanItemCard.kt`
- [ ] T022 [US3] Implement `deleteLoanItem` logic in `LoanDetailsViewModel.kt`
- [ ] T023 [US3] Implement Edit modal and `updateLoanItem` logic in `LoanDetailsViewModel.kt`

**Checkpoint**: US3 functional - full management of checklist items

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T024 Ensure `LoanItem`s are preserved when contact is moved to "completed" section (Logic verification)

---

## Dependencies & Execution Order

1. **Setup (Phase 1)**: Parallelizable initialization.
2. **Foundational (Phase 2)**: Depends on Phase 1. BLOCKS all user stories.
3. **US1 (Phase 3)**: Depends on Phase 2. The core MVP.
4. **US2 & US3 (Phase 4 & 5)**: Depend on US1. Can be done sequentially.

## Parallel Execution Examples

```bash
# Setup tasks can run together:
Task: T001, T002, T003, T004

# US1 Tests can run together:
Task: T010, T011
```

## Implementation Strategy

- **MVP First**: Complete Phases 1, 2, and 3 first. This delivers the core "Checklist" view and settlement toggle using migrated legacy data.
- **Incremental Delivery**: Add US2 (Add) and then US3 (Edit/Delete) as separate increments.
