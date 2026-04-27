# Feature Specification: SMS Extraction Engine

**Feature Branch**: `002-sms-extraction-engine`
**Created**: 2026-04-27
**Status**: Draft
**Input**: User description: "Automated, on-device SMS Information Extraction engine that reads bank/financial-institution text messages, discovers their structure via an unsupervised clustering algorithm, and — after the user maps fields once — silently produces structured Transaction entities. Sideload/F-Droid deployment with explicit READ_SMS permission. Includes a Human-in-the-Loop quarantine queue for unrecognized message shapes."

## Clarifications

### Session 2026-04-27

- Q: When one SMS sender ID could legitimately concern more than one wallet (e.g., the user has two wallets at the same bank, both fed by "ChaseAlerts"), how should the system disambiguate? → A: Disallow many-to-one. The UI MUST prevent linking a sender to a wallet if that sender is already linked to a different wallet. A sender ID belongs to at most one wallet; a wallet may still hold multiple senders. (Ivy Wallet has no separate "credit card" concept — every account is just a wallet, including bank-card wallets.)
- Q: How should the system catch up on real-time SMS that aren't visible because Android dropped the live broadcast (force-kill, doze, vendor power management, etc.)? → A: No live broadcast at all. The system runs a full inbox-reconciliation scan **on every app launch**, plus on **explicit user trigger** via a "Sync SMS" entry in the dropdown / overflow menu. There is no BroadcastReceiver, no foreground service, no background scheduling. The pipeline is pure pull-mode; "real-time" capture is replaced by "sync-time" capture.
- Q: What does the user see during the first long historical scan, and how is the scope of that scan controlled? → A: Progressive rendering — templates appear in the list as they are discovered, with a non-blocking "Scanning… N / M messages" indicator; mapping is enabled on already-discovered templates while the scan continues. **Before the first scan starts**, the user is prompted to pick a historical period: **Last Week**, **Last Month**, **Last Quarter (3 months)**, **Last Year**, or **All Time**. The chosen period bounds only the initial seeding scan; subsequent launch / manual reconciliation scans use the watermark from the most recent processed message regardless of the original period choice. The user MUST be able to extend the period later (e.g. went with "Last Month" originally, now wants "Last Year") via a "Scan further back…" action.
- Q: What SMS-related data should the existing Ivy Wallet backup/export include after a phone restore or app reinstall? → A: Back up only the user's *configurations*: discovered templates (structural patterns), wildcard-to-property mappings, classifications, sender→wallet links, blacklist state, and the per-template watermark for incremental scanning. Do NOT back up raw SMS bodies, the PendingReviewItem queue, the per-message dedup keys, or any source-SMS payload referenced by auto-created transactions. After restore, those derived artifacts are re-built by re-running the scan against the device's SMS inbox — the device inbox is the source of truth for *messages*; the backup is the source of truth for *interpretations*.
- Q: What is the accessibility plan for the inline tap-on-`<*>` wildcard mapping UI (which is hostile to screen readers, keyboard / D-pad navigation, and small tap targets)? → A: **None required — this feature is being built for a single user (the project owner) only**. Accessibility (TalkBack, keyboard / D-pad navigation, Switch Access, motor-accessibility tap targets) and broad internationalization are explicitly **out of scope**. The inline tap-the-`<*>` interaction is the only mapping path. If the feature is ever opened up to other users in the future, dual-interaction (inline + enumerated list) and full accessibility coverage MUST be revisited as a separate effort.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Map a discovered template and earn automatic transactions (Priority: P1)

A user installs Ivy Wallet on a phone with months of bank-notification SMS history. They open the SMS Extraction screen, grant READ_SMS permission, and pick a historical scan period (Week / Month / Quarter / Year / All) so the first scan stays bounded. The app then analyzes the in-scope inbox and progressively presents a list of distinct message *templates* it discovers (each a recurring message shape with `<*>` placeholders where the dynamic parts — amounts, merchants, dates, balances — used to be). Even before the scan finishes, the user can pick one template (e.g., the "purchase confirmation" shape from their bank), link the sending sender ID to one of their existing Ivy Wallet wallets, tap the wildcards to label them as **Amount**, **Merchant**, and **Date**, and classify the template as an **Expense**. From that moment on, every time the user opens Ivy Wallet (or taps **Sync SMS** in the dropdown menu), any newly arrived SMS that matches the template becomes a real expense transaction in that wallet, with no further taps.

