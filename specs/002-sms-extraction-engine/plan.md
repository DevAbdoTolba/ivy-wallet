# Implementation Plan: SMS Extraction Engine

**Branch**: `002-sms-extraction-engine` | **Date**: 2026-04-27 | **Spec**: [spec.md](./spec.md)
**Input**: Feature specification from `/specs/002-sms-extraction-engine/spec.md`

## Summary

Add an on-device, pull-only SMS extraction engine to Ivy Wallet: a `feature:sms-sync` module that (1) reads the device SMS inbox via the system ContentProvider on every app launch and on demand from a "Sync SMS" overflow menu item, (2) clusters messages into structural templates using a Kotlin port of the Drain log-parsing algorithm, (3) lets the user tap inline `<*>` wildcards in a Compose screen to map them to Transaction properties (Amount / Merchant / Date / Reference) and classify the template as INCOME / EXPENSE / TRANSFER, (4) silently auto-creates Transactions when an SMS exactly matches a user-mapped Active template (Tier 1) or routes the message to a Pending Review queue otherwise (Tier 2), and (5) extends the existing JSON backup to include the user's SMS configurations. No BroadcastReceiver, no foreground service, no scheduled work — strictly pull-mode. Sender ID → wallet is constrained 1:1 by a `UNIQUE` SQL index. Single-user / personal-deployment scope: accessibility and i18n are explicitly out of scope.

## Technical Context

**Language/Version**: Kotlin (project default, latest LTS as configured in `buildSrc`)
**Primary Dependencies**: Jetpack Compose, ArrowKt (`Either` + `either { }` DSL), Hilt, Room, Material3, kotlinx.serialization, Coroutines
**Storage**: Room — existing `IvyRoomDatabase` (currently v131). This feature adds **migration 131 → 132** introducing three new tables and one new index. No schema change to existing `AccountEntity` (the wallet → sender relationship is modeled in a join table).
**Testing**: JUnit + Kotest for business logic; Paparazzi (via `PaparazziScreenshotTest` base class with `TestParameterInjector` for theme variants) for Compose screens. Test sources live in `src/test/java/`.
**Target Platform**: Android (sideloaded / F-Droid build). `READ_SMS` runtime permission requested at first entry to the SMS extraction screen. No Google Play distribution constraints apply.
**Project Type**: Mobile (multi-module Android Gradle project)
**Performance Goals**: First template tappable within **3 s** of scan start (per SC-008), full bounded "Last Year" scan of up to 5 000 messages in **< 30 s**, incremental reconciliation scan of ≤ 50 new messages in **< 1 s** (SC-009). Tier 1 transaction creation has a **0 % guess rate** (SC-004).
**Constraints**: Pull-only ingestion (no `BroadcastReceiver`, no foreground / background service, no `WorkManager` job — explicit constitution-compliance choice). All SMS processing on-device, no network. Drain clustering and inbox reads run on `Dispatchers.IO`; UI never blocks. ArrowKt `Either<String, T>` for all data-source operations (no thrown exceptions). Sender-ID-to-wallet relationship enforced 1:1 at both DB (`UNIQUE` index) and UI (pre-write check with inline error). Single-user scope — no a11y, no i18n.
**Scale/Scope**: Single user. Initial inbox up to ~5 000 SMS in user-bounded scan window (Week / Month / Quarter / Year / All). New module: 1 (`feature:sms-sync`). New Compose screens: 4 (Permission gate, Period picker, Template list, Pending Review). New domain entities: 3 (`SmsTemplate`, `SenderAccountLink`, `PendingReviewItem`). New use cases: ~8.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Compliance Plan |
|-----------|-----------------|
| **I. Pragmatic Simplicity (Grug Brained)** | Pull-only model deliberately rejects the more "elegant" but operationally fragile real-time `BroadcastReceiver`. Drain algorithm chosen because it has a fixed-depth tree (small, comprehensible) — not deep-learning clustering. Reuse existing repository/mapper/DAO patterns instead of inventing new abstractions. No new DI scope, no new background framework. |
| **II. Layered Unidirectional Architecture** | Strict three-layer split inside `feature:sms-sync`: **Data** (`SmsInboxDataSource` reading the system ContentProvider, three Room DAOs, mappers Entity↔Domain) → **Domain** (use cases: `ScanInboxUseCase`, `DiscoverTemplatesUseCase` wrapping the Drain parser, `MapTemplateUseCase`, `RouteSmsUseCase` for Tier 1 / Tier 2 / Blacklist, `CreateTransactionFromSmsUseCase`, `BlacklistTemplateUseCase`, `ResolvePendingItemUseCase`, `ExtendScanPeriodUseCase`) → **UI** (Compose screens + ViewModels exposing immutable `*ViewState` + sealed `*Event`). Mappers are explicit: `SmsRow → SmsMessage → SmsMessageViewState`. |
| **III. Type-Safe Error Handling** | Every fallible boundary returns `Either<String, T>` using the existing `either { }` DSL: ContentProvider read (permission denied / cursor error), Drain parse (malformed body), DAO writes (constraint violation), backup encode/decode (parse error). No throwing for expected failures. The three failure outcomes from FR-007 (permission denied, transient read error, storage error) are encoded as distinct `String` prefixes consumable by the UI layer. |
| **IV. Main-Safe Operations** | All inbox reads, Drain clustering, DAO writes, and backup serialization wrapped in `withContext(dispatchers.io)` (per the existing `AppCoroutinesDispatchers` injection pattern). Compose ViewModels emit `StateFlow<ViewState>` only — never block the UI thread. The progressive scan emits incremental results via a `Flow<ScanProgress>`. |
| **V. Testing & Quality Assurance** | Unit tests (JUnit + Kotest) for: Drain parser (deterministic given a fixed corpus), template-matching, sender-link uniqueness validator, Tier 1/2/Blacklist routing logic, mapper round-trips. Paparazzi snapshot tests for: Permission gate screen, Period picker, Template list (3 states: empty / scanning / discovered), Mapping bottom sheet, Pending Review queue. Manual on-device verification with synthesized SMS injection. |

