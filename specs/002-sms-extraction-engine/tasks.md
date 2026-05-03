---

description: "Tasks for SMS Extraction Engine"
---

# Tasks: SMS Extraction Engine

**Input**: Design documents from `/specs/002-sms-extraction-engine/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md (all present)

**Tests**: Tests are INCLUDED. Constitution Principle V (Testing & Quality Assurance) makes them mandatory: JUnit + Kotest for business logic, Paparazzi for Compose screens. Tasks for tests appear inline within each story.

**Organization**: Tasks are grouped by user story (US1 = MVP, US2 = quarantine, US3 = blacklist) so each can be implemented and shipped independently.

## Format: `[ID] [P?] [Story?] Description`

- **[P]**: Can run in parallel (different files, no in-flight dependencies)
- **[Story]**: Which user story (US1 = P1 MVP, US2 = P2 quarantine, US3 = P3 blacklist)
- File paths are exact and absolute-from-repo-root.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Stand up the empty `feature:sms-sync` module so all subsequent code has a place to live.

- [X] T001 Register the new module in `settings.gradle.kts` by adding the line `include(":feature:sms-sync")` alphabetically among the existing `:feature:*` entries.
- [X] T002 Create `feature/sms-sync/build.gradle.kts` mirroring the structure of `feature/transactions/build.gradle.kts`. Declare dependencies on `:shared:data:core`, `:shared:data:model`, `:shared:ui:core`, `:shared:ui:navigation`, `:shared:base`, plus the standard Compose / Hilt / Coroutines / ArrowKt entries used across feature modules.
- [X] T003 [P] Create `feature/sms-sync/src/main/AndroidManifest.xml` declaring `<uses-permission android:name="android.permission.READ_SMS" />` and an empty `<application />` stub.
- [X] T004 Add `implementation(projects.feature.smsSync)` to `app/build.gradle.kts` in the dependencies block, in the same alphabetical group as the other `feature:*` projects.
- [X] T005 Create the package skeleton under `feature/sms-sync/src/main/java/com/ivy/sms/` with empty subpackages: `data/`, `domain/model/`, `domain/usecase/`, `domain/parser/`, `ui/permission/`, `ui/period/`, `ui/templates/`, `ui/pending/`, `ui/nav/`, `di/`, `startup/` (use `.gitkeep` files only — no Kotlin files yet).

**Checkpoint**: `./gradlew :feature:sms-sync:assembleDebug` succeeds; module is empty but valid.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Everything every user story depends on — entities, migration, DAOs, mappers, parsers, permission machinery, the routing skeleton, and the launch hook. Once this phase is done, US1 / US2 / US3 can be worked on in any order.

**⚠️ CRITICAL**: No user story work begins until this phase is complete.

### 2.1 Persistence layer (Room schema bump v131 → v132)

- [X] T006 [P] Create `shared/data/core/src/main/java/com/ivy/data/db/entity/SmsTemplateEntity.kt` with the columns from data-model.md §1.1 (id, pattern, wildcardSlotsJson, state, classification, senderIdHint, firstSeenEpochMillis, lastSeenEpochMillis, matchCount). Annotate with `@Entity(tableName = "sms_template")` and `@PrimaryKey` on `id`.
- [X] T007 [P] Create `shared/data/core/src/main/java/com/ivy/data/db/entity/SenderAccountLinkEntity.kt` per data-model.md §1.2 with `senderId` as `@PrimaryKey` (this single-column PK enforces the FR-016 1:1 uniqueness at the DB level).
- [X] T008 [P] Create `shared/data/core/src/main/java/com/ivy/data/db/entity/PendingReviewItemEntity.kt` per data-model.md §1.3 with a UNIQUE index on `dedupKey` and a non-unique index on `templateId` (use `@Entity(indices = [...])`).
- [X] T009 Create `shared/data/core/src/main/java/com/ivy/data/db/migration/Migration131to132_SmsExtraction.kt` extending `Migration(131, 132)`. Body executes the three `CREATE TABLE` statements and two `CREATE INDEX` statements verbatim from data-model.md §2 inside `database.execSQL(...)` calls.
- [X] T010 Modify `shared/data/core/src/main/java/com/ivy/data/db/IvyRoomDatabase.kt`: bump `version` from 131 to 132, append the three new entities to the `entities` array, and register `Migration131to132_SmsExtraction()` in the migrations list (look for the existing migration registration spot; the latest entry is `Migration130to131_LoanChecklist`).

### 2.2 DAOs

- [X] T011 [P] Create `shared/data/core/src/main/java/com/ivy/data/db/dao/read/ReadSmsTemplateDao.kt` with `@Dao` interface methods: `findAll(): List<SmsTemplateEntity>`, `findById(id: String): SmsTemplateEntity?`, `findByPattern(pattern: String): SmsTemplateEntity?`, `findActive(): List<SmsTemplateEntity>` (state = 'ACTIVE'), `observeAll(): Flow<List<SmsTemplateEntity>>`.
- [X] T012 [P] Create `shared/data/core/src/main/java/com/ivy/data/db/dao/write/WriteSmsTemplateDao.kt` with `upsert`, `delete(id: String)`, `replaceAll(items: List<SmsTemplateEntity>)`.
- [X] T013 [P] Create `shared/data/core/src/main/java/com/ivy/data/db/dao/read/ReadSenderAccountLinkDao.kt` with `findAll`, `findBySenderId(senderId: String): SenderAccountLinkEntity?`, `findByAccountId(accountId: String): List<SenderAccountLinkEntity>`, `observeAll(): Flow<List<SenderAccountLinkEntity>>`.
- [X] T014 [P] Create `shared/data/core/src/main/java/com/ivy/data/db/dao/write/WriteSenderAccountLinkDao.kt` with `insert(entity)` (which will throw on PK collision — caller is expected to pre-check), `delete(senderId: String)`, `replaceAll`.
- [X] T015 [P] Create `shared/data/core/src/main/java/com/ivy/data/db/dao/read/ReadPendingReviewItemDao.kt` with `findAll`, `findByTemplateId(templateId: String): List<PendingReviewItemEntity>`, `findByDedupKey(key: String): PendingReviewItemEntity?`, `count(): Int`, `observeCount(): Flow<Int>`.
- [X] T016 [P] Create `shared/data/core/src/main/java/com/ivy/data/db/dao/write/WritePendingReviewItemDao.kt` with `insert`, `deleteById(id: String)`, `deleteByTemplateId(templateId: String)`, `deleteAll`.
- [X] T017 Modify `shared/data/core/src/main/java/com/ivy/data/di/RoomDbModule.kt` to add six new `@Provides` methods exposing each of the new DAOs from `IvyRoomDatabase` (mirror the pattern used by existing DAO providers in the same file).

### 2.3 Domain model classes

- [X] T018 [P] Create `feature/sms-sync/src/main/java/com/ivy/sms/domain/model/SmsMessage.kt` (data class with dedupKey, senderId, body, timestamp).
- [X] T019 [P] Create `feature/sms-sync/src/main/java/com/ivy/sms/domain/model/ScanPeriod.kt` (sealed interface: LastWeek / LastMonth / LastQuarter / LastYear / AllTime, each `object`).
- [X] T020 [P] Create `feature/sms-sync/src/main/java/com/ivy/sms/domain/model/SmsTemplate.kt` (data class per data-model.md §5; include `TemplateState` enum, `TransactionClassification` enum, `WildcardSlot` data class, `WildcardMapping` sealed interface in the same file or sibling files).
- [X] T021 [P] Create `feature/sms-sync/src/main/java/com/ivy/sms/domain/model/Ids.kt` containing `@JvmInline value class SmsTemplateId(val value: UUID)`, `WildcardId(val value: UUID)`, `PendingReviewItemId(val value: UUID)` — follow the existing convention from `AccountId` / `TransactionId` in `shared:data:model`.
- [X] T022 [P] Create `feature/sms-sync/src/main/java/com/ivy/sms/domain/model/SenderAccountLink.kt` (data class with senderId, accountId, linkedAt).
- [X] T023 [P] Create `feature/sms-sync/src/main/java/com/ivy/sms/domain/model/PendingReviewItem.kt` (data class) and `feature/sms-sync/src/main/java/com/ivy/sms/domain/model/QuarantineReason.kt` (enum: TEMPLATE_NOT_MAPPED, AMOUNT_NOT_PARSEABLE, SENDER_NOT_LINKED, CURRENCY_MISMATCH).
- [X] T024 [P] Create `feature/sms-sync/src/main/java/com/ivy/sms/domain/model/SyncResult.kt` (sealed interface per `contracts/sms-sync-public-api.md` §1: PermissionMissing, Completed, PartiallyCompleted) and `SyncTrigger` enum.

### 2.4 Mappers

- [X] T025 [P] Create `feature/sms-sync/src/main/java/com/ivy/sms/data/SmsTemplateMapper.kt` with two suspend functions returning `Either<String, T>`: `SmsTemplateEntity.toDomain()` and `SmsTemplate.toEntity()`. Wildcard slots round-trip via `kotlinx.serialization` JSON encoding of `List<WildcardSlot>`.
- [X] T026 [P] Create `feature/sms-sync/src/main/java/com/ivy/sms/data/SenderAccountLinkMapper.kt` with `SenderAccountLinkEntity.toDomain()` and `SenderAccountLink.toEntity()`.
- [X] T027 [P] Create `feature/sms-sync/src/main/java/com/ivy/sms/data/PendingReviewItemMapper.kt`. Note: this mapper does NOT round-trip body contents through serialization (body is stored as a plain TEXT column).
- [X] T028 [P] Create `feature/sms-sync/src/main/java/com/ivy/sms/data/SmsMessageMapper.kt` mapping a raw `SmsRow` (the cursor-row data class defined in T031) to a domain `SmsMessage`, computing the dedup key as `sha256(senderId + ":" + epochMillis + ":" + body)` formatted as hex.

### 2.5 Transaction metadata extension

- [X] T029 Modify `shared/data/model/src/main/kotlin/com/ivy/data/model/Transaction.kt` `TransactionMetadata` data class: add four nullable fields (`smsSourceDedupKey: String? = null`, `smsTemplateId: UUID? = null`, `smsSourceSenderId: String? = null`, `smsSourceTimestamp: Instant? = null`) per `contracts/domain-extensions.md` §1. All defaults are `null`. Verify the existing JSON serialization configuration for this metadata class is `ignoreUnknownKeys = true` — if not, fix it in the same edit.
- [X] T030 [P] Create `feature/sms-sync/src/main/java/com/ivy/sms/data/TransactionMetadataExt.kt` with extension property `TransactionMetadata.smsTemplateIdTyped: SmsTemplateId?` to bridge raw `UUID?` ↔ typed wrapper without forcing `shared:data:model` to depend on `feature:sms-sync`.

### 2.6 SMS inbox data source

- [X] T031 Create `feature/sms-sync/src/main/java/com/ivy/sms/data/SmsRow.kt` — internal data class for a raw cursor row (id: Long, address: String, dateEpochMillis: Long, body: String).
- [X] T032 Create `feature/sms-sync/src/main/java/com/ivy/sms/data/SmsInboxDataSource.kt`: an `interface` plus `class SmsInboxDataSourceImpl @Inject constructor(@ApplicationContext context, dispatchers: AppCoroutinesDispatchers)` that exposes `suspend fun read(lowerBoundEpochMillis: Long, watermarkEpochMillis: Long): Either<String, List<SmsRow>>`. Wraps `context.contentResolver.query(Telephony.Sms.Inbox.CONTENT_URI, projection, selection, selectionArgs, sortOrder)` per research.md §R-2. Returns `Either.Left("PERMISSION_DENIED:...")` on `SecurityException`, `Either.Left("READ_ERROR:...")` on cursor failure, `Either.Right(emptyList())` on null cursor with no rows. Runs the entire body inside `withContext(dispatchers.io)`.

### 2.7 Drain parser

- [X] T033 [P] Create `feature/sms-sync/src/main/java/com/ivy/sms/data/DrainParserState.kt`: data classes for the parse-tree (`DrainNode`, `DrainCluster`) — pure Kotlin, no Android. Cluster holds `templatePattern: List<String>` (tokens, with `<*>` for wildcards), `messageCount: Int`, `templateId: UUID`.
- [X] T034 Create `feature/sms-sync/src/main/java/com/ivy/sms/data/DrainParser.kt` implementing the algorithm from research.md §R-1: tokenize, pre-normalize digits/dates/amounts, descend the fixed depth-4 tree (token-count → first-token → second-token → third-token), find best-matching cluster by similarity ≥ 0.5, update template by `<*>`-substituting disagreements at each position. Public surface: `class DrainParser` with `fun consume(message: SmsMessage): DrainCluster` and `fun rebuildFromTemplates(seed: List<SmsTemplate>): Unit`. Pure Kotlin — must run on `Dispatchers.IO` from the calling use case, but the parser itself is synchronous.
- [X] T035 [P] Create `feature/sms-sync/src/main/java/com/ivy/sms/domain/parser/AmountParser.kt`: `fun parseAmount(text: String): Either<String, BigDecimal>`. Handles common bank-SMS formats: `USD 12.34`, `$12.34`, `12.34 USD`, `12,345.67`, `12.345,67` (locale-tolerant: tries common decimal separators, picks the one that parses).
- [X] T036 [P] Create `feature/sms-sync/src/main/java/com/ivy/sms/domain/parser/DateTimeParser.kt`: `fun parseDateTime(text: String): Either<String, Instant>`. Tries a list of common bank-SMS date patterns (`dd/MM/yy`, `dd/MM/yyyy`, `MM/dd/yyyy HH:mm`, `dd-MMM-yyyy`, `yyyy-MM-dd`) in order; first successful parse wins.

### 2.8 DataStore / preferences

- [X] T037 Create `feature/sms-sync/src/main/java/com/ivy/sms/data/SmsWatermarkPreferences.kt`: thin wrapper around the project's existing DataStore exposing two suspend operations (`read(): Either<String, Long?>`, `write(epochMillis: Long): Either<String, Unit>`) for the `sms.watermark.epochMillis` key, plus mirror operations for `sms.scan.period.lowerBoundEpochMillis`. Inject via Hilt the same way other preferences classes in the project are wired.

### 2.9 Repositories

- [X] T038 Create `feature/sms-sync/src/main/java/com/ivy/sms/data/SmsTemplateRepository.kt`: an `interface` + `class SmsTemplateRepositoryImpl @Inject constructor(readDao, writeDao, mapper, dispatchers)` providing `findAll`, `findById`, `findActive`, `findByPattern`, `upsert`, `delete`, `observeAll` — all returning `Either<String, T>` and dispatching to IO.
- [X] T039 Create `feature/sms-sync/src/main/java/com/ivy/sms/data/SenderAccountLinkRepository.kt` per `contracts/domain-extensions.md` §2 (interface + impl). The `upsert` method MUST first call `findBySenderId`; if a different `accountId` already holds the senderId, return `Either.Left("LINK_CONFLICT: sender '<id>' already linked to wallet '<name>'")` — fetching the wallet name requires injecting `AccountRepository` here.
- [X] T040 Create `feature/sms-sync/src/main/java/com/ivy/sms/data/PendingReviewItemRepository.kt`: `findAll`, `count` (synchronous + observable Flow), `enqueue(item)`, `dismiss(id)`, `clearByTemplate(templateId)`. The `enqueue` method MUST swallow UNIQUE-constraint violations on `dedupKey` silently (log only) — duplicate enqueue is not an error, it's a no-op.

### 2.10 Routing + scanning use cases

- [X] T041 Create `feature/sms-sync/src/main/java/com/ivy/sms/domain/usecase/RouteSmsUseCase.kt`: takes a single `SmsMessage` + the current set of templates + sender→wallet links, decides one of {Tier 1 transaction, Tier 2 quarantine, Blacklist drop}, executes the side-effect via injected dependencies (`CreateTransactionFromSmsUseCase`, `PendingReviewItemRepository`). Pure routing logic — fully unit-testable in isolation. Must explicitly check the `BLACKLISTED` short-circuit BEFORE the Tier 1 match check.
- [X] T042 Create `feature/sms-sync/src/main/java/com/ivy/sms/domain/usecase/DiscoverTemplatesUseCase.kt`: feeds a list of `SmsMessage` through the `DrainParser`, persists newly-emitted clusters as `SmsTemplate` rows in `state = UNMAPPED` (or matches them to existing rows), returns `Either<String, List<SmsTemplate>>` of all templates active or newly discovered.
- [X] T043 Create `feature/sms-sync/src/main/java/com/ivy/sms/domain/usecase/CreateTransactionFromSmsUseCase.kt`: parses each user-mapped wildcard via `AmountParser` / `DateTimeParser` / regex extraction, constructs a `Transaction` (Income/Expense/Transfer based on classification), populates `metadata.smsSourceDedupKey + smsTemplateId + smsSourceSenderId + smsSourceTimestamp`, persists via the existing transaction-write pathway. Returns `Either<String, TransactionId>`. On amount-parse failure, returns `Either.Left("AMOUNT_NOT_PARSEABLE:...")` so the caller can route the message to quarantine instead.
- [X] T044 Create `feature/sms-sync/src/main/java/com/ivy/sms/domain/usecase/ScanInboxUseCase.kt`: orchestrates one full reconciliation pass — read watermark → call `SmsInboxDataSource` → run each row through `DiscoverTemplatesUseCase` and `RouteSmsUseCase` → advance watermark atomically at the end. Emits a `Flow<ScanProgress>` for the UI's progressive-rendering needs.
- [X] T045 Create `feature/sms-sync/src/main/java/com/ivy/sms/domain/usecase/SyncSmsUseCase.kt` per `contracts/sms-sync-public-api.md` §1. Public surface: single `suspend operator fun invoke(trigger: SyncTrigger): Either<String, SyncResult>`. Internally guarded by a `Mutex` for idempotency. Pre-check `READ_SMS` permission and short-circuit to `SyncResult.PermissionMissing` if absent.

### 2.11 Permission machinery

- [X] T046 [P] Create `feature/sms-sync/src/main/java/com/ivy/sms/ui/permission/PermissionState.kt`: sealed interface with `Granted`, `Denied`, `PermanentlyDenied` per research.md §R-4.
- [X] T047 Create `feature/sms-sync/src/main/java/com/ivy/sms/ui/permission/PermissionGate.kt`: a Composable that takes `content: @Composable () -> Unit` and renders it only when `PermissionState.Granted`; otherwise renders an explanatory empty state with "Try again" + "Open System Settings" buttons. Uses `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())` and re-checks permission on `Lifecycle.Event.ON_RESUME` via `LifecycleEventObserver`.

### 2.12 Hilt module + startup hook

- [X] T048 Create `feature/sms-sync/src/main/java/com/ivy/sms/di/SmsSyncModule.kt`: `@Module @InstallIn(SingletonComponent::class)` providing the three new repositories (`SmsTemplateRepository`, `SenderAccountLinkRepository`, `PendingReviewItemRepository`), `SmsInboxDataSource`, `DrainParser` (as `@Singleton` so its in-memory tree is shared across calls), `SmsWatermarkPreferences`, `SyncSmsUseCase`, and `SmsSyncAppStartup`. Bind interfaces to impls via `@Binds`.
- [X] T049 Create `feature/sms-sync/src/main/java/com/ivy/sms/startup/SmsSyncAppStartup.kt`: interface + impl per `contracts/sms-sync-public-api.md` §3. Owns its own `applicationScope = CoroutineScope(SupervisorJob() + dispatchers.io)`. Method `scheduleLaunchScan()` performs a fire-and-forget `applicationScope.launch { syncSmsUseCase(SyncTrigger.APP_LAUNCH) }`. Exposes `syncResult: StateFlow<SyncResult?>` for UI consumption.
- [X] T050 Modify `app/src/main/java/com/ivy/wallet/IvyAndroidApp.kt`: inject `SmsSyncAppStartup` via Hilt `EntryPoint` (the Application class can't have constructor injection) inside `onCreate()` AFTER `super.onCreate()`. Call `scheduleLaunchScan()`. MUST NOT block — verify by ensuring no `runBlocking` is introduced.

### 2.13 Backup integration (FR-033 / FR-034)

- [X] T051 Modify `shared/data/core/src/main/java/com/ivy/data/backup/BackupDataUseCase.kt` (or its companion DTOs file): extend `IvyWalletCompleteData` with the four new optional fields per `contracts/backup-extension.md` §1: `smsTemplates`, `senderAccountLinks`, `smsWatermarkEpochMillis`, `smsScanLowerBoundEpochMillis`. Create new DTOs `SmsTemplateBackupDto`, `WildcardSlotBackupDto`, `SenderAccountLinkBackupDto`.
- [X] T052 Modify `BackupDataUseCase.generateJsonBackup()` to gather the new tables (via injected `SmsTemplateRepository` + `SenderAccountLinkRepository` + `SmsWatermarkPreferences`) and pass them into the `IvyWalletCompleteData(...)` constructor. Wrap each new gather in `withContext(dispatchers.io)`.
- [X] T053 Modify `BackupDataUseCase.importBackupFile(...)` to conditionally restore each new section (per `contracts/backup-extension.md` §4 — `?.let { ... }` per section). MUST NOT throw if a section is absent.

### 2.14 Foundational tests

- [X] T054 [P] Create `feature/sms-sync/src/test/java/com/ivy/sms/data/DrainParserTest.kt` (JUnit + Kotest assertions). Cover: deterministic clustering (same input → same templates), correct `<*>` substitution at disagreement positions, similarity threshold 0.5 boundary cases, max-children safety cap.
- [X] T055 [P] Create `feature/sms-sync/src/test/java/com/ivy/sms/data/SmsTemplateMapperTest.kt`: mapper round-trip preservation including `wildcardSlotsJson` serialization.
- [X] T056 [P] Create `feature/sms-sync/src/test/java/com/ivy/sms/data/SenderAccountLinkRepositoryTest.kt`: verifies `upsert` returns `Either.Left("LINK_CONFLICT:...")` when the senderId is already linked to a different accountId, with the conflicting wallet's name interpolated correctly. Uses an in-memory Room DB.
- [X] T057 [P] Create `feature/sms-sync/src/test/java/com/ivy/sms/domain/parser/AmountParserTest.kt` and `DateTimeParserTest.kt` covering the locale variants enumerated in T035 / T036.
- [X] T058 [P] Create `feature/sms-sync/src/test/java/com/ivy/sms/domain/usecase/RouteSmsUseCaseTest.kt`: pure-logic test that proves Blacklist short-circuits BEFORE Tier 1 match, and that an unmapped template routes to Tier 2 with `QuarantineReason.TEMPLATE_NOT_MAPPED`.
- [X] T059 [P] Create `shared/data/core/src/androidTest/java/com/ivy/data/db/migration/Migration131to132Test.kt` (instrumented test using `MigrationTestHelper`): create a v131 DB, run the migration, verify the three new tables exist with the expected columns and that the `senderId` PK rejects duplicate inserts.

**Checkpoint**: Foundation ready. Run `./gradlew :feature:sms-sync:testDebugUnitTest :shared:data:core:connectedDebugAndroidTest` — all foundational tests pass. Module compiles; app boots; the launch-scan no-ops cleanly when permission is missing; nothing in the UI changes yet because no screens exist.

---

## Phase 3: User Story 1 - Map a discovered template and earn automatic transactions (Priority: P1) 🎯 MVP

**Goal**: User grants READ_SMS, picks a historical period, sees templates render progressively, taps a template, maps its wildcards, classifies it, links the sender to a wallet, and immediately starts seeing auto-created transactions for matching messages.

**Independent Test**: Per spec §US1 Independent Test — install on a device with ≥20 same-shaped bank SMS, grant permission, pick "Last Month", confirm the template appears, map it (Amount + Merchant + DateTime, classification = EXPENSE), link the sender to a wallet, inject a fresh matching SMS via `adb`, re-open the app, verify a transaction appears.

### 3.1 Use cases for US1

- [X] T060 [P] [US1] Create `feature/sms-sync/src/main/java/com/ivy/sms/domain/usecase/MapTemplateUseCase.kt`: validates that ≥1 wildcard is mapped to `Amount` AND `classification != null` before saving (per data-model.md §6 first rule). On save, transitions the template's state from `UNMAPPED` → `ACTIVE`, then calls `RouteSmsUseCase` for each pending-review row whose `templateId` matches (so previously-queued matching messages convert to transactions). Returns `Either<String, MapTemplateResult>` where `MapTemplateResult` holds the count of converted-from-queue transactions.
- [X] T061 [P] [US1] Create `feature/sms-sync/src/main/java/com/ivy/sms/domain/usecase/LinkSenderToWalletUseCase.kt`: thin wrapper around `SenderAccountLinkRepository.upsert()` that surfaces the FR-016 conflict error verbatim. Returns `Either<String, Unit>`.
- [X] T062 [P] [US1] Create `feature/sms-sync/src/main/java/com/ivy/sms/domain/usecase/ExtendScanPeriodUseCase.kt`: takes a new `ScanPeriod` (must extend further back than the current `sms.scan.period.lowerBoundEpochMillis`), reads SMS rows from the gap window via `SmsInboxDataSource`, runs them through `DiscoverTemplatesUseCase` and `RouteSmsUseCase`. The watermark is NOT touched.

### 3.2 Period picker UI

- [X] T063 [P] [US1] Create `feature/sms-sync/src/main/java/com/ivy/sms/ui/period/PeriodPickerViewState.kt` — immutable view state for the picker (no fields beyond the enum list and the selected option).
- [X] T064 [US1] Create `feature/sms-sync/src/main/java/com/ivy/sms/ui/period/PeriodPickerViewModel.kt`: emits `StateFlow<PeriodPickerViewState>`; on confirm, writes the chosen period's lower bound into `SmsWatermarkPreferences` and triggers `SyncSmsUseCase(SyncTrigger.FIRST_SCAN_AFTER_PERMISSION)`.
- [X] T065 [US1] Create `feature/sms-sync/src/main/java/com/ivy/sms/ui/period/PeriodPickerScreen.kt` — Compose screen with five radio options (Last Week / Last Month / Last Quarter / Last Year / All Time), a "Start scanning" button, dismissed only after the user makes a choice (per US1 acceptance scenario 6).

### 3.3 Template list (progressive rendering)

- [X] T066 [P] [US1] Create `feature/sms-sync/src/main/java/com/ivy/sms/ui/templates/TemplateListViewState.kt`: holds `templates: List<TemplateRowViewState>`, `scanProgress: ScanProgress?`, `error: String?`. `TemplateRowViewState` has fields for the human-readable preview, state badge, and click target.
- [X] T067 [US1] Create `feature/sms-sync/src/main/java/com/ivy/sms/ui/templates/TemplateListViewModel.kt`: combines `SmsTemplateRepository.observeAll()` with the `ScanInboxUseCase`'s progress flow into a single state. Re-emits on each new template discovered (per FR-005d).
- [X] T068 [US1] Create `feature/sms-sync/src/main/java/com/ivy/sms/ui/templates/TemplateListScreen.kt`: Compose UI; uses `LazyColumn` for templates; renders the non-blocking "Scanning… N / M" indicator at the top when `scanProgress != null`; per-row "Active" / "Unmapped" / "Pending Review" / "Blacklisted" badges.

### 3.4 Template mapping (inline tap-on-wildcard)

- [X] T069 [P] [US1] Create `feature/sms-sync/src/main/java/com/ivy/sms/ui/templates/TemplateMappingViewState.kt`: holds the rendered `AnnotatedString`, the wildcard list with current bindings, and the chosen classification.
- [X] T070 [US1] Create `feature/sms-sync/src/main/java/com/ivy/sms/ui/templates/TemplateMappingViewModel.kt`: handles wildcard tap events and classification picks; on save, dispatches `MapTemplateUseCase`.
- [X] T071 [US1] Create `feature/sms-sync/src/main/java/com/ivy/sms/ui/templates/TemplateMappingScreen.kt`: builds the `AnnotatedString` per research.md §R-3 with `pushStringAnnotation(tag = "WILDCARD", annotation = wildcardId.toString())` around each `<*>` span. The `BasicText` uses `Modifier.pointerInput` + `TextLayoutResult.getOffsetForPosition` + `getStringAnnotations("WILDCARD", offset, offset)` to identify which wildcard was tapped. Tap opens `WildcardMappingBottomSheet`.
- [X] T072 [US1] Create `feature/sms-sync/src/main/java/com/ivy/sms/ui/templates/WildcardMappingBottomSheet.kt`: `ModalBottomSheet` with the five mapping options (Amount / Merchant / DateTime / Reference / Ignore) per FR-019. Each non-Ignore option becomes disabled if already chosen on a different wildcard in this template.
- [X] T073 [US1] Add a classification picker to `TemplateMappingScreen.kt` (segmented control or three-radio row): INCOME / EXPENSE / TRANSFER. Save button is disabled until ≥1 wildcard is mapped to Amount AND a classification is chosen.

### 3.5 Wallet picker for sender linking

- [X] T074 [P] [US1] Create `feature/sms-sync/src/main/java/com/ivy/sms/ui/templates/LinkSenderToWalletViewState.kt`: list of all wallets + a free-text `error` field that surfaces the `LINK_CONFLICT` message per FR-016.
- [X] T075 [US1] Create `feature/sms-sync/src/main/java/com/ivy/sms/ui/templates/LinkSenderToWalletViewModel.kt`: collects wallets from `AccountRepository`; on selection, dispatches `LinkSenderToWalletUseCase` and surfaces `Either.Left` strings into `error` for inline display.
- [X] T076 [US1] Create `feature/sms-sync/src/main/java/com/ivy/sms/ui/templates/LinkSenderToWalletScreen.kt`: simple radio-list of the user's wallets with the conflict error rendered inline above the list when present, and a "Remove existing link first" action when the error is `LINK_CONFLICT`.

### 3.6 "Scan further back" extension action

- [X] T077 [US1] Add a "Scan further back…" overflow item to `TemplateListScreen.kt`. Tapping re-opens `PeriodPickerScreen` in "extend mode"; the chosen period (which must be longer than the current) dispatches `ExtendScanPeriodUseCase` and shows a snackbar with the count of additional templates discovered (per US1 acceptance scenario 8).

### 3.7 Top-level entry composition + navigation

- [X] T078 [US1] Create `feature/sms-sync/src/main/java/com/ivy/sms/ui/SmsExtractionScreen.kt`: the top-level Composable that decides which sub-screen to show based on a state machine: PermissionGate → PeriodPicker (first time only) → TemplateListScreen.
- [X] T079 [US1] Create `feature/sms-sync/src/main/java/com/ivy/sms/ui/nav/SmsSyncNavigation.kt` registering route keys `SmsExtractionScreen`, `TemplateMappingScreen(templateId)`, `LinkSenderToWalletScreen(senderId)` per `contracts/sms-sync-public-api.md` §2 with the project's `shared:ui:navigation` system. Navigation graph wiring goes here.
- [X] T080 [US1] Modify `feature/home/src/main/java/com/ivy/home/HomeMoreMenu.kt`: add a `MoreMenuButton(icon = Icons.Sms, label = "Sync SMS")` to `QuickAccess()` (per the codebase map's Section 12). On tap, dispatch `SyncSmsUseCase(SyncTrigger.MANUAL_MENU)` and surface the result via a snackbar reading e.g. "Synced — 3 new transactions, 1 to review".

### 3.8 US1 tests

- [X] T081 [P] [US1] Create `feature/sms-sync/src/test/java/com/ivy/sms/domain/usecase/MapTemplateUseCaseTest.kt`: covers the validation rules (no Amount mapping → save rejected; no classification → save rejected) and the queued-message conversion behavior.
- [X] T082 [P] [US1] Create `feature/sms-sync/src/test/java/com/ivy/sms/domain/usecase/CreateTransactionFromSmsUseCaseTest.kt`: verify metadata fields are populated correctly (dedup key, template id, sender, timestamp); verify currency comes from the linked wallet, NOT from the SMS body.
- [X] T083 [P] [US1] Create `feature/sms-sync/src/test/java/com/ivy/sms/ui/templates/TemplateListPaparazziTest.kt` (Paparazzi snapshot): empty state, scanning state with progress, mixed-state list (1 Active + 2 Unmapped + 1 Pending Review).
- [X] T084 [P] [US1] Create `feature/sms-sync/src/test/java/com/ivy/sms/ui/templates/TemplateMappingPaparazziTest.kt`: snapshot of the mapping screen showing 3 wildcards, the bottom sheet open with options, and the classification picker.
- [X] T085 [P] [US1] Create `feature/sms-sync/src/test/java/com/ivy/sms/ui/period/PeriodPickerPaparazziTest.kt`: snapshot of the period picker with no selection and with "Last Year" selected.
- [X] T086 [P] [US1] Create `feature/sms-sync/src/test/java/com/ivy/sms/ui/permission/PermissionGatePaparazziTest.kt`: snapshot of the permission gate in `Denied` state and `PermanentlyDenied` state.

**Checkpoint**: US1 is complete. The MVP is shippable: a single user can grant permission, discover templates, map one, link a sender, and watch transactions appear. Run the §4 quickstart procedure on a real device for end-to-end validation.

---

## Phase 4: User Story 2 - Manually resolve unknown messages from quarantine queue (Priority: P2)

**Goal**: When an SMS doesn't match any user-mapped template, it lands in a Pending Review queue. The user opens the queue, sees each pending item with its discovered template, and either maps the template (which converts that and all sibling-template messages to transactions) or dismisses the item.

**Independent Test**: With US1 working, inject an SMS from the same sender but a never-seen shape via `adb`. Verify (a) no transaction is created, (b) the badge counter on the entry point increments, (c) the queue shows the item with raw text + discovered template, (d) tapping "Map this template" runs the same flow as US1 and converts the queued item.

### 4.1 Queue use cases + repository wiring

- [X] T087 [P] [US2] Create `feature/sms-sync/src/main/java/com/ivy/sms/domain/usecase/ResolvePendingItemUseCase.kt`: two operations — `dismiss(id: PendingReviewItemId): Either<String, Unit>` (deletes the row, leaves template untouched) and `convertViaTemplateMapping(templateId: SmsTemplateId): Either<String, ConversionResult>` which is invoked by `MapTemplateUseCase` after a Phase 3 mapping save (already wired in T060; this is the explicit named-symbol that Phase 4 references).
- [X] T088 [P] [US2] Create `feature/sms-sync/src/main/java/com/ivy/sms/domain/usecase/ObservePendingCountUseCase.kt`: returns `Flow<Int>` from `PendingReviewItemRepository`. Used by both the queue screen and the home overflow menu badge.

### 4.2 Pending Review queue UI

- [X] T089 [P] [US2] Create `feature/sms-sync/src/main/java/com/ivy/sms/ui/pending/PendingReviewViewState.kt`: list of `PendingItemRowViewState` entries, each holding the raw body, the discovered template's `<*>`-rendered string, the sender, the timestamp, and the `quarantineReason` label.
- [X] T090 [US2] Create `feature/sms-sync/src/main/java/com/ivy/sms/ui/pending/PendingReviewViewModel.kt`: collects `PendingReviewItemRepository.observeAll()`, joins each item with its template via `SmsTemplateRepository`, exposes per-item event handlers for "Map this template" / "Dismiss" / "Blacklist this template".
- [X] T091 [US2] Create `feature/sms-sync/src/main/java/com/ivy/sms/ui/pending/PendingReviewScreen.kt`: `LazyColumn` of pending items; each item is a card with raw SMS body (collapsed by default, expandable), the discovered template rendered with `<*>` highlighting (read-only — no taps in this view), the sender chip, the timestamp, the quarantine reason chip, and three action buttons.

### 4.3 Badge + entry point

- [X] T092 [US2] Modify `feature/home/src/main/java/com/ivy/home/HomeMoreMenu.kt` (the change from T080): wrap the "Sync SMS" `MoreMenuButton` with a small badge that displays the result of `ObservePendingCountUseCase` when > 0. Tapping the badge (rather than the menu button) navigates directly to `PendingReviewScreen`.
- [X] T093 [US2] Modify `feature/sms-sync/src/main/java/com/ivy/sms/ui/SmsExtractionScreen.kt` to surface a "Pending Review (N)" entry that navigates to `PendingReviewScreen` when N > 0.

### 4.4 US2 tests

- [X] T094 [P] [US2] Create `feature/sms-sync/src/test/java/com/ivy/sms/domain/usecase/ResolvePendingItemUseCaseTest.kt`: dismiss removes only the targeted item, leaves template state unchanged; converting a template empties all queued rows for that template AND creates one transaction per row.
- [X] T095 [P] [US2] Create `feature/sms-sync/src/test/java/com/ivy/sms/ui/pending/PendingReviewPaparazziTest.kt`: snapshot of empty queue, queue with mixed quarantine reasons (TEMPLATE_NOT_MAPPED + AMOUNT_NOT_PARSEABLE + SENDER_NOT_LINKED), and queue with one expanded item.

**Checkpoint**: US2 is complete. Unknown messages now safely land in a queue and can be resolved by mapping or dismissing.

---

## Phase 5: User Story 3 - Silence noisy templates by blacklisting (Priority: P3)

**Goal**: User toggles a "Blacklist / Ignore" state on noisy templates (OTPs, promotions, balance alerts). Future matching messages are silently dropped — no transaction, no quarantine, no badge increment. Toggling Blacklist OFF restores normal routing for future messages.

**Independent Test**: Per spec §US3 Independent Test — confirm a non-financial template lands in the queue per US2; toggle Blacklist on it; inject another matching SMS; verify it does NOT appear in queue, does NOT create a transaction, does NOT increment the badge.

### 5.1 Blacklist use case

- [X] T096 [US3] Create `feature/sms-sync/src/main/java/com/ivy/sms/domain/usecase/BlacklistTemplateUseCase.kt`: two operations — `enable(templateId)` transitions the template state to `BLACKLISTED` AND deletes all `pending_review_item` rows where `templateId = id` in the same DB transaction (per data-model.md §6 fourth rule); `disable(templateId)` reverts to `UNMAPPED` (NOT `ACTIVE` — the user must re-map).

### 5.2 UI surfaces

- [X] T097 [P] [US3] Modify `feature/sms-sync/src/main/java/com/ivy/sms/ui/templates/TemplateListScreen.kt`: add a per-row overflow icon with a single action — "Blacklist" (when state ∈ {UNMAPPED, ACTIVE, PENDING_REVIEW}) or "Un-blacklist" (when state = BLACKLISTED). Show a confirmation dialog before blacklisting an `ACTIVE` template (it would orphan that template's mappings).
- [X] T098 [P] [US3] Modify `feature/sms-sync/src/main/java/com/ivy/sms/ui/pending/PendingReviewScreen.kt`: add a "Blacklist this template" button to each pending item alongside "Map this template" and "Dismiss" (per spec §US2 acceptance scenario 1 + FR-026c). Tapping it removes ALL pending items for that template from the queue.
- [X] T099 [US3] Modify `feature/sms-sync/src/main/java/com/ivy/sms/ui/templates/TemplateListScreen.kt` to render a distinctive "Blacklisted" badge color/icon for blacklisted templates (per FR-017).

### 5.3 US3 tests

- [X] T100 [P] [US3] Create `feature/sms-sync/src/test/java/com/ivy/sms/domain/usecase/BlacklistTemplateUseCaseTest.kt`: verifies queue items are cleared on enable; verifies state transitions; verifies that disable goes to UNMAPPED not ACTIVE.
- [X] T101 [P] [US3] Create `feature/sms-sync/src/test/java/com/ivy/sms/domain/usecase/RouteSmsBlacklistTest.kt`: focused regression test that an SMS matching a `BLACKLISTED` template produces no transaction AND no queue insert AND no badge increment.
- [X] T102 [P] [US3] Create `feature/sms-sync/src/test/java/com/ivy/sms/ui/templates/TemplateListBlacklistedPaparazziTest.kt`: snapshot of the template list with a blacklisted row.

**Checkpoint**: US3 complete. All three user stories independently functional and testable.

---

## Phase 6: Polish & Cross-Cutting Concerns

**Purpose**: Audit-trail UX, the opt-in re-process action, end-to-end verification.

- [X] T103 [P] Modify `feature/transactions/.../TransactionsScreen.kt` (or the transaction-row composable used there): when `transaction.metadata.smsSourceDedupKey != null`, render a small "from SMS" indicator chip on the row per FR-030.
- [X] T104 Create `feature/sms-sync/src/main/java/com/ivy/sms/ui/templates/SmsSourceLookupScreen.kt`: when the user taps an "from SMS" chip, navigate here. Show the source SMS body re-fetched from the device inbox by `(senderId, timestamp, dedupKey)`. If the SMS is no longer in the inbox, show "Original SMS no longer available".
- [X] T105 [P] Create `feature/sms-sync/src/main/java/com/ivy/sms/domain/usecase/ReprocessHistoricalUseCase.kt` per FR-032: explicit, opt-in. Takes a templateId, runs `RouteSmsUseCase` against all historical SMS in the period that match this template AND don't already have a corresponding `metadata.smsSourceDedupKey` transaction. Returns a count preview before write; requires confirmation token.
- [X] T106 Add a "Re-process historical messages" overflow item to `TemplateMappingScreen.kt` (or accessible from the template list when state = ACTIVE). Tap → preview dialog → confirm → run `ReprocessHistoricalUseCase`.
- [X] T107 [P] Create `feature/sms-sync/src/test/java/com/ivy/sms/domain/usecase/ReprocessHistoricalUseCaseTest.kt`: preview count is accurate, confirmation actually writes, dedup prevents double-creation if run twice.
- [ ] T108 Run the full `quickstart.md` end-to-end checklist on a real device with a real bank SMS sender. Capture screenshots / a short video of: permission grant → period pick → progressive scan → template mapping → first auto-transaction. (Manual task — record outcome in this checkbox.)
- [ ] T109 Verify the spec's measurable success criteria on the same device:
  - SC-001: ≥95% of historical SMS clustered into a template (count via `SmsTemplateRepository.findAll().sumOf { it.matchCount }` vs. total inbox size in scope)
  - SC-003: <3s from scan trigger to transaction visible
  - SC-008: First template tappable within 3s of scan start
  - SC-009: Incremental scan of ≤50 messages in <1s
  Record numbers in a comment on this task.
- [X] T110 [P] Update `docs/Guidelines.md` (or create a feature-specific `docs/sms-sync.md`) with one paragraph on how to add a new mapping target (e.g., when bank SMS start including a category) — points future-you at `WildcardMapping` sealed interface and the `WildcardMappingBottomSheet` UI.
- [ ] T111 Run `./gradlew :feature:sms-sync:verifyPaparazziDebug` and resolve any snapshot diffs introduced by Phase 6 changes (e.g., the "from SMS" chip changes the transaction row snapshot).
- [X] T112 Final pass: grep the codebase for any `TODO`, `FIXME`, or `// XXX` introduced by this feature; resolve or convert each to a tracked todo via `/gsd-add-todo` with rationale.