**Why this priority**: This is the entire value proposition. Without it, the feature delivers nothing. Permission, ingestion, clustering, mapping, classification, and Tier 1 silent capture must all work end-to-end for a single template before anything else matters.

**Independent Test**: Install the app on a device with at least 20 historical bank-notification SMS messages of the same shape. Grant READ_SMS. Confirm the app discovers a template covering those messages. Map its fields, classify, and link to a wallet. Inject a fresh SMS matching the template into the device inbox (or wait for a real one), then re-open Ivy Wallet (or tap **Sync SMS**) and verify a transaction with the correct amount, wallet, currency, and type appears within seconds of the scan trigger, with no further user interaction.

**Acceptance Scenarios**:

1. **Given** the user has granted READ_SMS and has historical bank-notification SMS in their inbox, **When** they open the SMS Extraction screen, **Then** the screen displays a list of discovered templates, each rendered with the literal text preserved and dynamic segments shown as visually distinct `<*>` wildcards.
2. **Given** a discovered template is shown, **When** the user taps a `<*>` wildcard, **Then** a bottom sheet opens listing the supported transaction properties (Amount, Merchant/Description, Date/Time, Reference/Note, "Ignore this wildcard") and the user can pick one to bind to that wildcard.
3. **Given** the user has bound at least one wildcard to **Amount**, picked an account-to-sender link, and chosen a classification (INCOME, EXPENSE, or TRANSFER), **When** they save, **Then** the template becomes "active" and the change is reflected in the template list with a clear "Automated" status badge.
4. **Given** an active template exists for sender "ChaseAlerts" linked to a USD wallet, **When** a new SMS from "ChaseAlerts" matching the template lands in the device inbox AND the user re-opens Ivy Wallet (or taps Sync SMS), **Then** the reconciliation scan creates a Transaction with the parsed amount, the wallet's USD currency, the configured type, and any other mapped fields — silently, without a notification or modal — and the transaction appears in the user's transaction list.
5. **Given** an active template is configured and a matching SMS has been picked up by a scan, **When** the user views the linked wallet's balance, **Then** the auto-created transaction is reflected in the balance using the wallet's currency.
6. **Given** the user has just granted READ_SMS for the first time, **When** the SMS Extraction screen opens, **Then** the screen prompts them to choose a historical scan period (Last Week, Last Month, Last Quarter, Last Year, All Time) before any scan begins; the scan only starts after the user confirms a choice.
7. **Given** a historical scan is in progress (e.g., 1,247 of 4,890 messages processed), **When** the user views the SMS Extraction screen, **Then** templates already discovered are shown in the list and are tappable for mapping; a non-blocking progress indicator displays "1,247 / 4,890 messages"; navigating away from the screen does not abort the scan.
8. **Given** the user originally chose "Last Month" as the historical period and now wants more coverage, **When** they tap "Scan further back…" and pick "Last Year", **Then** the app runs an additional one-shot scan covering only the gap between the prior lower bound (one month ago) and the new lower bound (one year ago), without re-processing already-scanned messages.

---

### User Story 2 - Manually resolve unknown messages from the quarantine queue (Priority: P2)

After the user has been using the feature, their bank sends a message in a new shape (e.g., a refund notification, an international-purchase alert, or a new product launch). The clustering algorithm produces a *new, unmapped* template for it. Because no field mapping exists, the system refuses to guess. Instead, the message is routed to a "Pending Review" queue. The user gets an unobtrusive in-app indicator (a badge with a count). They open the queue, see the new template alongside the raw SMS that triggered it, and either (a) map its fields and classify it like in Story 1, after which all queued messages of this shape are converted to transactions, or (b) dismiss the message as "not a transaction".

**Why this priority**: Banks change message formats; new products produce new formats; international travel triggers shapes the user has never seen. Without a quarantine path, every unknown message either silently disappears (data loss) or gets guessed at (data corruption). Quarantine is the safety net that keeps the Tier 1 silent path trustworthy.

