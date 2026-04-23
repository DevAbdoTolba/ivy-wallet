# Quickstart: Granular Loan Checklist

## Prerequisites
- Room Database (Version 130)
- Existing `loans` table.

## Implementation Steps

### 1. Database (Data Layer)
- [ ] Create `LoanItemEntity` in `shared/data/core/src/main/java/com/ivy/data/db/entity/`.
- [ ] Create `LoanItemDao` (Read) and `WriteLoanItemDao` (Write) in `shared/data/core/src/main/java/com/ivy/data/db/dao/`.
- [ ] Register new DAOs in `IvyRoomDatabase.kt` and `RoomDbModule.kt`.
- [ ] Implement `Migration130to131_LoanChecklist.kt`.

### 2. Repository (Domain Layer)
- [ ] Implement `LoanRepository.kt` in `shared/data/core/src/main/java/com/ivy/data/repository/` (or update if exists).
- [ ] Add `getLoanItems(loanId: UUID): Flow<List<LoanItem>>`.
- [ ] Add `saveLoanItem(item: LoanItem): Either<Failure, Unit>`.
- [ ] Add `deleteLoanItem(id: UUID): Either<Failure, Unit>`.

### 3. ViewModel (Presentation Layer)
- [ ] Update `LoanDetailsViewModel` to observe `getLoanItems`.
- [ ] Implement logic to calculate unsettled balance from the flow.
- [ ] Add events for Toggle, Add, Edit, and Delete.

### 4. UI (UI Layer)
- [ ] Implement `LoanItemCard` in `feature/loans/src/main/java/com/ivy/loans/loandetails/ui/`.
- [ ] Refactor `LoanDetailsScreen` to use `LazyColumn` for items.
- [ ] Add FAB and "Add/Edit Loan Item" modal.
- [ ] Add Paparazzi tests for the new UI states.
