# Data Model: Granular Loan Checklist

## Entities

### LoanItemEntity
Represents an individual item within a person's loan checklist.

| Field | Type | Description |
|-------|------|-------------|
| id | UUID (PK) | Unique identifier for the loan item. |
| contactId | UUID (FK) | Reference to the `loans` table. |
| amount | Double | Monetary value (must be > 0). |
| title | String | Non-empty description of the item. |
| isSettled | Boolean | Settlement status (default: false). |
| createdAt | Instant | Timestamp of creation. |

## Relationships
- **LoanEntity (1) <-> LoanItemEntity (N)**: Each person (LoanEntity) can have multiple loan items.

## Database Changes
- **Table**: `loan_items`
- **Constraints**: 
  - `amount > 0`
  - `title NOT NULL`
  - `contactId` NOT NULL (Foreign Key with CASCADE DELETE for historical preservation via "completed" logic).
- **Migration**: 130 -> 131
  - Create table `loan_items`.
  - Migrate non-zero `loans.amount` to `loan_items` as 'Legacy Balance'.