**Independent Test**: With Story 1 already working, send an SMS from a known sender but in a never-seen shape. Verify it does NOT create a transaction, DOES land in the quarantine queue, and that the queue shows the message with its newly-discovered template. Map the template and confirm the queued message (and any subsequent matches) become transactions.

**Acceptance Scenarios**:

1. **Given** an SMS arrives from a sender that has at least one linked account but the message does not match any active (user-mapped) template, **When** ingestion completes, **Then** no transaction is created, AND the message is added to the Pending Review queue, AND a badge counter on the SMS Extraction entry point increments.
2. **Given** the user opens the Pending Review queue, **When** the screen renders, **Then** each pending item shows: the raw SMS text, the auto-discovered template (with `<*>` wildcards), the sender ID, the message timestamp, and an action to "Map this template" or "Dismiss".
3. **Given** the user picks "Map this template" on a pending item, **When** they complete the mapping flow (same flow as Story 1) and save, **Then** the pending message AND all other queued messages matching the same template are converted to transactions, and the queue items are cleared.
4. **Given** the user picks "Dismiss" on a pending item, **When** they confirm, **Then** that single message is removed from the queue with no transaction created, and the template remains unmapped (future messages of this shape continue to be quarantined unless the user maps or blacklists the template).

---

### User Story 3 - Silence noisy templates by blacklisting (Priority: P3)

The user's bank sends frequent non-financial messages from the same sender ID — promotional offers, balance alerts, OTP codes, fraud-warning surveys. Each is its own template. They produce no transactions, but they pile up in the Pending Review queue and create noise. The user toggles "Blacklist" on those templates. From that point, matching messages are silently dropped at the data-source layer: they never enter quarantine, never produce transactions, and never increment the badge.

**Why this priority**: Quality-of-life. The feature works without it, but without it the queue becomes unusable for users whose banks chat constantly. Blacklisting is also the user's lever to express "this template is intentionally not a transaction" — distinct from "this template is not yet mapped".

**Independent Test**: Confirm a non-financial SMS template lands in the quarantine queue (per Story 2). Toggle Blacklist on the template. Send another SMS matching the template. Verify it does NOT appear in the queue, does NOT create a transaction, and does NOT increment any badge.

**Acceptance Scenarios**:

1. **Given** a template is shown in the template list (whether mapped, unmapped, or pending review), **When** the user toggles "Blacklist / Ignore" on it, **Then** the template's status updates to "Blacklisted" and any pending-review items belonging to it are removed from the queue.
2. **Given** a template is marked Blacklisted, **When** an SMS arrives that matches it, **Then** the message is discarded silently — no transaction, no quarantine entry, no badge increment, no notification.
3. **Given** a template is Blacklisted, **When** the user toggles Blacklist back off, **Then** future matching messages once again either create transactions (if mapped) or land in quarantine (if not).

---

### Edge Cases

