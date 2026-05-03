# Feature Specification: SMS Extraction Engine

**Feature Branch**: `002-sms-extraction-engine`
**Created**: 2026-04-27
**Status**: Draft
**Input**: User description: "Automated, on-device SMS Information Extraction engine that reads bank/financial-institution text messages, discovers their structure via an unsupervised clustering algorithm, and — after the user maps fields once — silently produces structured Transaction entities. Sideload/F-Droid deployment with explicit READ_SMS permission. Includes a Human-in-the-Loop quarantine queue for unrecognized message shapes."

## Clarifications

### Session 2026-04-27

- Q: When one SMS sender ID could legitimately concern more than one wallet (e.g., the user has two wallets at the same bank, both fed by "ChaseAlerts"), how should the system disambiguate? → A: Disallow many-to-one. The UI MUST prevent linking a sender to a wallet if that sender is already linked to a different wallet. A sender ID belongs to at most one wallet; a wallet may still hold multiple senders. (Ivy Wallet has no separate "credit card" concept — every account is just a wallet, including bank-card wallets.)
- Q: How should the system catch up on real-time SMS that aren't visible because Android dropped the live broadcast (force-kill, doze, vendor power management, etc.)? → A: No live broadcast at all. The system runs a full inbox-reconciliation scan **on every app launch**, plus on **explicit user trigger** via a "Sync SMS" entry in the dropdown / overflow menu. There is no BroadcastReceiver, no foreground service, no background scheduling. The pipeline is pure pull-mode; "real-time" capture is replaced by "sync-time" capture. *(SUPERSEDED 2026-04-28: launch-time scan still runs but is now per-linked-sender; the global "Sync SMS" overflow entry is removed in favor of per-wallet "Sync now".)*
- Q: What does the user see during the first long historical scan, and how is the scope of that scan controlled? → A: Progressive rendering — templates appear in the list as they are discovered, with a non-blocking "Scanning… N / M messages" indicator; mapping is enabled on already-discovered templates while the scan continues. **Before the first scan starts**, the user is prompted to pick a historical period: **Last Week**, **Last Month**, **Last Quarter (3 months)**, **Last Year**, or **All Time**. The chosen period bounds only the initial seeding scan; subsequent launch / manual reconciliation scans use the watermark from the most recent processed message regardless of the original period choice. The user MUST be able to extend the period later (e.g. went with "Last Month" originally, now wants "Last Year") via a "Scan further back…" action. *(2026-04-28: still applies, scoped per-sender — the period and watermark are now per-linked-sender, not global.)*
- Q: What SMS-related data should the existing Ivy Wallet backup/export include after a phone restore or app reinstall? → A: Back up only the user's *configurations*: discovered templates (structural patterns), wildcard-to-property mappings, classifications, sender→wallet links, blacklist state, and the per-template watermark for incremental scanning. Do NOT back up raw SMS bodies, the PendingReviewItem queue, the per-message dedup keys, or any source-SMS payload referenced by auto-created transactions. After restore, those derived artifacts are re-built by re-running the scan against the device's SMS inbox — the device inbox is the source of truth for *messages*; the backup is the source of truth for *interpretations*.
- Q: What is the accessibility plan for the inline tap-on-`<*>` wildcard mapping UI (which is hostile to screen readers, keyboard / D-pad navigation, and small tap targets)? → A: **None required — this feature is being built for a single user (the project owner) only**. Accessibility (TalkBack, keyboard / D-pad navigation, Switch Access, motor-accessibility tap targets) and broad internationalization are explicitly **out of scope**. The inline tap-the-`<*>` interaction is the only mapping path. If the feature is ever opened up to other users in the future, dual-interaction (inline + enumerated list) and full accessibility coverage MUST be revisited as a separate effort.

### Session 2026-04-28 (post-MVP redesign)

After the initial implementation (T001–T112) was reviewed on-device, the entry-point shape, the wildcard role taxonomy, and several rendering behaviors were redesigned. The points below SUPERSEDE earlier clarifications where they conflict.

