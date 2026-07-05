# Feature Specification: Unified Loan Payment Ledger, Resilient App Lock & SMS Transfer Destinations

**Feature Branch**: `003-loan-payment-ledger`
**Created**: 2026-07-05
**Status**: Draft
**Input**: User description: "Loan payment ledger unification + app-lock overlay + SMS transfer destinations. Implements the five approved design decisions from the 2026-07-05 design report (all recommended options accepted by the owner). Build order: B (lock overlay) → C (transfer destination + ghost repair) → A (loan ledger)."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - In-progress work survives the app lock (Priority: P1)

A user is halfway through filling in a transaction or loan form when the phone screen turns off or they briefly switch apps. When they return and unlock the app, everything they had typed — amounts, titles, selections, open sheets — is exactly as they left it.

**Why this priority**: This is the highest-frequency pain point: it silently destroys user work every day, affects every form in the app, and erodes trust ("the app ate my entry"). It is also fully independent of the other stories.

**Independent Test**: Enable app lock, start filling any form (e.g., a new expense with amount, title, and category set), leave the app past the lock timeout, return and unlock. The form must be intact. Repeat on the loan entry modal and the loan payment sheet.

**Acceptance Scenarios**:

1. **Given** app lock is enabled and a user is mid-way through any form, **When** the app locks due to inactivity and the user unlocks it, **Then** every typed value, every selection, the cursor position, and any open sheet/modal are exactly as they were before the lock.
2. **Given** the app is locked, **When** the user views the device's recent-apps switcher, **Then** no app content is visible (existing privacy protection is preserved).
3. **Given** the app is locked, **When** the user taps, types, or presses back, **Then** no interaction reaches the screen underneath the lock cover; only the unlock flow responds.
4. **Given** the user unlocks successfully, **When** the app returns, **Then** no screen re-initializes or reloads in a way that discards what was on it.

---

### User Story 2 - Choose how quickly the app locks (Priority: P2)

A user who finds the current lock timing too aggressive (or not aggressive enough) can choose how long the app waits before locking after they leave it.

**Why this priority**: Small, self-contained companion to Story 1. Valuable but not load-bearing.

**Independent Test**: Change the "Lock after" setting to each available choice, background the app for just under and just over that duration, and observe whether the lock engages.

**Acceptance Scenarios**:

1. **Given** app lock is enabled, **When** the user opens the app's settings, **Then** they can choose a lock delay of: Instantly, 1 minute, 5 minutes, or 15 minutes.
2. **Given** a chosen delay, **When** the user leaves the app for less than that delay and returns, **Then** the app does not ask them to unlock.
3. **Given** a chosen delay, **When** the user leaves the app for longer than that delay, **Then** the app requires unlock on return.
4. **Given** no explicit choice has been made, **Then** the delay defaults to 1 minute (today's behavior).

---

### User Story 3 - Transfer messages get a real destination (Priority: P3)

A user whose bank SMS describes a transfer ("You transferred 500 EGP to …") ends up with a correct two-sided transfer in the app: money leaves the linked wallet and arrives in the wallet the user chose — either automatically (if the user saved a default destination for that message format) or via a one-tap review step.

**Why this priority**: Today every automatically processed transfer message produces an invisible, broken record — an active data-corruption bug. Must land before the loan work because loan linking builds on trustworthy SMS transactions.

**Independent Test**: Map a transfer-type message format twice — once with a saved default destination and once without — then let a matching message arrive (or re-scan) and verify both paths.

**Acceptance Scenarios**:

1. **Given** a message format mapped with a transfer amount and a saved default destination wallet, **When** a matching message arrives, **Then** a transfer is created automatically from the sender-linked wallet to the default destination, with both sides showing the correct amount, and no user input is required.
2. **Given** a message format mapped with a transfer amount and no default destination, **When** a matching message arrives, **Then** nothing is committed to any wallet and the message waits in the review queue, clearly labeled as needing a destination.
3. **Given** a review item that needs a destination, **When** the user picks a destination wallet and confirms, **Then** the transfer is created with that destination.
4. **Given** the same review item, **When** the user also ticks "remember for this template", **Then** the chosen destination is saved on that message format and future matching messages are processed automatically.
5. **Given** any path through the system, **Then** it is impossible to create a transfer whose source and destination are the same wallet.
6. **Given** a message format whose saved destination wallet has since been deleted, **When** a matching message arrives, **Then** it falls back to the review queue asking for a destination.

---

### User Story 4 - Previously lost transfers are rescued (Priority: P4)

A user who was affected by the invisible-transfer bug gets those lost messages back: after updating, each previously hidden transfer appears in the review queue, ready to be re-routed to the right wallet.

**Why this priority**: Repairs historical damage from the bug fixed in Story 3; depends on Story 3's review flow existing.

**Independent Test**: Seed a database with invisible self-transfer records created the old way, update, and verify each one surfaces as a review item and no longer exists as a committed transaction.

**Acceptance Scenarios**:

1. **Given** the database contains invisible self-transfer records created by earlier versions from SMS, **When** the user updates and opens the app, **Then** each such record is converted into a review-queue item (not deleted, not silently kept), and the review badge reflects them.
2. **Given** a rescued review item, **When** the user assigns a destination and confirms, **Then** a correct transfer is created — the money finally appears.
3. **Given** the rescue has run once, **When** the app restarts, **Then** it does not run again or duplicate review items.

---

### User Story 5 - Pay a loan entry and move real money (Priority: P5)

A user taps the circle next to a loan checklist entry. A small sheet slides up, already filled with the remaining amount, the loan's wallet, and today's date. One confirming tap records the payment **and** creates a real transaction that changes the wallet balance. Typing a smaller amount first makes it a partial payment — same flow, nothing extra to learn.

**Why this priority**: The heart of the loan overhaul (owner's decisions D1:C and D5:B). Ordered after the SMS stories per the approved build order, but independently testable on its own.

**Independent Test**: On a loan with entries, pay one entry in full (2 taps) and one partially, then verify wallet balances, entry states, and loan totals.

**Acceptance Scenarios**:

1. **Given** an unsettled entry, **When** the user taps its circle, **Then** a payment sheet opens pre-filled with the entry's remaining amount, the loan's default wallet, and today's date.
2. **Given** the payment sheet with defaults, **When** the user confirms, **Then** a payment is recorded against the entry and a real transaction is created in the chosen wallet (money in when collecting a loan they gave; money out when repaying a loan they took), and the entry becomes settled.
3. **Given** the payment sheet, **When** the user edits the amount to less than the remaining amount and confirms, **Then** the entry records a partial payment, stays open, shows paid/total progress, and the next payment sheet pre-fills with the new remainder.
4. **Given** the payment sheet, **When** the user tries to confirm an amount of zero, a negative amount, or more than the entry's remaining amount, **Then** the confirmation is not possible and the limit is communicated.
5. **Given** a loan with no default wallet, **When** the payment sheet opens, **Then** the user must pick a wallet before they can confirm.
6. **Given** a settled entry with payments, **When** the user taps its circle to un-settle it, **Then** the app asks for one confirmation and, on confirm, deletes the payment record(s) and their transaction(s) together, restoring wallet balances.
7. **Given** an entry settled before this feature existed (no payment behind it), **Then** it displays a "settled — no transaction" note, is excluded from payment history, and no transactions are generated for it retroactively.
8. **Given** rapid repeated taps on an entry's circle, **Then** only one payment sheet opens and no duplicate payments are recorded.

---

### User Story 6 - See entry progress and payment history (Priority: P6)

A user can see at a glance how much of each entry is paid (half-filled circle, paid/total figures) and can expand an entry to see every payment — its date, wallet, and a link that jumps to the underlying transaction. The loan header's "paid" figure is the plain sum of payments, with no double counting.

**Why this priority**: The read-side of Story 5; meaningful only once payments exist.

**Independent Test**: Create full and partial payments on several entries, then verify the visual states, the expanded history, the jump-links, and that the loan header total equals the sum of payment amounts exactly.

**Acceptance Scenarios**:

1. **Given** an entry with partial payments, **Then** its row shows a distinct partially-paid state and its paid/total amounts.
2. **Given** an entry with payments, **When** the user expands it, **Then** each payment lists its amount, wallet, and date, and offers a jump-link to its transaction.
3. **Given** any combination of settled entries, partial payments, and loan-level payments, **Then** the loan header's "paid" figure equals the sum of payment records only — recording one real-world payment changes the total by exactly its amount.
4. **Given** an entry is deleted while it has payments, **Then** its payments remain as loan-level payments (money history is never silently destroyed) and the loan totals are unchanged.

---

### User Story 7 - Link an SMS transaction to a loan (Priority: P7)

A user sends money to a friend by bank transfer; the SMS creates the transaction. From the review card or the transaction's detail view, the user taps "Link to loan", picks the friend's loan, and optionally points the amount at specific entries. The loan updates — and no second transaction is ever created, because the money already moved once.

**Why this priority**: The capstone that connects the SMS engine to the loan ledger; depends on Stories 3 and 5.

**Independent Test**: Link an SMS-created transaction to a loan in each direction (money out / money in, on both a lent and a borrowed loan) and verify loan totals, entry allocation, and that wallet balances never change at link time.

**Acceptance Scenarios**:

1. **Given** an SMS-created transaction, **When** the user opens it (or its review card) and chooses "Link to loan", **Then** they can pick from their loans (or start a new loan from this transaction).
2. **Given** a picked loan where the money direction means a repayment (money in on a loan they gave; money out on a loan they took), **When** the loan has entries, **Then** the user can allocate the amount to entries — automatically oldest-first, by manual pick, or skip entries and just reduce the loan total.
3. **Given** any link is confirmed, **Then** no new transaction is created and no wallet balance changes; the loan gains a payment record that points at the existing transaction, and the transaction shows its loan link.
4. **Given** the money direction and loan type, **Then** the meaning is inferred automatically: money out on a lent loan = lent more (increase); money out on a borrowed loan = repaid (decrease, may settle entries); money in on a lent loan = they repaid (decrease, may settle entries); money in on a borrowed loan = borrowed more (increase).
5. **Given** a linked transaction is later deleted, **Then** its loan payment record is removed with it (they describe one real-world event) and loan totals update accordingly.
6. **Given** a linked payment settles an entry via allocation, **Then** that entry's progress and settled state update exactly as if paid from the loan screen.

---

### Edge Cases

- Lock engages while the loan payment sheet or the destination picker is open mid-input → covered by Story 1: everything survives.
- The wallet chosen in a payment sheet or saved as a transfer destination is deleted later → future flows fall back to asking; existing transactions are untouched.
- A transfer-format message also fails other checks (e.g., currency mismatch) → existing quarantine reasons still apply; "needs destination" does not override them.
- An entry's amount is edited down below what is already paid on it → the entry auto-settles (paid ≥ amount); history is preserved.
- The ghost-transfer rescue finds zero affected rows → it completes silently and never runs again.
- A user unlinks or edits a loan payment that came from an SMS link → allowed from the loan side; the transaction itself is never modified by loan-side edits, only the link/record is.
- Two devices / restored backups replay the rescue → conversion is keyed to the affected records, so restored data cannot duplicate review items for rows already converted.

## Requirements *(mandatory)*

### Functional Requirements

**App lock (Stories 1–2)**

- **FR-001**: The lock screen MUST cover the running app without destroying the screens underneath; all in-progress input MUST survive a lock/unlock cycle unchanged.
- **FR-002**: While locked, the app MUST block every form of interaction (touch, keyboard, back navigation) from reaching covered content, and MUST keep app content hidden from the system's recent-apps preview.
- **FR-003**: Users MUST be able to choose a lock delay of Instantly, 1, 5, or 15 minutes; the default MUST remain 1 minute; the choice MUST take effect without restarting the app.
- **FR-004**: No screen may re-initialize itself in a way that discards visible user data when the app returns from the locked state.

**SMS transfer destinations (Stories 3–4)**

- **FR-005**: A message format mapped with a transfer amount MUST support an optional saved destination wallet.
- **FR-006**: With a saved destination, matching messages MUST be processed automatically into a two-sided transfer (correct source, destination, and amounts on both sides).
- **FR-007**: Without a saved destination, matching messages MUST wait in the review queue labeled as needing a destination; nothing may be committed to any wallet in the meantime.
- **FR-008**: The review step MUST offer a destination picker and a "remember for this template" option that saves the destination for future messages.
- **FR-009**: The system MUST refuse to store any transfer whose source and destination are the same wallet, on every path (automatic and manual).
- **FR-010**: A one-time repair MUST convert previously stored invisible self-transfers created from SMS into review-queue items; the repair MUST be idempotent and MUST NOT delete the underlying message data.

**Loan payment ledger (Stories 5–7)**

- **FR-011**: Every loan payment — full or partial, entry-targeted or loan-level — MUST be recorded as a payment record carrying amount, date, and wallet.
- **FR-012**: A payment record MAY reference a specific loan entry; an entry's paid amount MUST equal the sum of its payment records, and its settled state MUST be derived (settled when paid ≥ entry amount), never stored independently.
- **FR-013**: Confirming a payment from the entry sheet MUST create both the payment record and a real, balance-affecting transaction in the chosen wallet, in the correct direction for the loan type.
- **FR-014**: The payment sheet MUST pre-fill the entry's remaining amount, the loan's default wallet, and today's date; users MUST be able to change each before confirming.
- **FR-015**: Entry-targeted payments MUST be rejected when the amount is zero, negative, or exceeds the entry's remaining amount.
- **FR-016**: Un-settling a paid entry MUST require one explicit confirmation and MUST then delete the associated payment record(s) and transaction(s) together, restoring balances.
- **FR-017**: A loan's total paid figure MUST be computed from payment records only; settled entries MUST NOT be counted separately (eliminating double counting).
- **FR-018**: Entries settled before this feature MUST keep their settled appearance with a "settled — no transaction" indication and MUST NOT have transactions generated retroactively.
- **FR-019**: Deleting an entry that has payments MUST preserve those payments as loan-level payment records.
- **FR-020**: Users MUST be able to link an existing transaction (including SMS-created ones) to a loan from the transaction's detail view and from the SMS review card.
- **FR-021**: Linking MUST create a payment record that references the existing transaction and MUST NOT create a second transaction or change any wallet balance.
- **FR-022**: The link flow MUST infer the payment meaning from money direction and loan type (lent/borrowed → increase/decrease) without asking the user to classify it.
- **FR-023**: When a linked payment is a repayment and the loan has entries, the user MUST be able to allocate the amount to entries automatically (oldest first), manually, or not at all.
- **FR-024**: Deleting a transaction that has a loan link MUST also remove the linked payment record, and loan totals MUST update accordingly.
- **FR-025**: The existing SMS engine guarantees MUST be preserved: message identity/deduplication behavior, frozen mapped formats, per-sender read boundaries, the single shared alignment logic, and single-instance duplicate-safe transaction creation.

### Key Entities

- **Loan**: A lending relationship (lent or borrowed) with a counterpart, a default wallet, and a set of entries and payment records.
- **Loan Entry**: A named piece of a loan (e.g., "TV installment, 1,200") with an amount. Its paid amount and settled state are derived from payment records that reference it.
- **Payment Record**: The single unit of loan money movement: amount, date, wallet, the loan it belongs to, optionally the entry it pays, and the transaction that embodies it. Exactly one transaction per record — either created by the record (entry payment) or adopted by it (SMS link).
- **Transaction**: The app's existing money event affecting wallet balances; may carry a loan link. One real-world payment is always exactly one transaction.
- **Message Format (SMS template)**: A recognized SMS pattern; for transfer formats it may carry an optional default destination wallet.
- **Review Item**: A message (or rescued record) awaiting a user decision, now including the "needs destination" reason.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: Zero loss of in-progress form input across a lock/unlock cycle, verified on every form in the app (100% of fields, selections, and open sheets preserved).
- **SC-002**: Recording a full entry payment takes at most 2 taps from the loan screen; a partial payment takes at most 4 interactions (tap, edit amount, confirm).
- **SC-003**: A transfer message with a saved destination becomes a correct two-sided transfer with zero user interaction, and the review path resolves a destination in a single pick-and-confirm.
- **SC-004**: After the repair and the new safeguards, an audit of stored data finds zero same-wallet transfers, and 100% of previously invisible SMS transfers are surfaced for user resolution.
- **SC-005**: For any sequence of payments, the loan's paid total changes by exactly the sum of those payments — no scenario exists where one real-world payment moves the total by any other amount.
- **SC-006**: Linking a transaction to a loan changes no wallet balance (balance sheets identical before and after linking) in 100% of cases.
- **SC-007**: All previously passing flows around SMS processing (deduplication, sender boundaries, format freezing) show no behavioral regression.

## Assumptions

- All five design decisions from the 2026-07-05 report are final as approved: payments-under-entries model (D1:C), lock-as-overlay plus timing setting (D2), hybrid destination handling (D3:C) with ghost conversion to review, manual loan linking only (D4) with auto-suggestions explicitly out of scope, quick-sheet payment flow (D5:B), no backfill for legacy settled entries (D6), and cascade delete with one confirmation when un-settling (D7).
- Build order is fixed: app lock first, SMS transfer destinations second, loan ledger last; each story ships independently testable.
- Entry-targeted payments are capped at the entry's remaining amount; overshoot is expressed as a separate loan-level payment.
- Deleting a loan entry never deletes money history; its payments become loan-level records.
- The loan's existing wallet association is used as the payment sheet's default wallet; loans without one require a wallet pick at payment time.
- The rescue of invisible transfers is a one-time, idempotent update step; message data is never deleted by it.
- Existing review-queue capacity and flows are reused for the new "needs destination" reason rather than introducing a separate queue.