- **Permission denied or revoked**: The user denies READ_SMS, or grants then later revokes it in OS settings. The feature must show a clear, recoverable empty state explaining what is needed and offering a re-request path. No crashes, no silent failure.
- **Empty SMS inbox**: Permission granted but no messages exist. The template list is empty with an explanatory empty state and a hint that templates will appear on the next launch or after the user taps **Sync SMS**.
- **User attempts to link a sender already linked to another wallet**: The UI MUST refuse the second link, surface a clear inline error naming the wallet currently holding that sender, and offer the user the option to either (a) remove the existing link first, or (b) cancel. See FR-016.
- **Wildcard captures unparseable amount**: A wildcard mapped to "Amount" captures text like "five thousand" or a localized number format the parser cannot handle. The message is routed to quarantine with a flag indicating "amount-parse-failed" rather than silently dropped or assigned a wrong amount.
- **Currency mismatch**: An SMS body contains a currency symbol that does not match the linked account's currency (e.g., "EUR 50.00" but the account is USD). The transaction uses the account's currency by default, and the message is flagged in quarantine for review rather than silently miscategorized.
- **Duplicate SMS**: The same SMS body arrives twice (carrier retry, restore from backup, etc.). The system MUST NOT create duplicate transactions for the same source message.
- **SMS arriving before any account is linked to its sender**: The message is captured into quarantine (or held until a linkable account exists), not dropped. The user can later link an account and resolve the queue.
- **Same template re-clustered slightly differently after more data**: The algorithm produces a *new* template for messages that previously matched an *old* one (e.g., the bank introduces a new optional clause). Both the old user mappings and the queue must remain consistent — the user must not lose configuration work because of re-clustering.
- **Template active, but later edited**: User changes a wildcard mapping or classification. Transactions already created are NOT retroactively rewritten unless the user explicitly opts in to a "re-process historical" action.
- **Phone restore / app reinstall**: After restore, Ivy Wallet's existing backup mechanism brings back the user's *configurations* (templates, sender→wallet links, mappings, classifications, blacklist, watermark) but NOT raw SMS bodies, queue items, or per-message dedup keys. On the next launch, the reconciliation scan re-derives queue items and dedup keys from the device's current SMS inbox; messages referenced by already-existing auto-created transactions are recognized via the watermark and not re-processed.
- **Message exceeding expected length**: Concatenated multi-part SMS (long messages reassembled from segments) are treated as a single message for clustering and parsing.
- **Spoofed sender ID**: A scam SMS impersonates a linked sender. Tier 1 silent creation is therefore restricted to messages that match a *user-mapped* template; arbitrary new message shapes from a known sender are quarantined, not auto-trusted.

## Requirements *(mandatory)*

### Functional Requirements

#### Permission and lifecycle

- **FR-001**: The app MUST request the operating system's READ_SMS permission explicitly before performing any SMS access, with a clear in-app explanation of what the permission is used for and that all processing happens on-device.
- **FR-002**: The app MUST gracefully handle permission denial and post-grant revocation by showing a recoverable empty state and continuing to function for all non-SMS features.
- **FR-003**: All SMS data MUST be processed on-device. The app MUST NOT transmit SMS content, sender IDs, or discovered templates to any external service.

#### Ingestion

- **FR-004**: The app MUST be able to read the device's SMS inbox via an asynchronous operation that returns a typed success/failure result rather than throwing. The same operation seeds the initial template discovery and powers all subsequent reconciliation scans.
- **FR-005**: The app MUST run a **reconciliation scan** of the SMS inbox at every app launch. The scan reads all messages newer than the last-processed watermark (a stable per-message identifier — sender + timestamp + body hash, at minimum) and feeds each newly-found message through the Tier 1 / Tier 2 / Blacklist pipeline. The app MUST NOT register a BroadcastReceiver for incoming SMS, MUST NOT run a foreground or background service, and MUST NOT schedule periodic background work for SMS reconciliation — ingestion is strictly pull-mode.
- **FR-005a**: The app MUST expose a manual **"Sync SMS"** action accessible from a global dropdown / overflow menu, which triggers the same reconciliation scan as FR-005 on demand. The action MUST surface its progress (running / completed / failed with reason) and the count of new transactions or quarantined items it produced.
- **FR-005b**: Before the **first** historical scan begins (immediately after READ_SMS is granted for the first time), the app MUST prompt the user to choose a **historical scan period**: Last Week, Last Month, Last Quarter (3 months), Last Year, or All Time. The first-time scan MUST be bounded by the selected period — only SMS with a timestamp at or after the period's lower bound are read. The chosen period is recorded but does NOT constrain subsequent reconciliation scans (which always use the latest watermark per FR-005).
- **FR-005c**: The app MUST provide a "Scan further back…" action on the SMS Extraction screen that re-prompts the user with the same period choices and, if a longer period is selected, runs an additional one-shot scan covering only the *gap* between the previously-scanned lower bound and the newly-chosen lower bound. The watermark for incremental reconciliation is unaffected by this extension scan.
- **FR-005d**: The historical scan UX MUST be **progressive and non-blocking**: discovered templates MUST render in the template list as they are produced; the SMS Extraction screen MUST show a non-blocking progress indicator displaying "messages processed / total in scope"; the user MUST be able to start mapping any already-discovered template before the scan completes; the user MUST be able to navigate away from the screen and have the scan continue (and resume / complete in the background) without restarting from zero.
- **FR-006**: The app MUST de-duplicate SMS messages by the stable per-message identifier described in FR-005, so the same source message cannot produce more than one transaction or queue entry across multiple scans.
- **FR-007**: The data layer MUST distinguish three failure outcomes — permission denied, transient read error, and storage/persistence error — and surface them as typed, recoverable results to the layers above.