**Initial result: PASS** — no violations. Re-checked after Phase 1 design: still PASS (see end of this document).

## Project Structure

### Documentation (this feature)

```text
specs/002-sms-extraction-engine/
├── plan.md              # This file
├── research.md          # Phase 0 output — Drain algorithm details, ContentProvider patterns, Compose AnnotatedString
├── data-model.md        # Phase 1 output — entity definitions + migration v131→v132
├── quickstart.md        # Phase 1 output — how to build, run, and test the feature locally
├── contracts/           # Phase 1 output — public interfaces exposed to other modules
│   ├── sms-sync-public-api.md      # Use cases callable from app/feature:home (e.g. SyncSmsUseCase)
│   ├── backup-extension.md         # Extension points on BackupDataUseCase / IvyWalletCompleteData
│   └── domain-extensions.md        # New fields on Account (linked sender IDs) + Transaction.metadata
├── checklists/
│   └── requirements.md  # Already created by /speckit-specify and updated by /speckit-clarify
└── tasks.md             # Created later by /speckit-tasks (NOT this command)
```

### Source Code (repository root) — new and modified files only

```text
# New module
feature/sms-sync/
├── build.gradle.kts                         # NEW — mirrors feature/transactions/build.gradle.kts
└── src/main/
    ├── AndroidManifest.xml                  # NEW — declares <uses-permission android:name="android.permission.READ_SMS"/>
    └── java/com/ivy/sms/
        ├── data/
        │   ├── SmsInboxDataSource.kt        # NEW — wraps content://sms/inbox; returns Either
        │   ├── DrainParser.kt               # NEW — fixed-depth tree clustering port of Drain
        │   ├── DrainParserState.kt          # NEW — serializable parser state (root tree + clusters)
        │   ├── SmsMessageMapper.kt          # NEW — SmsRow → SmsMessage (domain)
        │   ├── SmsTemplateMapper.kt         # NEW — Entity ↔ Domain
        │   └── SenderAccountLinkMapper.kt   # NEW — Entity ↔ Domain
        ├── domain/
        │   ├── model/                       # Domain types (SmsMessage, SmsTemplate, ScanPeriod, …)
        │   ├── usecase/
        │   │   ├── ScanInboxUseCase.kt              # NEW — orchestrates one scan run
        │   │   ├── DiscoverTemplatesUseCase.kt      # NEW — feeds messages through DrainParser
        │   │   ├── RouteSmsUseCase.kt               # NEW — Tier 1 / Tier 2 / Blacklist routing
        │   │   ├── MapTemplateUseCase.kt            # NEW — persists wildcard mapping + classification
        │   │   ├── CreateTransactionFromSmsUseCase.kt
        │   │   ├── BlacklistTemplateUseCase.kt
        │   │   ├── ResolvePendingItemUseCase.kt
        │   │   ├── LinkSenderToWalletUseCase.kt     # NEW — enforces 1:1 with inline error
        │   │   ├── ExtendScanPeriodUseCase.kt
        │   │   └── SyncSmsUseCase.kt                # NEW — public entry point used by app + HomeMoreMenu
        │   └── parser/
        │       ├── AmountParser.kt          # NEW — locale-tolerant numeric extraction
        │       └── DateTimeParser.kt        # NEW — common bank SMS date formats → Instant
        ├── ui/
        │   ├── permission/                  # NEW — Compose permission gate screen
        │   ├── period/                      # NEW — Period picker (Week/Month/Quarter/Year/All)
        │   ├── templates/                   # NEW — Template list + mapping bottom sheet
        │   ├── pending/                     # NEW — Pending Review queue screen
        │   └── nav/                         # NEW — feature route definitions
        ├── di/
        │   └── SmsSyncModule.kt             # NEW — Hilt bindings for data sources, parsers, dispatcher
        └── startup/
            └── SmsSyncAppStartup.kt         # NEW — invoked from IvyAndroidApp.onCreate()

# Modified existing files
shared/data/core/src/main/java/com/ivy/data/db/
├── IvyRoomDatabase.kt                       # MODIFIED — bump version 131 → 132; add 3 new entities
├── migration/Migration131to132_SmsExtraction.kt   # NEW — creates 3 tables + UNIQUE index
├── entity/SmsTemplateEntity.kt              # NEW
├── entity/SenderAccountLinkEntity.kt        # NEW (UNIQUE index on senderId)
├── entity/PendingReviewItemEntity.kt        # NEW
├── dao/read/ReadSmsTemplateDao.kt           # NEW
├── dao/read/ReadSenderAccountLinkDao.kt     # NEW
├── dao/read/ReadPendingReviewItemDao.kt     # NEW
├── dao/write/WriteSmsTemplateDao.kt         # NEW
├── dao/write/WriteSenderAccountLinkDao.kt   # NEW
└── dao/write/WritePendingReviewItemDao.kt   # NEW

shared/data/core/src/main/java/com/ivy/data/di/
└── RoomDbModule.kt                          # MODIFIED — provide the 6 new DAOs

shared/data/core/src/main/java/com/ivy/data/backup/
└── BackupDataUseCase.kt                     # MODIFIED — extend IvyWalletCompleteData with smsTemplates + senderAccountLinks; export/import logic

shared/data/model/src/main/kotlin/com/ivy/data/model/
└── Transaction.kt                            # MODIFIED — add `smsSourceId: UUID?` and `smsTemplateId: UUID?` to TransactionMetadata

# Module-graph wiring
settings.gradle.kts                          # MODIFIED — register `:feature:sms-sync`
app/build.gradle.kts                         # MODIFIED — depend on `:feature:sms-sync`

# UI wiring
feature/home/src/main/java/com/ivy/home/HomeMoreMenu.kt   # MODIFIED — add "Sync SMS" entry to QuickAccess
app/src/main/java/com/ivy/wallet/IvyAndroidApp.kt         # MODIFIED — call SmsSyncAppStartup in onCreate
shared/ui/navigation/...                                  # MODIFIED — add SMS extraction destinations
```

