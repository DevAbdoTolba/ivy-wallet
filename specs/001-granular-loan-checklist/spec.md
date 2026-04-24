# Feature Specification: Granular Loan Checklist

**Feature Branch**: `001-granular-loan-checklist`  
**Created**: 2026-04-23  
**Status**: Completed  
**Input**: User description: "refactor single running total per person into a one-to-many checklist system"

## Clarifications

### Session 2026-04-23
- Q: Item Management (Edit/Delete) → A: Allow both editing and deletion.
- Q: Item Uniqueness (Duplicate Titles) → A: Allow duplicates for the same contact.
- Q: Amount Validation → A: Block zero and negative amounts (must be > 0); apply same logic to borrow sections.
- Q: Contact Deletion/Completion → A: Preserve items for history, leveraging existing "completed" loan logic.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Viewing and Managing Loan Items (Priority: P1)

As a user, I want to see a detailed list of individual items within a person's loan so I can track exactly what the debt is for.

**Why this priority**: Core requirement of the refactor from a single total to a granular list.

**Independent Test**: Can view the list of items for a contact and see the active balance update as items are toggled.

**Acceptance Scenarios**:

1. **Given** a contact with a "Legacy Balance", **When** I view their loan details, **Then** I see one item titled "Legacy Balance" with the original amount.
2. **Given** a list of loan items, **When** I toggle an item's settlement status, **Then** the total unsettled balance at the top of the screen updates immediately.

---

### User Story 2 - Adding New Loan Items (Priority: P2)

As a user, I want to add new specific items to a person's loan checklist so I can track new debts as they occur.

**Why this priority**: Essential for the "checklist system" functionality beyond just viewing legacy data.

**Independent Test**: Can use the FAB to add a new item and see it appear in the list and affect the total balance.

**Acceptance Scenarios**:

1. **Given** I am on the loan details screen, **When** I click the FAB and enter a title and amount, **Then** a new unsettled loan item is created for that contact.

---

### User Story 3 - Historical Records (Priority: P3)

As a user, I want to keep settled items in the list so I have a history of what has been paid.

**Why this priority**: Important for financial tracking but secondary to active debt management.

**Independent Test**: Settled items remain in the list but are visually distinct (e.g., checked) and do not count toward the active balance.

**Acceptance Scenarios**:

1. **Given** a settled loan item, **When** I view the list, **Then** it is marked as settled and excluded from the sum of active debt.

### Edge Cases

- **Zero/Negative Amounts**: Blocked. System validates that amount is > 0.
- **Contact Deletion**: Items are preserved for history, leveraging existing "completed" loan logic.
- **Empty Titles**: Blocked. System requires a non-empty title for all loan items.

## Requirements *(mandatory)*

### Functional Requirements

- **FR-001**: System MUST support a one-to-many relationship between Contacts and Loan Items.
- **FR-002**: System MUST migrate existing single-total loan balances into a `LoanItem` titled "Legacy Balance" during the first run.
- **FR-003**: System MUST display a list of all loan items for a selected contact, sorted by creation date.
- **FR-004**: System MUST allow toggling the settlement status of any individual loan item.
- **FR-005**: System MUST calculate and display the sum of all unsettled (`isSettled = false`) loan items for a contact in real-time.
- FR-006**: System MUST provide a way (FAB) to add new loan items with a title and amount.
- **FR-007**: System MUST persist the creation timestamp for each loan item.
- **FR-008**: System MUST allow editing the title and amount of an existing unsettled loan item.
- **FR-009**: System MUST allow deleting a loan item.
- **FR-010**: System MUST allow multiple loan items with the same title for a single contact.
- **FR-011**: System MUST validate that the amount is greater than zero for all loan and borrow items.
- **FR-012**: System MUST preserve all loan items for history when a contact/loan is moved to the "completed" section or deleted.
- **FR-013**: System MUST require a non-empty title for all loan items.

### Key Entities *(include if feature involves data)*

- **LoanItem**:
    - `id`: Unique identifier.
    - `contactId`: Reference to the person.
    - `amount`: The monetary value of the item.
    - `title`: Description of what the loan is for.
    - `isSettled`: Boolean flag indicating if the item has been paid.
    - `createdAt`: Timestamp of creation.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Users can add a new loan item in under 10 seconds.
- **SC-002**: The total balance updates in under 3 seconds after toggling an item's settlement status.
- **SC-003**: 100% of existing loan totals are successfully migrated to "Legacy Balance" items upon update.
- **SC-004**: Users can accurately distinguish between settled and unsettled items at a glance.

## Assumptions

- **Contact Existence**: We assume the `contactId` provided to the Loan Details screen always refers to an existing contact.
- **Currency**: We assume all loan items for a contact use the same currency (consistent with existing app behavior).
- **Migration Scope**: Migration only happens once and covers all contacts with non-zero loan balances.
- **UI Interaction**: The FAB will open a modal input (BottomSheet/Dialog) for title and amount.
FAB will open a modal input (BottomSheet/Dialog) for title and amount.