---

## Dependencies & Execution Order

### Phase Dependencies

- **Phase 1 (Setup)** → no deps, start immediately. T001 → T002 → T004 are sequential (settings.gradle must reference an existing path; build.gradle must exist before app depends on it). T003 and T005 are parallel with T002.
- **Phase 2 (Foundational)** → depends on Phase 1. **Blocks all user-story phases.** Inside Phase 2:
  - T006/T007/T008 (entities) all parallel.
  - T009 depends on T006/T007/T008.
  - T010 depends on T009.
  - T011–T016 (DAOs) all parallel — each a different file. They depend on T006/T007/T008.
  - T017 depends on T011–T016.
  - T018–T024 (domain models) all parallel.
  - T025–T028 (mappers) parallel; depend on the matching entity + domain model.
  - T029 sequential (single file). T030 parallel with T029 (different file).
  - T031 → T032 sequential (T032 uses T031).
  - T033 → T034 sequential.
  - T035, T036 parallel with T034.
  - T037 parallel with the rest (different file).
  - T038, T039, T040 parallel — depend on respective DAOs + mappers.
  - T041, T042, T043 parallel — depend on T038/T039/T040 + parsers.
  - T044 depends on T041/T042/T043.
  - T045 depends on T044.
  - T046 → T047 sequential.
  - T048 depends on T037/T038/T039/T040/T045/T049 (Hilt module sees everything).
  - T049 depends on T045.
  - T050 depends on T049.
  - T051 → T052 → T053 sequential (same file series).
  - T054–T059 (foundational tests) all parallel; each depends on its target unit being implemented.