- Q: Should the SMS extraction engine be operated as a global app-wide feature, or per-wallet? → A: **Per-wallet**. The global "Sync SMS" entry on the home overflow menu is removed. Each wallet gains a "Link SMS chat" button inside its existing wallet-edit modal (`AccountModal` in the legacy module). The user picks one sender per wallet (the wallet's "SMS chat"), and all scanning, template discovery, and sync happens against that one sender for that one wallet. The 1:1 sender-to-wallet rule (FR-016) is preserved and now also reflected in the entry-point shape.
- Q: How does the user pick a sender when linking it to a wallet? → A: A picker screen shows the **top 10 sender IDs** found in the device inbox (ranked by message count). The user can tap one of them, OR type any sender ID into a free-text field; on submit, the typed value is validated to exist in the inbox before the link is saved. If the typed sender has zero messages in the inbox, the link is refused with an inline error.
- Q: When does the app run a reconciliation scan? → A: (a) On app launch — but only against wallets that already have a linked sender (per-sender bounded read). (b) Manually from the wallet modal via a "Sync now" button. The wallet modal also shows a last-sync status row ("Last sync: 2 min ago" / "Last sync failed: <reason>"). If the launch scan fails, the banner stays visible and the manual button is the recovery path. There is still no `BroadcastReceiver`, no foreground service, no `WorkManager` job.
- Q: What can the user map an individual `<*>` wildcard to? → A: One of **eight roles** (replacing the previous Amount / Merchant / DateTime / Reference / Ignored taxonomy):
  1. **Income** — the wildcard's value is the transaction amount AND the transaction kind is Income. (At most one Income/Expense/Transfer wildcard per template.)
  2. **Expense** — same, kind is Expense.
  3. **Transfer** — same, kind is Transfer.
  4. **Current Total** — the running balance after this transaction. Stored on the auto-created Transaction's metadata, not used as the amount.
  5. **Transaction Fee** — a fee charged. Stored on metadata.
  6. **Date** — a date/time string parsed via `DateTimeParser`. If no Date wildcard is bound, OR a bound Date wildcard fails to parse, the auto-created transaction defaults to the SMS message timestamp.
  7. **Merchant** — free-text concatenated into the transaction description. Listed last in the picker (least prominent — most bank SMS already include a recognizable merchant in literal text, so the user typically does not need to bind it).
  8. **Ignored** — explicitly skip this wildcard. Multiple wildcards may be Ignored.
  The separate INCOME / EXPENSE / TRANSFER classification picker is removed; the template's kind is implied by which amount role is bound.
- Q: What does the picker UI for the eight roles look like? → A: It uses the project's existing transaction-type keypad style (matching `ChangeTransactionTypeModal` in the legacy module) — touch-friendly tiles, NOT a Material3 `RadioButton` list. Same visual identity as the rest of the app.
- Q: How are wildcards rendered in the template list / mapping / pending-review screens? → A: **Marked, not removed.** Instead of replacing varying segments with the literal string `<*>`, the UI shows the actual value from one example message, visually highlighted (color + role label) so the user can read the message naturally and still recognize which segments are dynamic. One example value per slot is captured during clustering and persisted with the template.
- Q: How does the parser handle numbers with letters glued to them (`70egp`, `190EGP`, `٦٠ج`, `$15`)? → A: The Drain pre-normalization regex collapses any digit cluster plus its adjacent non-space alphanumeric characters into a single token before tree descent. The whole glued unit becomes one wildcard slot.
- Q: How are template lists displayed when many SMS share the same shape? → A: As a **rollup** — one row per template showing the example body with marked tokens, plus a "N messages match this shape" subtitle. Individual messages are not listed.
- Q: How are Arabic SMS rendered? → A: Per-message direction. The first non-whitespace character of the body is checked against the Arabic Unicode blocks (`U+0600`–`U+06FF`, `U+0750`–`U+077F`, `U+FB50`–`U+FDFF`, `U+FE70`–`U+FEFF`); if Arabic, the body is rendered with `LayoutDirection.Rtl` for that one render. English messages render LTR.
- Q: What is the equivalent of "blacklist this template" called in the UI? → A: **"Ignore this template forever."** Same effect as the previous blacklist (template state → `BLACKLISTED`, future matching messages dropped, queued items cleared), but exposed as a prominent button on each pending-review item and each template list row, not a hidden overflow toggle.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Link an SMS chat to a wallet and earn automatic transactions (Priority: P1)

A user installs Ivy Wallet on a phone with months of bank-notification SMS history. They open the wallet they want to track via SMS, tap the existing wallet-edit modal, and find a new **"Link SMS chat"** button. Tapping it opens a sender picker showing the **top 10 sender IDs** in their inbox (ranked by message count) plus a free-text field. They pick (or type) the sender for that wallet — say `ChaseAlerts`. The app validates the sender exists in the inbox, prompts them to choose a historical scan period (Week / Month / Quarter / Year / All) bounded to messages from THIS sender only, and starts scanning. Templates are clustered progressively and shown as a rollup list ("12 messages match this shape"). The user taps a template, sees one example message rendered with each dynamic segment **highlighted in place** (not replaced with `<*>`), taps a highlighted segment, and the project's transaction-type keypad opens listing the eight roles: Income / Expense / Transfer / Current Total / Transaction Fee / Date / Merchant / Ignored. They mark the amount segment as **Expense**, optionally mark a date segment as **Date**, save. From that moment on, every launch (and the per-wallet "Sync now" button) reads only that sender's new messages, and any matching ones become real Expense transactions in this wallet, with no further taps.

**Why this priority**: This is the entire value proposition. Without it, the feature delivers nothing. Per-wallet entry, sender picker, ingestion, clustering, role-based mapping, and Tier 1 silent capture must all work end-to-end for a single template before anything else matters.

**Independent Test**: Install the app on a device with at least 20 historical SMS from the same sender, all sharing the same shape. Open a wallet, tap "Link SMS chat", pick the sender, accept "Last Month". Confirm the template appears as a rollup row. Tap it, mark the amount segment as Expense, save. Inject a fresh matching SMS via `adb` and tap "Sync now" on the wallet — verify a transaction with the correct amount, wallet currency, and Expense type appears within seconds, with no further user interaction.

**Acceptance Scenarios**:

1. **Given** the user has granted READ_SMS and has SMS from at least one sender in their inbox, **When** they open a wallet's edit modal, **Then** a "Link SMS chat" button is visible. Tapping it opens a sender picker showing the top 10 senders by message count plus a free-text input.
2. **Given** the sender picker is open, **When** the user types a sender ID and submits, **Then** the app verifies the sender has at least one message in the inbox; if so, the link is created; if not, an inline error explains "no messages from this sender found".
3. **Given** a sender has just been linked to the wallet, **When** the link saves, **Then** the user is prompted to choose a historical scan period (Last Week / Last Month / Last Quarter / Last Year / All Time). The scan only begins after a choice is confirmed and only reads SMS from the linked sender within the chosen period.
4. **Given** discovered templates are shown for the linked sender, **When** the user views the list, **Then** each row is rendered as a rollup ("N messages match this shape") with one example body where dynamic segments are highlighted in place — NOT replaced with `<*>`. Arabic-bodied messages render right-to-left; English bodies render left-to-right.
5. **Given** a template is open for mapping, **When** the user taps a highlighted dynamic segment, **Then** a keypad-style picker (matching the project's transaction-type keypad) opens listing the eight roles in this order: Income, Expense, Transfer, Current Total, Transaction Fee, Date, Merchant, Ignored.
6. **Given** the user has bound exactly one segment to one of {Income, Expense, Transfer}, **When** they save, **Then** the template becomes Active. No separate classification picker exists — the template's kind IS the chosen amount role.
7. **Given** an Active template exists for sender `ChaseAlerts` linked to a USD wallet, **When** a new SMS from `ChaseAlerts` matching the template lands in the device inbox AND the user re-opens Ivy Wallet OR taps "Sync now" on that wallet, **Then** the reconciliation scan creates a Transaction with the parsed amount, the wallet's USD currency, the role-implied type, and any other mapped fields — silently — and the transaction appears in the user's transaction list. If the template has no Date wildcard, OR a bound Date wildcard fails to parse, the transaction's date defaults to the SMS message timestamp.
8. **Given** a launch-time sync fails (e.g., transient cursor error), **When** the user opens the linked wallet's edit modal, **Then** the last-sync status row reads "Last sync failed: <reason>" and the "Sync now" button is enabled for manual retry.
9. **Given** the user originally chose "Last Month" as the historical period for a sender and now wants more coverage, **When** they tap "Scan further back…" inside the per-sender template list, **Then** the app runs an additional one-shot scan covering only the gap between the prior lower bound and the newly-chosen lower bound, without re-processing already-scanned messages and without touching the per-sender watermark.

---

### User Story 2 - Manually resolve unknown messages from the quarantine queue (Priority: P2)

After the user has been using the feature, their bank sends a message in a new shape (e.g., a refund notification, an international-purchase alert, or a new product launch). The clustering algorithm produces a *new, unmapped* template for it. Because no field mapping exists, the system refuses to guess. Instead, the message is routed to a per-wallet "Pending Review" queue. The wallet's edit modal shows a passive badge with a count. The user opens the queue, sees the new template alongside the SMS body (with dynamic segments highlighted), and either (a) maps its fields like in Story 1 — after which all queued messages of this shape are converted to transactions — (b) dismisses the message as "not a transaction", or (c) taps "Ignore this template forever" to silence the shape entirely.

**Why this priority**: Banks change message formats; new products produce new formats; international travel triggers shapes the user has never seen. Without a quarantine path, every unknown message either silently disappears (data loss) or gets guessed at (data corruption). Quarantine is the safety net that keeps the Tier 1 silent path trustworthy.

**Independent Test**: With Story 1 already working, send an SMS from a known sender but in a never-seen shape. Verify it does NOT create a transaction, DOES land in the per-wallet quarantine queue, and that the queue shows the message with its newly-discovered template. Map the template and confirm the queued message (and any subsequent matches) become transactions.

**Acceptance Scenarios**:

1. **Given** an SMS arrives from a sender linked to a wallet but the message does not match any Active template, **When** ingestion completes, **Then** no transaction is created, AND the message is added to the Pending Review queue scoped to that wallet, AND a per-wallet badge counter on the wallet's edit modal increments.
2. **Given** the user opens the per-wallet Pending Review queue, **When** the screen renders, **Then** each pending item shows: the SMS body rendered with dynamic segments highlighted in place (per-message LTR/RTL based on first-letter detection), the auto-discovered template's roll-up, the sender ID, the message timestamp, the quarantine reason, and three actions — "Map this template", "Dismiss this message", and "Ignore this template forever".
3. **Given** the user picks "Map this template" on a pending item, **When** they complete the mapping flow (same flow as Story 1, using the eight-role keypad) and save, **Then** the pending message AND all other queued messages matching the same template are converted to transactions, and the queue items are cleared.
4. **Given** the user picks "Dismiss this message" on a pending item, **When** they confirm, **Then** that single message is removed from the queue with no transaction created, and the template remains unmapped (future messages of this shape continue to be quarantined unless the user maps or ignores-forever the template).
5. **Given** the user picks "Ignore this template forever" on a pending item, **When** they confirm, **Then** the template state becomes `BLACKLISTED`, ALL queued messages for that template are removed from the queue, and future matching SMS are silently dropped at the data-source layer.

---

### User Story 3 - Silence noisy templates by ignoring them forever (Priority: P3)

The user's bank sends frequent non-financial messages from the same sender ID — promotional offers, balance alerts, OTP codes, fraud-warning surveys. Each is its own template. They produce no transactions, but they pile up in the Pending Review queue and create noise. The user taps **"Ignore this template forever"** on those templates from either the template list or the pending-review queue. From that point, matching messages are silently dropped at the data-source layer: they never enter quarantine, never produce transactions, and never increment the badge.

**Why this priority**: Quality-of-life. The feature works without it, but without it the queue becomes unusable for users whose banks chat constantly. "Ignore this template forever" is also the user's lever to express "this template is intentionally not a transaction" — distinct from "this template is not yet mapped".

**Independent Test**: Confirm a non-financial SMS template lands in the quarantine queue (per Story 2). Tap "Ignore this template forever". Send another SMS matching the template. Verify it does NOT appear in the queue, does NOT create a transaction, and does NOT increment any badge.

**Acceptance Scenarios**:

1. **Given** a template is shown in the template list (whether mapped, unmapped, or pending review), **When** the user taps "Ignore this template forever" on it, **Then** the template's state transitions to `BLACKLISTED` and any pending-review items belonging to it are removed from the queue.
2. **Given** a template is `BLACKLISTED`, **When** an SMS arrives that matches it, **Then** the message is discarded silently — no transaction, no quarantine entry, no badge increment, no notification.
3. **Given** a template is `BLACKLISTED`, **When** the user taps "Un-ignore this template", **Then** the template transitions back to `UNMAPPED` (NOT directly to `ACTIVE` — the user must re-map) and future matching messages are routed normally (Tier 1 if mapped, Tier 2 otherwise).

---

### Edge Cases

- **Permission denied or revoked**: The user denies READ_SMS, or grants then later revokes it in OS settings. The feature must show a clear, recoverable empty state inside any wallet edit modal that has a linked sender. No crashes, no silent failure.
- **Empty SMS inbox or no SMS from linked sender**: Permission granted but the linked sender has no messages. The per-wallet template list shows an explanatory empty state and a "Sync now" button.
- **User attempts to link a sender already linked to another wallet**: The sender picker MUST refuse the link, surface a clear inline error naming the wallet currently holding that sender, and offer (a) remove the existing link first, or (b) cancel. See FR-016.
- **User types a sender that does not exist in the inbox**: The free-text sender input MUST validate against the device inbox; if zero matching messages, refuse the link with an inline error.
- **Wildcard captures unparseable amount**: A wildcard mapped to one of {Income, Expense, Transfer} captures text like "five thousand" or a localized number format the parser cannot handle. The message is routed to quarantine with `QuarantineReason.AMOUNT_NOT_PARSEABLE` rather than silently dropped or assigned a wrong amount.
- **Number-letter glued tokens**: SMS bodies often contain amounts like `70egp`, `190EGP`, `٦٠ج`, or `$15` with no separator. The Drain pre-normalization regex MUST treat the digit cluster + adjacent non-space alphanumeric characters as a single token. Tests cover Arabic-Indic digits and Latin-script currency suffixes.
- **No Date wildcard or Date parse fails**: The auto-created transaction's date defaults to the SMS message timestamp. This is not an error path — it is the documented fallback.
- **Currency in SMS body differs from wallet currency**: The transaction uses the wallet's currency; the message is flagged in quarantine with `QuarantineReason.CURRENCY_MISMATCH` rather than silently miscategorized.
- **Duplicate SMS**: The same SMS body arrives twice (carrier retry, restore from backup, etc.). The system MUST NOT create duplicate transactions for the same source message (dedup by `sha256(senderId + ":" + epochMillis + ":" + body)`).
- **Same template re-clustered slightly differently after more data**: The algorithm produces a *new* template for messages that previously matched an *old* one (e.g., the bank introduces a new optional clause). Both the old user mappings and the queue must remain consistent — the user must not lose configuration work because of re-clustering.
- **Template active, but later edited**: User changes a wildcard mapping. Transactions already created are NOT retroactively rewritten unless the user explicitly opts in to a "re-process historical" action.
- **Phone restore / app reinstall**: After restore, Ivy Wallet's existing backup mechanism brings back the user's *configurations* (templates, sender→wallet links, role mappings, blacklist state, per-sender watermarks) but NOT raw SMS bodies, queue items, or per-message dedup keys. On the next launch, the per-sender reconciliation scan re-derives queue items and dedup keys from the device's current SMS inbox; messages already linked to existing transactions are recognized via watermark + dedup key and skipped.
- **Multi-part / concatenated SMS**: Long messages reassembled from multiple PDU segments are treated as a single message for clustering and parsing.
- **Spoofed sender ID**: A scam SMS impersonates a linked sender. Tier 1 silent creation is restricted to messages that match a *user-mapped* template; new shapes from a known sender are quarantined, not auto-trusted.
- **Arabic vs English bodies**: Some senders mix languages. Direction is decided by the first non-whitespace character of the body only, per the 2026-04-28 clarification. A mixed-script body whose first character is English renders LTR even if it contains Arabic substrings (acceptable for single-user scope).

## Requirements *(mandatory)*

### Functional Requirements

#### Permission and lifecycle

- **FR-001**: The app MUST request the operating system's READ_SMS permission explicitly before performing any SMS access, with a clear in-app explanation that all processing happens on-device.
- **FR-002**: The app MUST gracefully handle permission denial and post-grant revocation: the wallet edit modal's SMS section shows a recoverable empty state, and all non-SMS features continue to function.
- **FR-003**: All SMS data MUST be processed on-device. The app MUST NOT transmit SMS content, sender IDs, or discovered templates to any external service.

#### Ingestion (per-wallet, pull-only)

- **FR-004**: The app MUST be able to read the device's SMS inbox via an asynchronous operation that returns a typed success/failure result (`Either<String, T>`) rather than throwing. The operation MUST accept a sender-ID filter so reads are scoped to one linked sender at a time.
- **FR-005**: On every app launch, the app MUST run a **per-sender reconciliation scan** for each wallet that already has a linked sender. The scan reads only SMS where `address = <linkedSenderId>` AND `date > <perSenderWatermark>`, then routes each new row through the Tier 1 / Tier 2 / Blacklist pipeline. The launch scan MUST NOT block UI startup. The app MUST NOT register a `BroadcastReceiver`, MUST NOT run a foreground service, and MUST NOT schedule periodic background work.
- **FR-005a**: Each wallet edit modal that has a linked sender MUST expose a **"Sync now"** button that triggers the same per-sender scan as FR-005 on demand. The button surfaces in-progress / completed / failed states and the count of new transactions or quarantined items it produced.
- **FR-005b**: When the user FIRST links a sender to a wallet, the app MUST prompt them to choose a **historical scan period** (Last Week / Last Month / Last Quarter / Last Year / All Time). The first-time scan for that sender MUST be bounded by the chosen period. The chosen period and resulting watermark are stored **per linked sender**, not globally.
- **FR-005c**: The wallet edit modal MUST provide a "Scan further back…" action that re-prompts for the same period choices and runs an additional one-shot scan covering only the gap between the prior lower bound and the newly-chosen lower bound for THIS sender. The per-sender watermark is unaffected.
- **FR-005d**: The historical scan UX MUST be progressive and non-blocking: discovered templates render as they are produced; a "messages processed / total in scope" indicator is shown; mapping is available on already-discovered templates while scanning continues; navigating away does not abort the scan.
- **FR-005e**: The wallet edit modal MUST display a **last-sync status row** showing either the timestamp of the most recent successful scan ("Last sync: 2 min ago") or the failure reason from the most recent scan attempt ("Last sync failed: <reason>"). When a launch scan fails, the failure row remains visible until a subsequent manual or launch scan succeeds.
- **FR-006**: The app MUST de-duplicate SMS by a stable per-message identifier (`sha256(senderId + ":" + epochMillis + ":" + body)`) so the same source message cannot produce more than one transaction or queue entry across multiple scans.
- **FR-007**: The data layer MUST distinguish three failure outcomes — permission denied, transient read error, storage error — and surface them as typed `Either.Left` results with distinct prefixes (`PERMISSION_DENIED:`, `READ_ERROR:`, `STORAGE_ERROR:`) consumable by the UI layer.

#### Sender selection

- **FR-007a**: When the user taps "Link SMS chat" on a wallet, the picker MUST display the **top 10 sender IDs** from the device inbox ranked by descending message count, plus a free-text input field.
- **FR-007b**: When the user submits a free-text sender, the app MUST verify the sender has ≥1 message in the inbox before creating the link. If zero, the link is refused with an inline error "No messages found from this sender".
- **FR-007c**: A wallet may have **at most one** linked sender at a time. To switch senders, the user explicitly removes the existing link first.

#### Template discovery

- **FR-008**: The app MUST cluster SMS into *templates* using an unsupervised, fixed-depth tree-based parsing approach (Drain). Messages sharing structural shape (same literal tokens in the same positions after pre-normalization) MUST land in the same template.
- **FR-008a**: The Drain pre-normalization step MUST treat any digit cluster plus its adjacent (non-space) alphanumeric characters as a single token before tree descent. This collapses `70egp`, `190EGP`, `٦٠ج`, `$15` into single wildcard slots.
- **FR-009**: Within each template, segments that vary across messages MUST be replaced internally with a `<*>` wildcard placeholder. Stable literal text MUST be preserved verbatim. The persisted template MUST also retain **one example value per wildcard slot** so the UI can render the slot in place rather than as a literal `<*>`.
- **FR-010**: Template discovery MUST be deterministic for a given input set.
- **FR-011**: New incoming messages MUST be matched against existing templates first; only if no match is found should the algorithm create a new template.
- **FR-012**: Discovered templates MUST be persisted so discovery does not have to redo on every app launch.

#### Sender-to-wallet linking

- **FR-013**: A wallet (Account) MUST be able to hold zero or one linked SMS sender ID. The link is created from the wallet edit modal via the picker described in FR-007a.
- **FR-014**: The user MUST be able to remove the linked sender from a wallet's edit modal. Removing the link clears that wallet's per-sender watermark, templates, and pending review queue.
- **FR-015**: When a transaction is created from an SMS, the transaction MUST inherit the linked wallet's currency. SMS-body currency MUST NOT override the wallet currency; if a currency mismatch is detected in the body, the message is quarantined with `QuarantineReason.CURRENCY_MISMATCH`.
- **FR-016**: A sender ID MUST be linked to **at most one wallet** at any given time, enforced both at the DB layer (UNIQUE / PRIMARY KEY on `senderId`) and at the picker UI (pre-write check with inline `LINK_CONFLICT:` error naming the existing wallet).

#### Template configuration UI

- **FR-017**: The user MUST be able to view the per-wallet list of discovered templates with state badges: `UNMAPPED`, `ACTIVE`, `BLACKLISTED`, or `PENDING_REVIEW` (a template is `PENDING_REVIEW` if any of its messages are queued and the template is not yet mapped or blacklisted).
- **FR-017a**: Each template list row MUST be rendered as a **rollup** showing one example body (with dynamic segments highlighted in place — NOT as literal `<*>`) plus a "N messages match this shape" subtitle. Individual messages are not listed in the template list.
- **FR-018**: The user MUST be able to tap an individual highlighted dynamic segment within a rendered template to open the role picker.
- **FR-019**: The role picker MUST be presented as a **keypad-style modal** (matching the project's `ChangeTransactionTypeModal` aesthetic, NOT a Material3 `RadioButton` list) and MUST offer the following eight roles in this exact order: **Income**, **Expense**, **Transfer**, **Current Total**, **Transaction Fee**, **Date**, **Merchant**, **Ignored**.
  - At most ONE wildcard per template may be bound to any of {Income, Expense, Transfer}. The chosen amount role implies the transaction kind.
  - At most ONE wildcard per template may be bound to Current Total, Transaction Fee, or Date.
  - Multiple wildcards may be bound to Merchant (concatenated into the description) and to Ignored.
  - Currency is NOT a role — it is inherited from the wallet.
- **FR-020**: The template's transaction kind is implied by which amount role is bound (Income / Expense / Transfer). There is NO separate classification picker. A template MAY become `ACTIVE` only when exactly one wildcard is bound to one of {Income, Expense, Transfer}.
- **FR-021**: The user MUST be able to tap a prominent **"Ignore this template forever"** button on each template list row AND on each pending-review item. The button transitions the template to `BLACKLISTED` and clears all queued messages for that template. A confirmation dialog MUST be shown before transitioning a currently-`ACTIVE` template.
- **FR-022**: The user MUST be able to edit an Active template's wildcard role mappings at any time. Subsequent SMS use the new mappings; transactions already created MUST NOT be silently rewritten.
- **FR-022a**: Message bodies, template rollup previews, and pending-review item bodies MUST be rendered with `LayoutDirection.Rtl` when the body's first non-whitespace character is in an Arabic Unicode block (`U+0600`–`U+06FF`, `U+0750`–`U+077F`, `U+FB50`–`U+FDFF`, `U+FE70`–`U+FEFF`); otherwise `LayoutDirection.Ltr`. The check is per-message at render time.
- **FR-022b**: All SMS-feature screens MUST be wrapped in the project's `IvyMaterial3Theme` (or equivalent) and use design-system tokens for background, foreground, and text styles. Raw `androidx.compose.material3.Text` / `Card` defaults MUST NOT be used for the page surfaces; legibility on the project's dark theme is required (no grey-on-black text).

#### Tier 1 — Silent automatic capture

- **FR-023**: When an incoming SMS exactly matches an `ACTIVE` template AND the sender is linked to a wallet AND the bound amount-role wildcard parses successfully, the system MUST create a Transaction silently using:
  - **Amount** — value from the bound Income/Expense/Transfer wildcard
  - **Type** — implied by the amount role (Income / Expense / Transfer)
  - **Currency** — the wallet's currency
  - **Date** — the bound Date wildcard if present and parseable; otherwise the SMS message timestamp
  - **Description** — the bound Merchant wildcard text (if any), concatenated
  - **Metadata** — `currentTotal` from a Current Total wildcard (if bound), `transactionFee` from a Transaction Fee wildcard (if bound), plus the source dedup key, template id, sender id, and SMS timestamp for audit
  No notification, no modal, no user prompt is shown for the success path.

#### Tier 2 — Quarantine

- **FR-024**: When an incoming SMS does NOT meet all Tier 1 conditions (no matching `ACTIVE` template; sender not linked; amount-role wildcard fails to parse; currency mismatch detected in body; etc.), the system MUST route the message to the per-wallet Pending Review queue.
- **FR-025**: Each pending item MUST display: the SMS body (with dynamic segments highlighted; per-message LTR/RTL), the discovered template's rollup, the sender ID, the message timestamp, and the quarantine reason (one of `TEMPLATE_NOT_MAPPED`, `AMOUNT_NOT_PARSEABLE`, `SENDER_NOT_LINKED`, `CURRENCY_MISMATCH`).
- **FR-026**: From each pending item, the user MUST be able to: (a) "Map this template" (converts this and all other queued messages of the same template), (b) "Dismiss this message" (removes only this row), or (c) "Ignore this template forever" (transitions template to `BLACKLISTED` and clears all of the template's queued messages).
- **FR-027**: The wallet edit modal MUST surface the count of Pending Review items as a passive in-app badge. The app MUST NOT generate OS-level notifications for quarantined messages.

#### Blacklist

- **FR-028**: When an incoming SMS matches a `BLACKLISTED` template, the system MUST drop the message at the data-source layer. No transaction is created, no queue entry is added, no badge increments.
- **FR-029**: Tapping "Un-ignore this template" on a `BLACKLISTED` template MUST transition it back to `UNMAPPED` (the user must re-map before future messages flow through Tier 1) and MUST NOT retroactively process messages dropped while it was blacklisted.

#### Data integrity

- **FR-030**: Auto-created transactions MUST be visually distinguishable from manually-entered ones (e.g., a small "from SMS" indicator on the transaction row), and tapping the indicator MUST allow the user to view the source SMS body and the template that produced it.
- **FR-031**: The user MUST be able to delete an auto-created transaction. Deletion MUST mark the source SMS as "user-rejected" (via its dedup key) so future scans don't re-create it.
- **FR-032**: The user MUST be able to "re-process historical messages" against an updated template as an explicit, opt-in action with a count preview and confirmation step.

#### Backup and restore

- **FR-033**: Ivy Wallet's existing backup / export format MUST include the user's SMS-extraction *configurations*: discovered `SmsTemplate` patterns + example values + role mappings + state, sender→wallet links, and the latest **per-sender** scan watermark. Auto-created Transactions remain part of the standard transaction backup with their source-link metadata intact.
- **FR-034**: The backup format MUST NOT include raw SMS body text, `PendingReviewItem` records, or per-message dedup keys. After restore, these derived artifacts are re-populated by re-running the per-sender reconciliation scan against the device's current SMS inbox. Restored watermarks MUST be honored.

### Key Entities *(include if feature involves data)*

- **SmsMessage**: A single text message read from the device inbox, identified by sender ID, timestamp, body, and a stable de-duplication key (`sha256(senderId + ":" + epochMillis + ":" + body)`).
- **SmsTemplate**: An algorithmically-discovered message shape with literal tokens and wildcard slots. Holds: structural pattern, list of wildcard positions, **one example value per wildcard slot** (for in-place rendering), state (`UNMAPPED` / `ACTIVE` / `BLACKLISTED` / `PENDING_REVIEW`), and the user's wildcard-to-role mappings. There is NO separate classification field — kind is implied by which amount role (Income/Expense/Transfer) is bound. Each template is associated with the sender it was discovered for.
- **WildcardRole**: An enum of eight values bound per wildcard slot: `Income`, `Expense`, `Transfer`, `CurrentTotal`, `TransactionFee`, `Date`, `Merchant`, `Ignored`. At most one of {Income, Expense, Transfer} per template; at most one of CurrentTotal / TransactionFee / Date per template; Merchant and Ignored may repeat.
- **SenderAccountLink**: An association between a sender ID (string, PK) and a wallet (Account). Cardinality is **one-to-one from the sender side** (a sender belongs to exactly one wallet) and **at-most-one from the wallet side** in the redesigned UX (a wallet may link zero or one sender via the modal). Holds the per-sender watermark and the per-sender historical-scan lower bound.
- **Account / Wallet** *(extension of existing entity)*: Reference to a single linked sender ID via `SenderAccountLink`. All other existing semantics — currency, balance, type — preserved unchanged.
- **Transaction** *(existing entity)*: When auto-created, carries `metadata.smsSourceDedupKey`, `metadata.smsTemplateId`, `metadata.smsSourceSenderId`, `metadata.smsSourceTimestamp`, and (when applicable) `metadata.currentTotal` and `metadata.transactionFee` for audit and reverse lookup.
- **PendingReviewItem**: A queued `SmsMessage` awaiting user resolution. Holds the message body, the discovered template id, the wallet id (so the queue is per-wallet), the quarantine reason, and the time it entered the queue.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: For a linked sender with at least 100 historical messages, **at least 95%** are assigned to a discovered template (the rest may remain as singletons), with **no duplicates** of the same structural shape across separate templates.
- **SC-002**: A user can take one previously-unseen template from "discovered" to "active and producing transactions" — including marking the amount role and saving — in **under 60 seconds** (improved from 90s target since the classification step is removed).
- **SC-003**: When the user opens Ivy Wallet (or taps "Sync now") and the linked sender has new messages matching active templates, the resulting transactions appear in the transaction list within **3 seconds** of scan trigger (incremental delta of up to 50 new messages on a typical mid-range Android device).
- **SC-004**: Across a 4-week real-world usage period, **zero transactions** are auto-created from messages that did not exactly match an Active user-mapped template (Tier 1 has a 0% guess rate; ambiguity defers to Tier 2).
- **SC-005**: Of all SMS from a wallet's linked sender, **at least 90%** are routed deterministically to one of the three terminal states (transaction created, quarantined, or blacklisted) without crash or silent loss.
- **SC-006**: At least **80%** of pending-review items can be cleared by mapping a single template (versus dismissing one-by-one), confirming clustering granularity.
- **SC-007**: After mapping a template, a real transaction is created — with the correct amount, currency, wallet, and type — **on the very first matching message** (≥98% first-match success rate).
- **SC-008**: For a "Last Year" historical scan covering up to 5,000 in-scope SMS *from a single linked sender* on a typical mid-range Android device, **the first discovered template is tappable for mapping within 3 seconds** of scan start, and the full scan completes in **under 30 seconds**.
- **SC-009**: Subsequent reconciliation scans (per-sender, on launch or via "Sync now") processing an incremental delta of up to 50 messages complete in **under 1 second**.
- **SC-010**: The Drain pre-normalization correctly collapses number-letter glued tokens (`70egp`, `190EGP`, `٦٠ج`, `$15`) into single wildcard slots in 100% of test cases covering Latin-script and Arabic-Indic digits.
- **SC-011**: Arabic-bodied messages render right-to-left in template list, mapping screen, and pending-review queue; English-bodied messages render left-to-right; mixed-script messages follow first-character detection. Verified by manual inspection on a device with both languages in the inbox.

## Assumptions

- The feature targets sideloaded / F-Droid distribution. The READ_SMS permission is therefore available; the implementation does not need to design around Google Play's restricted-permission policies.
- All processing — ingestion, clustering, role-mapping, transaction creation — happens on-device. No cloud, no network calls, no telemetry of SMS content.
- The user is the sole, trusted owner of the device; SMS content is treated as personal data and never leaves the device.
- The feature integrates with Ivy Wallet's existing wallet (Account) and Transaction domain entities. The wallet entry-point is the existing legacy `AccountModal`; the Transaction entity is unchanged structurally and only gains optional source-reference + balance/fee metadata.
- The existing technology stack (100% Kotlin, Jetpack Compose UI, ArrowKt for typed result handling, Onion Architecture) is non-negotiable. All new code MUST conform to the project's constitution.
- Template discovery uses an unsupervised, fixed-depth tree-based clustering approach (Drain-style log parsing) ported to Kotlin. Alternative algorithms are out of scope.
- Currency is determined by the linked wallet, not parsed from SMS body text. Any SMS-body currency that contradicts the wallet currency is treated as a quarantine signal.
- "Tier 1 silent capture" means *no UI interruption* for the success path. It does NOT mean "no audit trail" — auto-created transactions remain inspectable, deletable, and traceable back to their source SMS.
- Multi-language support beyond Arabic-RTL / English-LTR direction handling is out of scope for this iteration.
- Retroactive re-processing of historical messages after a mapping change is opt-in and never automatic.
- The feature does not attempt to detect or block phishing/spoofed SMS beyond the natural protection of "Tier 1 only fires for messages exactly matching a user-mapped template".
- **Single-user (personal) deployment**: This feature is built for the project owner's personal use, not for distribution to other Ivy Wallet users in this iteration. Accessibility (TalkBack / screen reader, keyboard / D-pad navigation, Switch Access, motor-accessibility tap targets) is out of scope. The Arabic/English RTL handling is the only i18n concession; broader i18n MUST be revisited if the feature is later promoted for general distribution.
