# Implementation Plan: Granular Loan Checklist

**Branch**: `001-granular-loan-checklist` | **Date**: 2026-04-23 | **Spec**: [specs/001-granular-loan-checklist/spec.md](spec.md)
**Input**: Feature specification from `/specs/001-granular-loan-checklist/spec.md`

## Summary
Refactor the current single-running-total loan system into a one-to-many checklist system using Room and Jetpack Compose. The technical approach involves creating a new `LoanItem` entity, implementing a Room migration script to preserve legacy data, and refactoring the UI to display a list of items with real-time balance updates.

## Technical Context

**Language/Version**: Kotlin 1.9+, Java 17  
**Primary Dependencies**: Jetpack Compose, Hilt, ArrowKt, Room, Material3  
**Storage**: Room Database (SQLite)  
**Testing**: JUnit4, Kotest, Paparazzi  
**Target Platform**: Android  
**Project Type**: mobile-app  
**Performance Goals**: < 3s balance update, smooth list scrolling  
**Constraints**: Main-safe (Coroutines), Type-safe (Either)  
**Scale/Scope**: ~1-100 loan items per contact

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

1. **Pragmatic Simplicity**: Checklist improves clarity and record-keeping without adding excessive complexity. (PASSED)
2. **Layered Unidirectional Architecture**: Clear separation: Room (Data) -> ViewModel/UseCase (Domain) -> Compose (UI). (PASSED)
3. **Type-Safe Error Handling**: Repository operations will return `Either<Failure, Result>`. (PASSED)
4. **Main-Safe Operations**: Database operations off-loaded to `Dispatchers.IO` via Coroutines. (PASSED)
5. **Testing & Quality Assurance**: New checklist UI will have Paparazzi screenshot tests; logic verified with JUnit. (PASSED)

## Project Structure

### Documentation (this feature)

```text
specs/001-granular-loan-checklist/
├── spec.md              # Feature specification
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
└── quickstart.md        # Phase 1 output
```

### Source Code (repository root)

```text
feature/loans/
├── src/main/java/com/ivy/loans/
│   ├── loan/
│   │   ├── data/
│   │   │   └── DisplayLoanItem.kt
│   │   └── LoanViewModel.kt
│   └── loandetails/
│       ├── ui/
│       │   └── LoanItemCard.kt
│       ├── LoanDetailsScreen.kt
│       └── LoanDetailsViewModel.kt

shared/data/core/src/main/java/com/ivy/data/
├── db/
│   ├── dao/
│   │   ├── read/LoanItemDao.kt
│   │   └── write/WriteLoanItemDao.kt
│   ├── entity/LoanItemEntity.kt
│   └── migration/Migration130to131_LoanChecklist.kt
└── repository/
    └── LoanRepository.kt
```

**Structure Decision**: Integrated into existing `feature/loans` for UI/ViewModel and `shared/data/core` for database/repository, following the project's established modularization.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| None | N/A | N/A |