**Structure Decision**: A new isolated feature module **`feature:sms-sync`** following the existing pattern of `feature:transactions` and `feature:accounts`. Persistence (entities, DAOs, migration) lives in the existing `shared:data:core` module to keep the database schema centralized. Domain models that are exposed across modules (e.g., the new `Transaction.metadata` fields) live in `shared:data:model`. The feature module owns the Drain parser, all UI, all domain use cases, and one Hilt module. The startup hook is a thin `SmsSyncAppStartup` class invoked from `IvyAndroidApp.onCreate()`. The "Sync SMS" entry point is added to the existing `HomeMoreMenu.QuickAccess()` composable so we don't introduce a new global UI surface.

## Phase 0: Outline & Research — completed

See [research.md](./research.md). Topics resolved:

1. **Drain log parsing algorithm** — choice of token-count + first-N tokens as parse-tree key; depth fixed at 4; similarity threshold = 0.5; trade-offs vs. word-vector / LCS approaches.
2. **Android SMS ContentProvider semantics** — column names, ordering, multipart re-assembly, watermark candidates, performance characteristics (cursor pagination).
3. **Jetpack Compose inline tappable wildcards** — `AnnotatedString` + `pushStringAnnotation` + `ClickableText` (or `pointerInput` with `TextLayoutResult.getOffsetForPosition`) — pattern chosen and rejected alternatives recorded.
4. **READ_SMS runtime permission UX** — `ActivityResultContracts.RequestPermission` from a Composable, lifecycle-aware re-checking after returning from system settings.
5. **Bounded historical scan SQL strategy** — `WHERE date >= ?` query against the SMS provider with an `ORDER BY date ASC LIMIT N` cursor for incremental progress emission.
6. **App-launch hook** — invoking a Hilt-injected `SmsSyncAppStartup` from `IvyAndroidApp.onCreate()` is preferred over `AppStarter` interface or Activity-level hooks (rationale in research.md).
7. **Backup-format extension** — appending two new top-level keys (`smsTemplates`, `senderAccountLinks`) to `IvyWalletCompleteData` is backward-compatible with existing decoder (kotlinx.serialization ignores unknown keys with `ignoreUnknownKeys = true` configured globally; verified in research).

