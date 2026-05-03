# SMS Extraction Engine — User Flow

**Audience**: implementer + reviewer
**Goal**: one-page picture of how the user moves from "open Ivy Wallet" to "auto-transactions land in my wallet" after the 2026-04-28 redesign. The point is a fast, predictable, per-wallet path with no global SMS surface.

## Top-level flow

```mermaid
flowchart TD
    Start([User opens Ivy Wallet]) --> Launch{Any wallet has<br/>linked sender?}
    Launch -- yes --> LaunchScan[Per-sender scan in IO scope<br/>silent + non-blocking]
    Launch -- no --> Home[Home / Accounts / etc.]

    LaunchScan -->|success| Tier1[Tier 1: matching SMS →<br/>auto-create Transaction]
    LaunchScan -->|new shape| Tier2[Tier 2: queue in<br/>per-wallet Pending Review]
    LaunchScan -->|fail| StatusFail[Last-sync row =<br/>Last sync failed: reason]
    Tier1 --> Home
    Tier2 --> Home
    StatusFail --> Home

    Home --> EditWallet[User taps wallet → Edit modal]
    EditWallet --> Section{Has linked<br/>sender already?}

    Section -- no --> BtnLink[Show: Link SMS chat]
    BtnLink --> SenderPicker[Sender picker:<br/>top 10 senders + free-text input]
    SenderPicker -->|free text| Validate{Sender exists<br/>in inbox?}
    Validate -- no --> ErrNoSender[Inline error:<br/>No messages from this sender]
    Validate -- yes --> SaveLink
    SenderPicker -->|tap top-10| SaveLink[Save SenderAccountLink<br/>FR-016 1:1 enforced]
    ErrNoSender --> SenderPicker
    SaveLink -->|conflict: sender<br/>linked elsewhere| ErrConflict[Inline error:<br/>already linked to wallet X]
    ErrConflict --> SenderPicker
    SaveLink --> PeriodPicker[Pick scan period<br/>Week/Month/Quarter/Year/All]

    Section -- yes --> SectionLinked[Show:<br/>Linked sender chip<br/>Last-sync status row<br/>Sync now button<br/>Templates: N<br/>Pending Review: N<br/>Unlink]

    PeriodPicker --> FirstScan[First-time per-sender scan]
    FirstScan -->|progressive| TemplateList

    SectionLinked -->|tap Templates: N| TemplateList[Per-wallet Template List<br/>rollup rows: N msgs match this shape]
    SectionLinked -->|tap Pending Review: N| Pending[Pending Review queue]
    SectionLinked -->|Sync now| ManualScan[Manual per-sender scan]
    ManualScan -->|complete| StatusOK[Last-sync row updates<br/>+ snackbar X new / Y queued]
    StatusOK --> SectionLinked

    TemplateList -->|tap a template| Mapping[Template Mapping screen]
    Mapping -->|tap highlighted token| Keypad[Project keypad picker:<br/>Income / Expense / Transfer /<br/>Current Total / Tx Fee /<br/>Date / Merchant / Ignored]
    Keypad --> Mapping
    Mapping -->|Save<br/>amount role bound| Active[Template ACTIVE<br/>queued msgs convert]
    Active --> TemplateList

    Mapping -->|Ignore template forever| Confirm{ACTIVE template?}
    Confirm -- yes --> ConfirmDialog[Confirm dialog]
    Confirm -- no --> Blacklist
    ConfirmDialog -->|confirm| Blacklist[Template BLACKLISTED<br/>queue cleared]
    Blacklist --> TemplateList

    Pending -->|Map this template| Mapping
    Pending -->|Dismiss this message| RemoveOne[Single row removed]
    Pending -->|Ignore template forever| Blacklist
    RemoveOne --> Pending

    TemplateList -->|Scan further back| ExtendPicker[Pick longer period]
    ExtendPicker --> ExtendScan[Gap-only one-shot scan]
    ExtendScan --> TemplateList
```

