# Quickstart — SMS Extraction Engine

**Feature**: 002-sms-extraction-engine
**Branch**: `002-sms-extraction-engine`
**Audience**: a developer (currently: just you) starting work on this feature locally.

This is the minimum a person needs to know to build, run, and verify the feature on a real device. It does NOT duplicate the spec or the plan; refer to those for the *what* and the *why*.

---

## 1. Build prerequisites

You already have everything from the main project:
- Android Studio with the project's configured Kotlin / AGP versions (see `buildSrc`)
- An Android device or emulator running API 26+ (the project's `minSdk`)
- The device must be sideload-trusted (this build uses `READ_SMS`, which Google Play does not allow without a signed declaration — but we ship via F-Droid / sideload, so no Play Console signoff needed)

---

## 2. One-time setup steps

1. **Register the new module** in `settings.gradle.kts`:
   ```
   include(":feature:sms-sync")
   ```
2. **Create the module skeleton** following the `feature/transactions` template:
   - `feature/sms-sync/build.gradle.kts` (mirror the structure of `feature/transactions/build.gradle.kts`; depend on `:shared:data:core`, `:shared:data:model`, `:shared:ui:core`, `:shared:ui:navigation`, `:shared:base`)
   - `feature/sms-sync/src/main/AndroidManifest.xml` declaring `<uses-permission android:name="android.permission.READ_SMS" />`
3. **Wire `:feature:sms-sync` into `app/build.gradle.kts`** as a dependency.
4. **Run a clean build** to confirm the new module compiles before any code is added: `./gradlew :feature:sms-sync:assembleDebug`.

---

## 3. Local dev loop — typical iteration

```
# Build only the changed feature module + its consumers
./gradlew :feature:sms-sync:assembleDebug :app:assembleDebug

# Install on connected device
./gradlew :app:installDebug

# Run unit tests for this feature
./gradlew :feature:sms-sync:testDebugUnitTest

# Run Paparazzi snapshot tests
./gradlew :feature:sms-sync:verifyPaparazziDebug

# Re-record snapshots after intentional UI changes
./gradlew :feature:sms-sync:recordPaparazziDebug
```

> **Memory note (per project convention):** these gradle commands are listed for reference. As a rule for this project, hand them off rather than auto-running. Paste the relevant command, wait for human-side execution.

---

## 4. End-to-end manual verification (Tier 1 happy path)

1. Install a freshly-built debug APK on a physical device with at least one bank SMS sender in the inbox.
2. Open Ivy Wallet → tap "Sync SMS" in the home overflow menu (or just open the SMS Extraction screen).
3. Grant `READ_SMS` when prompted.
4. Pick a historical period — start with **Last Month** to keep the scan small.
5. Templates should appear in the list as the scan progresses (progressive rendering — see SC-008).
6. Tap a discovered template. The mapping screen opens.
7. Tap each `<*>` wildcard in turn and bind it to **Amount** / **Merchant** / **DateTime** as appropriate.
8. Pick **EXPENSE** classification and tap Save.
9. Back on the template list, the row now shows the **Active** badge.
10. From the wallet picker (when prompted), link the template's sender (e.g. "ChaseAlerts") to a wallet.
11. **Inject a fresh test SMS** into the device inbox (see §5 below) matching the same shape but with a new amount/date.
12. **Re-open Ivy Wallet** (or tap "Sync SMS").
13. Confirm: a new transaction appears in the wallet's transaction list within a few seconds, with the parsed amount, the wallet's currency, the EXPENSE classification, and a small "from SMS" indicator.

---

## 5. Injecting a test SMS into the device inbox (without a real bank)

Android's `content://sms/inbox` provider accepts inserts via `adb shell content insert` with the right column set. Run from a host shell:

```
adb shell "content insert --uri content://sms/inbox \
  --bind address:s:'ChaseAlerts' \
  --bind body:s:'Purchase of USD 12.34 at Coffee Shop on 04/27/26. Ref: 9821' \
  --bind date:l:$(date +%s%3N) \
  --bind type:i:1 \
  --bind read:i:1"
```

Notes:
- `type:i:1` = Inbox (received). `2` = Sent.
- `date:l:` is epoch milliseconds — the snippet uses `$(date +%s%3N)` (Linux/macOS shell). On Windows PowerShell, substitute `[DateTimeOffset]::Now.ToUnixTimeMilliseconds()`.
- This insert path requires the host shell to have `adb` access; the device does NOT need to be a default SMS handler.
- After insert, force-stop and re-open Ivy Wallet, OR tap **Sync SMS**, to trigger the reconciliation scan.

---

## 6. Tier 2 (quarantine) verification

1. With Tier 1 working for one template, inject an SMS from the same sender but a *different shape* (e.g. add a "Refund" prefix or change tokens). The Drain parser will produce a new template that has no user mapping.
2. Re-open Ivy Wallet. The Pending Review badge in the SMS Extraction entry should increment by 1.
3. Open Pending Review. The new SMS appears with its discovered template (`<*>` wildcards visible) and reason "Template not yet mapped".
4. Tap "Map this template" → complete the same mapping flow. Save.
5. The pending item is converted to a transaction and removed from the queue.

---

## 7. Backup / restore verification

1. After step 4 above completes, open the existing backup/export feature from settings and export to a file.
2. Open the resulting JSON inside the ZIP and confirm:
   - `smsTemplates` contains your mapped templates with their classification + wildcard mappings.
   - `senderAccountLinks` contains the wallet ↔ sender mapping you created.
   - `smsWatermarkEpochMillis` is non-null and matches the timestamp of the most recent processed SMS.
   - **No `pendingReviewItems` array exists**, no raw SMS bodies appear anywhere — even inside transaction metadata, the body is replaced by the `smsSourceDedupKey` hash.
3. Uninstall the app, reinstall, and import the backup.
4. Re-open the SMS Extraction screen — your templates and links are restored, but the Pending Review queue starts empty.
5. Tap **Sync SMS**. The scan re-runs against the device inbox using the restored watermark; previously-processed messages are NOT re-processed (verified by the transaction count remaining unchanged), but any genuinely-new SMS that arrived during the reinstall window is picked up.

---

## 8. Common pitfalls

- **Forgetting to grant READ_SMS** — the app shows the permission gate. If the user denied with "Don't ask again", they must grant via system settings; the screen rechecks on resume.
- **Inserting a test SMS but not re-launching** — pull-mode means the app does not see new SMS until the next scan. Tap **Sync SMS**.
- **Watermark not advancing** — usually means a `STORAGE_ERROR:` was swallowed. Check Logcat for the use-case-level Either.Left.
- **Drain producing too many small templates** — verify the similarity threshold is `0.5` and the depth is `4` in the parser implementation. Lower threshold merges over-aggressively; higher fragments.
- **Unique constraint violation on sender_account_link** — the user is trying to link a sender already linked elsewhere. The UI is supposed to catch this pre-write; if you see the DB exception in Logcat, the pre-write check is missing.
