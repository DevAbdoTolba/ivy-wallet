# Contract — Public API of `feature:sms-sync`

**Feature**: 002-sms-extraction-engine
**Module**: `feature:sms-sync`
**Consumers**: `app`, `feature:home` (HomeMoreMenu)

This document defines what `feature:sms-sync` exposes to the rest of the app graph. Anything not listed here is an internal implementation detail and MUST NOT be referenced from outside the module.

---

## 1. `SyncSmsUseCase`

The single entry point for triggering a reconciliation scan.

```
interface SyncSmsUseCase {
    /**
     * Run an inbox reconciliation scan.
     *
     * @param trigger source of the request (drives logging + UI surfacing only)
     *
     * @return Right(SyncResult) on success — the result describes what happened
     *         (templates discovered, transactions created, items quarantined).
     *         Left(errorString) only for unrecoverable internal errors;
     *         expected outcomes (permission missing, empty inbox) are encoded
     *         on the Right side as SyncResult variants.
     */
    suspend operator fun invoke(trigger: SyncTrigger): Either<String, SyncResult>
}

enum class SyncTrigger { APP_LAUNCH, MANUAL_MENU, FIRST_SCAN_AFTER_PERMISSION }

sealed interface SyncResult {
    object PermissionMissing : SyncResult
    data class Completed(
        val newMessagesProcessed: Int,
        val transactionsCreated: Int,
        val itemsQuarantined: Int,
        val durationMillis: Long,
    ) : SyncResult
    data class PartiallyCompleted(  // emitted by the progressive pipeline if the user navigates away
        val newMessagesProcessed: Int,
        val transactionsCreated: Int,
        val itemsQuarantined: Int,
    ) : SyncResult
}
```

**Error string conventions** (per FR-007):
- `"PERMISSION_DENIED:..."` — runtime check failed mid-scan (rare; usually surfaces as `SyncResult.PermissionMissing`)
- `"READ_ERROR:..."` — ContentResolver query threw / returned null cursor
- `"STORAGE_ERROR:..."` — DAO write failed

**Invariants**:
- The use case is **idempotent**. Calling it multiple times in quick succession produces at most one running scan; subsequent calls observe the same result via a shared `Mutex`.
- It MUST NOT throw. Any internal exception is captured into `Either.Left("...")`.
- It MUST be safe to call from `IvyAndroidApp.onCreate()` (i.e., it doesn't require a foreground Activity).

---

## 2. Navigation routes

`feature:sms-sync` declares these destinations and registers them with `shared:ui:navigation`:

| Route key | Composable | Purpose |
|-----------|-----------|---------|
| `SmsExtractionScreen`     | `SmsExtractionScreen()`   | Top-level entry; routes between PermissionGate / PeriodPicker / TemplateList based on state |
| `PendingReviewScreen`     | `PendingReviewScreen()`   | Queue UI |
| `TemplateMappingScreen`   | `TemplateMappingScreen(templateId)` | Tappable wildcard mapping + classification |
| `LinkSenderToWalletScreen`| `LinkSenderToWalletScreen(senderId)` | Wallet picker for a discovered sender |

The home screen's `HomeMoreMenu.QuickAccess()` adds a single new button: `MoreMenuButton(icon = Icons.Sms, label = "Sync SMS")` which dispatches to `SyncSmsUseCase(SyncTrigger.MANUAL_MENU)` and surfaces the result via a snackbar (count of new transactions + quarantined items, or "Permission needed" with a Settings deep-link).

---

## 3. `SmsSyncAppStartup`

A startup hook injected into `IvyAndroidApp.onCreate()`.

```
interface SmsSyncAppStartup {
    /** Fires the launch-time reconciliation scan in a background coroutine.
     *  MUST return immediately — does NOT block app launch.
     */
    fun scheduleLaunchScan()
}
```

Implementation: dispatches `applicationScope.launch(dispatchers.io) { SyncSmsUseCase(SyncTrigger.APP_LAUNCH) }`. Errors are logged but never crash the app. Result is exposed via a long-lived `StateFlow<SyncResult?>` for any later screen to observe.

The scope `applicationScope` is a `CoroutineScope(SupervisorJob() + dispatchers.io)` owned by `SmsSyncAppStartup` itself — *not* the `Application`'s lifecycle, so a slow scan does not delay process exit.

---

## 4. Hilt bindings exposed

The module's Hilt module (`SmsSyncModule`, in `installIn = SingletonComponent`) provides:
- `SyncSmsUseCase` (binds the impl class)
- `SmsSyncAppStartup` (binds the impl class)
- Internal: `DrainParser`, `SmsInboxDataSource`, six DAOs (already provided by `RoomDbModule`)

No other use case from this feature is bound at the `SingletonComponent` level — they are all `internal` to the feature module and only injected into its own ViewModels.

---

## 5. AndroidManifest declarations

`feature/sms-sync/src/main/AndroidManifest.xml`:

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.READ_SMS" />
</manifest>
```

This permission entry will be merged into the app manifest at build time. No other manifest entries (no `<receiver>`, no `<service>`) — pull-only ingestion.