#### Template discovery

- **FR-008**: The app MUST cluster SMS messages into *templates* using an unsupervised, fixed-depth tree-based parsing approach. Messages that share structural shape (same literal tokens in the same positions) MUST land in the same template; messages with structurally different shapes MUST land in different templates.
- **FR-009**: Within each template, segments that vary across messages (numbers, currency amounts, dates, merchant names, reference codes) MUST be replaced with a `<*>` wildcard placeholder. Stable literal text MUST be preserved verbatim.
- **FR-010**: Template discovery MUST be deterministic for a given input set: re-running the algorithm on the same SMS corpus MUST produce the same templates.
- **FR-011**: New incoming messages MUST be matched against existing templates first; only if no match is found should the algorithm create a new template.
- **FR-012**: The system MUST persist discovered templates so that template discovery does not have to be redone on every app launch.

#### Account-to-sender linking

- **FR-013**: An Account in Ivy Wallet MUST be extendable to hold a set of linked SMS sender IDs. A single account MAY link to multiple sender IDs.
- **FR-014**: The user MUST be able to add and remove sender IDs on any account from a configuration screen.
- **FR-015**: When a transaction is created from an SMS, the transaction MUST inherit the linked account's currency. The body of the SMS MUST NOT override the account's currency; if a currency mismatch is detected in the SMS body, the message MUST be quarantined for review (see FR-024) rather than silently coerced.
- **FR-016**: A sender ID MUST be linked to **at most one wallet** at any given time. The link configuration UI MUST enforce this by preventing the user from attaching a sender to a second wallet while it is already attached to another wallet, surfacing a clear inline error that names the currently-linked wallet and offers a path to remove the existing link before retrying. The reverse direction is unconstrained — a single wallet MAY hold multiple linked sender IDs (per FR-013).

#### Template configuration UI

- **FR-017**: The user MUST be able to view the list of discovered templates with their current state: Unmapped, Active (mapped + classified), Blacklisted, or Pending Review.
- **FR-018**: The user MUST be able to tap an individual `<*>` wildcard within a rendered template to open a mapping bottom sheet.
- **FR-019**: The mapping bottom sheet MUST allow binding a wildcard to one of: Amount, Merchant/Description, Date/Time, Reference/Note, or "Ignore this wildcard". Each non-ignored mapping target MAY be used at most once per template; "Ignore this wildcard" may be applied to any number of wildcards. Account Identifier and Currency are NOT mapping targets — wallet selection is determined by the sender→wallet link (per FR-016) and currency is inherited from the linked wallet (per FR-015).
- **FR-020**: The user MUST be able to classify a template as INCOME, EXPENSE, or TRANSFER. A template MUST have a classification before it can become Active.
- **FR-021**: The user MUST be able to toggle a "Blacklist / Ignore" state on any template, available from both the template list and the Pending Review queue.
- **FR-022**: The user MUST be able to edit an Active template's mappings or classification at any time; subsequent SMS use the new mappings, but transactions already created MUST NOT be silently rewritten.

#### Tier 1 — Silent automatic capture

- **FR-023**: When an incoming SMS exactly matches an Active (user-mapped + classified, non-blacklisted) template AND the sender ID is linked to a wallet (per FR-016, this is unambiguous because senders are 1:1 with wallets) AND every required field (Amount at minimum) is parseable, the system MUST create a Transaction silently — no notification, no modal, no user prompt — using the parsed fields and the linked wallet's currency.

#### Tier 2 — Quarantine