- **Phase 3 (US1)** → depends on Phase 2. After foundation:
  - T060/T061/T062 parallel (different use-case files).
  - T063 → T064 → T065 sequential (period picker pipeline same files).
  - T066 → T067 → T068 sequential (template list pipeline).
  - T069 → T070 → T071 → T072 → T073 sequential (mapping pipeline same screen file).
  - T074 → T075 → T076 sequential (link wallet pipeline).
  - T077 depends on T065 + T068 + T062.
  - T078 depends on T065 + T068.
  - T079 depends on T078.
  - T080 depends on T045 (SyncSmsUseCase) — can land independently of the rest of US1 actually, but pragmatically wait for the screens to exist so the snackbar's deep-link target works.
  - T081–T086 (US1 tests) parallel.
- **Phase 4 (US2)** → depends on Phase 2 + (T060 from Phase 3 for the mapping flow it reuses).
  - T087 + T088 parallel.
  - T089 → T090 → T091 sequential (queue pipeline).
  - T092 depends on T080 (modifying same file) AND T088.
  - T093 depends on T078.
  - T094, T095 parallel.
- **Phase 5 (US3)** → depends on Phase 2.
  - T096 standalone.
  - T097 + T098 parallel (different files), depend on T096.
  - T099 depends on T097.
  - T100, T101, T102 parallel.