All `NEEDS CLARIFICATION` from the spec are now resolved (the single FR-016 marker was eliminated during `/speckit-clarify`).

**Output**: [research.md](./research.md)

## Phase 1: Design & Contracts — completed

**Prerequisites**: research.md complete ✓

1. **Data model** → [data-model.md](./data-model.md) — three new Room entities with their full DDL, the v131→v132 migration body, the join-table approach for sender→wallet, the watermark column placement, the new `TransactionMetadata` fields, and the field-by-field `SmsTemplate` state-machine rules.

2. **Interface contracts** → [contracts/](./contracts/):
   - **`sms-sync-public-api.md`** — public surface of `feature:sms-sync` consumed by `app` and `feature:home` (the `SyncSmsUseCase`, the navigation routes, the `SmsSyncAppStartup` interface).
   - **`backup-extension.md`** — additions to `BackupDataUseCase` and the JSON shape changes (with backward-compat strategy).
   - **`domain-extensions.md`** — modifications to `Transaction.metadata` and the new `SenderAccountLinkRepository` shape.

3. **Quickstart** → [quickstart.md](./quickstart.md) — step-by-step: how to build the module locally, how to inject test SMS into the emulator inbox via `adb shell content insert`, how to verify the end-to-end Tier 1 path manually, and how to run the Paparazzi snapshot tests.

4. **Agent context update** — `CLAUDE.md` updated to reference this plan between the SPECKIT markers (one-line pointer; the plan itself is the source of truth).

**Output**: data-model.md, contracts/*, quickstart.md, updated CLAUDE.md

## Constitution Re-check (post-design)

| Principle | Status | Notes |
|-----------|--------|-------|
| **I. Pragmatic Simplicity** | ✓ PASS | All choices in Phase 1 favor existing patterns (join table over schema mutation, `Either<String, T>` over custom error sealed class, in-place backup-format extension over a v2 format). |
| **II. Layered Architecture** | ✓ PASS | Three-layer split is sustained in `feature:sms-sync`. `shared:data:core` retains DB ownership; `feature:sms-sync` does not declare its own DB. |
| **III. Type-Safe Error Handling** | ✓ PASS | Public use cases all return `Either<String, T>`. Permission denial / read errors / persistence errors carry distinct `String` prefixes (`"PERMISSION_DENIED:"`, `"READ_ERROR:"`, `"STORAGE_ERROR:"`) — see contracts/sms-sync-public-api.md. |
| **IV. Main-Safe** | ✓ PASS | `SmsSyncAppStartup` dispatches the launch scan via `withContext(dispatchers.io)`; the Compose layer only collects a `StateFlow<ScanProgress>`. |
| **V. Testing** | ✓ PASS | Drain parser is deterministic and unit-testable in isolation (no Android dependencies). UI screens map cleanly to Paparazzi snapshot tests. |

**Final result: PASS — no violations to track.**

## Complexity Tracking

> No violations. Section intentionally empty.

| Violation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| _(none)_  | _(n/a)_    | _(n/a)_                             |