- **FR-024**: When an incoming SMS does NOT meet all Tier 1 conditions (no matching Active template, sender not linked to any wallet, unparseable required field, currency mismatch, etc.), the system MUST route the message to a Pending Review queue rather than guess at the transaction details.
- **FR-025**: The Pending Review queue MUST display each pending item with: the raw SMS text, the auto-discovered template, the sender ID, the message timestamp, and the reason it was quarantined (e.g., "Template not yet mapped", "Amount could not be parsed", "Sender not linked to any wallet", "Currency mismatch").
- **FR-026**: From the Pending Review queue, the user MUST be able to: (a) map the template (which converts this and all other queued messages of the same template), (b) dismiss a single message, or (c) blacklist the template (which clears all queued messages of that template).
- **FR-027**: The app MUST surface the count of Pending Review items as a passive in-app badge. The app MUST NOT generate an OS-level notification for each quarantined message.

#### Blacklist

- **FR-028**: When an incoming SMS matches a Blacklisted template, the system MUST drop the message at the data-source layer. No transaction is created, no queue entry is added, no badge increments.
- **FR-029**: Toggling Blacklist OFF on a template MUST restore normal Tier 1 / Tier 2 routing for future messages but MUST NOT retroactively process messages that were dropped while the template was Blacklisted.

#### Data integrity

- **FR-030**: Auto-created transactions MUST be visually distinguishable from manually-entered transactions in the transaction list (e.g., a small "from SMS" indicator), and tapping one MUST allow the user to view the source SMS and the template that produced it.
- **FR-031**: The user MUST be able to delete an auto-created transaction. Deletion MUST NOT cause the source SMS to re-trigger creation; the system MUST track that the source SMS has been "user-rejected" and not re-process it.
- **FR-032**: The user MUST be able to "re-process historical messages" against an updated template as an explicit, opt-in action. This action MUST clearly preview how many new transactions it will create and require confirmation before writing.

#### Backup and restore

- **FR-033**: Ivy Wallet's existing backup / export format MUST be extended to include the user's SMS-extraction *configurations*: discovered SmsTemplate structural patterns, wildcard-to-property mappings, classifications, blacklist flags, sender→wallet links, and the latest per-template scan watermark. Auto-created Transactions remain part of the existing transaction backup as normal transactions, retaining their source-link reference IDs.
- **FR-034**: The backup format MUST NOT include raw SMS body text, PendingReviewItem records, or per-message dedup keys. After restore, these derived artifacts MUST be re-populated by re-running the reconciliation scan against the device's current SMS inbox. The watermark restored from backup MUST be honored so already-processed messages are not re-processed.

### Key Entities *(include if feature involves data)*