## Why this flow is fast and reliable

- **No global surface.** All entry points live inside the wallet edit modal — the user never has to think "where is the SMS feature?" The wallet they care about IS the SMS context.
- **One sender per wallet.** Removes ambiguity. The `address = ?` filter on every SMS read keeps cursor scans tight (typically dozens of rows, not thousands).
- **Pull-only with launch sync + manual fallback.** No `BroadcastReceiver`, no `WorkManager`. If the launch scan fails, the last-sync row tells the user, and the "Sync now" button is the recovery path. No silent retries.
- **Per-sender watermark.** Each linked sender has its own last-processed timestamp; one sender's failures never block another wallet's progress.
- **Mark, don't remove.** Tokens are highlighted in their original example value, not replaced with `<*>`. The user reads natural-looking SMS text and recognizes the dynamic parts by color.
- **Eight-role keypad, no second classification step.** The amount role IS the transaction type. Saving requires only: pick the amount role + (optional) date / merchant / etc.
- **One ignore-forever button.** "Ignore this template forever" is the same destination from the template list, the mapping screen, and any pending-review item — the user never has to learn a different word for the same outcome.

## Per-screen render direction

`LayoutDirection` is decided at the body level by the first non-whitespace character:

| First char in inbox block | Direction | Notes |
|---|---|---|
| `؀`–`ۿ` (Arabic) | Rtl | Includes Arabic-Indic digits |
| `ݐ`–`ݿ` (Arabic Supplement) | Rtl | |
| `ﭐ`–`﷿`, `ﹰ`–`﻿` (Arabic Pres. Forms) | Rtl | |
| anything else | Ltr | Latin, Cyrillic, digits, punctuation |

Per-message check at render time. Mixed-script bodies follow the first character (acceptable for single-user scope).

## State machine for `SmsTemplate`

```mermaid
stateDiagram-v2
    [*] --> UNMAPPED: Drain discovers shape
    UNMAPPED --> ACTIVE: user binds amount role + saves
    UNMAPPED --> BLACKLISTED: Ignore template forever
    UNMAPPED --> PENDING_REVIEW: SMS arrives, no mapping yet
    PENDING_REVIEW --> ACTIVE: user maps from queue
    PENDING_REVIEW --> BLACKLISTED: Ignore template forever
    ACTIVE --> ACTIVE: user edits role bindings (no kind change)
    ACTIVE --> BLACKLISTED: Ignore template forever (with confirm)
    BLACKLISTED --> UNMAPPED: Un-ignore
    ACTIVE --> [*]: template deleted (reclustered)
```

Notes:
- `BLACKLISTED → ACTIVE` is intentionally **not** a direct edge. After un-ignoring, the user must re-bind roles and re-save.
- A template's kind (Income / Expense / Transfer) is implied by which amount-role wildcard is bound; there is no separate enum field on the template.

## Routing one incoming SMS

```mermaid
flowchart LR
    SMS([New SMS row]) --> InWindow{address = linked<br/>sender for any wallet?}
    InWindow -- no --> Drop1[Ignored at data source]
    InWindow -- yes --> Dedup{Already in dedup<br/>or user-rejected?}
    Dedup -- yes --> Drop2[Skip]
    Dedup -- no --> Match{Match a template<br/>for this sender?}
    Match -- BLACKLISTED --> Drop3[Drop silently]
    Match -- ACTIVE + parse OK --> Tx[Tier 1: write Transaction]
    Match -- ACTIVE + parse fail --> Q1[Queue: AMOUNT_NOT_PARSEABLE]
    Match -- UNMAPPED --> Q2[Queue: TEMPLATE_NOT_MAPPED]
    Match -- new shape --> NewT[Create UNMAPPED template<br/>+ Queue: TEMPLATE_NOT_MAPPED]
```

Currency-mismatch detection runs as part of "parse OK" — a body currency that contradicts the wallet's currency drops the row to the queue with `CURRENCY_MISMATCH`.