- **Phase 6 (Polish)** → depends on US1 minimum (T103, T104, T105 reference SMS-sourced transactions).

### User Story Independence

- **US1** is the MVP and ships solo: with US1 alone, a user can configure a template and receive auto-transactions. No US2/US3 needed.
- **US2** ships only after US1 because the mapping flow it dispatches to (T060) is owned by US1. But US2's UI and dismiss path are independent of US3.
- **US3** is the most independent: BlacklistTemplateUseCase (T096) only touches Phase 2 code; the UI changes (T097/T098/T099) layer cleanly on top of either US1's or US2's screens.

### Parallel Opportunities

- All `[P]` tasks within the same phase / same dependency tier can run concurrently.
- Cross-phase: once Phase 2 is complete, two contributors could split US2 and US3 cleanly. (For this single-developer feature, sequential P1 → P2 → P3 is the realistic flow.)

---

## Parallel Example: Phase 2 entity creation

```bash
# After Phase 1 setup is done, three entities can be created in parallel:
Task: "T006 [P] Create SmsTemplateEntity in shared/data/core/src/main/java/com/ivy/data/db/entity/"
Task: "T007 [P] Create SenderAccountLinkEntity in shared/data/core/src/main/java/com/ivy/data/db/entity/"
Task: "T008 [P] Create PendingReviewItemEntity in shared/data/core/src/main/java/com/ivy/data/db/entity/"

# Once all three entities exist, six DAOs in parallel:
Task: "T011 [P] Read SMS template DAO"
Task: "T012 [P] Write SMS template DAO"
Task: "T013 [P] Read sender link DAO"
Task: "T014 [P] Write sender link DAO"
Task: "T015 [P] Read pending review DAO"
Task: "T016 [P] Write pending review DAO"
```