- **SmsMessage**: A single text message read from the device inbox, identified by sender ID, timestamp, body, and a stable de-duplication key. Holds the relationship to the template it currently matches (or `null` if not yet clustered).
- **SmsTemplate**: An algorithmically-discovered message shape with literal tokens and `<*>` wildcards. Holds: structural pattern, list of wildcard positions, current state (Unmapped / Active / Blacklisted), the user's wildcard-to-property mappings, and the classification (INCOME / EXPENSE / TRANSFER) when set.
- **SenderAccountLink**: An association between a sender ID (string) and a single wallet (Account). Cardinality is **many-to-one** (many senders → one wallet, never the reverse): a sender ID is unique across all links, enforced at the storage layer with a uniqueness constraint on sender ID. Per FR-016, the UI also enforces this before write.
- **Account / Wallet** *(extension of existing entity)*: Now also holds a set of linked sender IDs. All other existing semantics — currency, balance, type — are preserved unchanged. (Ivy Wallet treats every account as a "wallet" — there is no separate credit-card or sub-account type.)
- **Transaction** *(existing entity, no schema change required)*: When auto-created, carries a reference back to the source SmsMessage and the SmsTemplate that produced it, so the user can audit origin.
- **PendingReviewItem**: A queued SmsMessage awaiting user resolution. Holds the message, its discovered template, the quarantine reason, and the time it entered the queue.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: After granting permission on a device with at least 100 historical bank-notification SMS messages, **at least 95% of those messages** are assigned to a discovered template (the rest may remain as singletons), with **no duplicates** of the same structural shape across separate templates.
- **SC-002**: A user can take one previously-unseen template from "discovered" to "active and producing transactions" — including linking an account, mapping fields, and classifying — in **under 90 seconds**.
- **SC-003**: When the user opens Ivy Wallet (or taps Sync SMS) and the inbox contains messages matching active templates, the resulting transactions appear in the transaction list within **3 seconds** of the scan starting (for an incremental delta of up to ~50 new messages on a typical mid-range Android device). Latency is measured from scan trigger, not from SMS arrival, because the system is pull-mode.
- **SC-004**: Across a 4-week real-world usage period, **zero transactions** are auto-created from messages that did not exactly match an Active user-mapped template (i.e., Tier 1 has a 0% guess rate; ambiguity always defers to Tier 2).
- **SC-005**: Of all SMS messages from senders linked to at least one account, **at least 90%** are routed deterministically to one of the three terminal states (transaction created, quarantined, or blacklisted) without crash, retry, or being silently lost.
- **SC-006**: Pending Review queue resolution: **at least 80%** of items added to the queue can be cleared by mapping a single template (versus dismissing one-by-one), confirming the clustering granularity is useful and not over-fragmented.
- **SC-007**: After mapping a template, the user can verify a real transaction was created — with the correct amount, currency, account, and type — **on the very first matching message** with no further configuration. (First-match success rate target: ≥ 98% across the user's mapped templates.)
- **SC-008**: For a "Last Year" historical scan covering up to 5,000 in-scope SMS on a typical mid-range Android device, **the first discovered template is tappable for mapping within 3 seconds** of scan start, and the full scan completes in **under 30 seconds**. The scan never blocks the UI; the user can navigate away and back without restarting it.
- **SC-009**: After the first historical scan completes, **subsequent reconciliation scans** (on app launch or via Sync SMS) processing an incremental delta of up to 50 newly-arrived messages complete in **under 1 second** on a typical mid-range Android device.

## Assumptions

- The feature targets sideloaded / F-Droid distribution. The READ_SMS permission is therefore available; the implementation does not need to design around Google Play's restricted-permission policies.
- All processing — ingestion, clustering, mapping, transaction creation — happens on-device. No cloud, no network calls, no telemetry of SMS content.
- The user is the sole, trusted owner of the device; SMS content is treated as personal data and never leaves the device.
- The feature integrates with Ivy Wallet's existing Account and Transaction domain entities. The Account entity is extended to hold a set of linked sender IDs; the Transaction entity is unchanged structurally and only gains an optional source-reference for SMS-originated transactions.
- The existing technology stack (100% Kotlin, Jetpack Compose UI, ArrowKt for typed result handling, Onion Architecture) is non-negotiable. All new code MUST conform to the project's constitution.
- Template discovery uses an unsupervised, fixed-depth tree-based clustering approach (Drain-style log parsing) ported to Kotlin. The user explicitly chose this algorithm; alternative algorithms are out of scope for this feature.
- Currency is determined by the linked Account, not parsed from SMS body text. Any SMS-body currency that contradicts the account currency is treated as a quarantine signal, not a override.
- "Tier 1 silent capture" means *no UI interruption* for the success path. It does NOT mean "no audit trail" — auto-created transactions remain inspectable, deletable, and traceable back to their source SMS.
- A first version of the feature targets a single-language SMS corpus (the user's primary phone language). Multi-language template discovery and mapping refinements are out of scope for this iteration.
- Retroactive re-processing of historical messages after a mapping change is opt-in and never automatic, to avoid silently overwriting the user's transaction history.
- The feature does not attempt to detect or block phishing/spoofed SMS beyond the natural protection of "Tier 1 only fires for messages exactly matching a user-mapped template" — full anti-phishing analysis is out of scope.
- **Single-user (personal) deployment**: This feature is built for the project owner's personal use, not for distribution to other Ivy Wallet users in this iteration. Accessibility (TalkBack / screen reader, keyboard / D-pad navigation, Switch Access, motor-accessibility tap targets) and broad internationalization are therefore **out of scope**. UX assumes a sighted touch user familiar with the application. If the feature is later promoted for general distribution, accessibility and i18n MUST be revisited.
