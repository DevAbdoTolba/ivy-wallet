# Research: Granular Loan Checklist

## Decisions & Rationale

### Decision 1: Entity Strategy
**Decision**: Create a new `LoanItemEntity` while keeping `LoanRecordEntity` for transaction history.
**Rationale**: The user specifically requested a new `LoanItem` entity for a "checklist system". `LoanRecordEntity` in the current codebase tracks balance changes (DECREASE/INCREASE) and is tied to transactions. Mixing these concerns would violate SRP and add complexity.
**Alternatives considered**: Refactoring `LoanRecordEntity` to be the checklist item. Rejected because it's tightly coupled with the current transaction/legacy logic.

### Decision 2: Migration Strategy
**Decision**: Migration 130 -> 131.
**Rationale**: We need to migrate the `amount` field from `LoanEntity` into the new `loan_items` table as a "Legacy Balance" entry.
**Steps**:
1. Create `loan_items` table.
2. `INSERT INTO loan_items (id, contactId, amount, title, isSettled, createdAt) SELECT lower(hex(randomblob(16))), id, amount, 'Legacy Balance', 0, strftime('%s', 'now') FROM loans WHERE amount != 0;`

### Decision 3: UI Architecture
**Decision**: Observe `Flow<List<LoanItem>>` in `LoanDetailsViewModel`.
**Rationale**: Real-time updates to the balance at the top of the screen require reactive data streams.
**Implementation**: Use `LoanItemDao` to expose a `Flow`, which the `LoanRepository` passes to the `ViewModel`.

## Technical Unknowns Resolved

- **Either Usage**: Project uses `arrow.core.Either` and `either { ... }` blocks in Mappers and Repositories.
- **Main Safety**: `ioThread { ... }` and `computationThread { ... }` wrappers are used in existing ViewModels, though standard Coroutines are preferred for new work if possible (depending on local convention).
- **Existing Models**: `DisplayLoanRecord` exists; we should create `DisplayLoanItem` to avoid confusion.