---

## Implementation Strategy

### MVP First (US1 only)

1. Phase 1 (Setup) — T001–T005. ~30 min.
2. Phase 2 (Foundational) — T006–T059. The big lift. The bulk of the engine sits here.
3. Phase 3 (US1) — T060–T086. The UI + the user-facing payoff.
4. **STOP and VALIDATE**: Run the full §4 quickstart procedure on a real device. If a real bank SMS produces a real transaction with no further taps, the MVP is real.
5. Ship to your own device. Use it for a week. **Then** decide if US2 is worth building, or whether the tier-1 Always-Active model needs adjustment.

### Incremental Delivery

- After MVP works, layer US2 (queue) — gives the "your bank sent something new" safety net.
- Then US3 (blacklist) — only useful once you've started getting noise in the queue.
- Polish (Phase 6) is genuinely optional — the audit-trail UX and re-process action are luxuries; the system works without them.

### Single-developer reality

- Don't try to parallelize mentally. Work top-to-bottom inside each phase. The `[P]` markers are for documentation, not execution pressure.
- Commit per task or per logical group. The task IDs are great commit-message anchors (e.g., `feat(sms-sync): T034 implement Drain parser`).
- Run the matching test task immediately after each implementation task — keep the cycle tight. Long stretches of untested code in this feature are dangerous because the routing logic has high blast radius.

---

## Notes

- `[P]` = different files, no in-flight dependencies. Use as a hint; don't force parallelism for a single developer.
- `[Story]` label is required only inside Phase 3/4/5. Setup, Foundational, and Polish phases use no story label.
- Total tasks: **112**. Estimated breakdown: Setup (5), Foundational (54), US1 (27), US2 (9), US3 (7), Polish (10).
- Each user story is independently completable. Independent test criteria are documented at the top of each user-story phase (cross-reference spec §User Story 1/2/3 "Independent Test" lines).
- Build commands are listed for reference only; per project preference, hand them off rather than auto-running.
