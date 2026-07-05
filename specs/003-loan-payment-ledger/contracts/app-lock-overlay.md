# Contract: App Lock Overlay (Project B)

**Provider**: `app` module (`RootActivity`, `RootViewModel`, `AppLockedScreen`)
**Consumers**: every screen in the app (implicitly), `feature/settings` (delay setting)

## Promises to the rest of the app

1. **Composition survival**: locking NEVER removes `NavigationRoot`/`IvyNavGraph` from
   composition. Any screen state held in `remember {}`, ViewModels, focus, scroll, or open
   modals survives a lock/unlock cycle byte-for-byte. Screens need no lock-awareness code.
2. **Input isolation while locked**: no pointer event, key event, IME input, or back press
   reaches content below the overlay. Back while locked does nothing (does not pop the app's
   backstack, does not leave the activity except via system Home).
3. **Privacy**: FLAG_SECURE behavior in `onWindowFocusChanged` is unchanged; the overlay is
   fully opaque; the IME is hidden and focus cleared on the locked transition so no keyboard
   floats above the cover.
4. **No re-init on unlock**: unlock only removes the overlay. It fires no navigation, no
   `LaunchedEffect` restarts (screens never left composition), no ViewModel `start()` calls.

## Settings surface (feature/settings ↔ app)

- `SharedPrefs.LOCK_DELAY_SECONDS: Int` — allowed values `0 | 60 | 300 | 900`; default `60`;
  absent key ⇒ `60`. Written by `SettingsViewModel` (new `SettingsEvent.SetLockDelay`), read by
  `RootViewModel` at each timer start (hot-reload semantics — no restart needed, FR-003).
- Row visible only when `APP_LOCK_ENABLED` is true. Labels: Instantly / 1 minute / 5 minutes /
  15 minutes.
- `0` (Instantly): `onPause` sets `appLocked = true` synchronously; no timer coroutine.

## Lock trigger semantics (unchanged except source of the limit)

- Timer starts in `onPause` when lock enabled; returning before the limit cancels & resets
  (`checkUserInactiveTimeStatus`). Reaching the limit calls `lockApp()`.
- `appLocked: StateFlow<Boolean?>` keeps its 3 states: `null` startup-undetermined (blank
  surface, no app content composed yet), `false` unlocked, `true` overlay shown.

## Hardening rider (feature/edit-transaction)

- `EditTransactionViewModel.start(screen)` MUST be idempotent per screen instance: a second
  call with the same screen while display state is populated is a no-op (no `reset()`).
  This is defense-in-depth; the overlay already prevents the known trigger.

## Acceptance hooks (maps to spec)

- US1 scenarios 1–4 ↔ promises 1–4. US2 scenarios 1–4 ↔ settings surface.
- Paparazzi: `AppLockedScreen` overlay over a populated form (light/dark).
- Manual UAT: `capture.bat lock_overlay_uat` while filling the loan payment sheet, screen off
  >delay, unlock, verify sheet + typed amount intact.
