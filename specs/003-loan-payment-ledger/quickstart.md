# Quickstart: 003-loan-payment-ledger

**Owner-run commands** — per project convention the agent hands commands over and the owner
executes them (build machine + device are owner-side).

## Build & unit test

```powershell
# full debug build (APK lands in app/build/outputs/apk/debug/app-debug.apk)
.\gradlew :app:assembleDebug

# fast compile check while iterating
.\gradlew compileDebugSources

# unit tests, per project touched in the current story
.\gradlew :shared:data:core:testDebugUnitTest      # migrations 135→136, 136→137 (+ backfill idempotence)
.\gradlew :feature:sms-sync:testDebugUnitTest      # routing, write-guard, ghost repair
.\gradlew :feature:loans:testDebugUnitTest         # pay/unsettle/link use cases, direction table
.\gradlew :app:testDebugUnitTest                   # RootViewModel lock-delay behavior

# Paparazzi goldens: record once when a new UI state is added, then verify
.\gradlew :feature:loans:recordPaparazziDebug
.\gradlew :feature:loans:verifyPaparazziDebug
```

Known-red that stays red (do NOT "fix"): `SmsCorpusTest.annotated_clusters_align_and_extract_correctly`
— 43 bank-alahly-14 failures, pre-existing, corpus needs regeneration (see 002 notes). CI skips it.

## On-device UAT scripts (per story, in build order)

Install: `adb install -r app\build\outputs\apk\debug\app-debug.apk`
Every capture goes through the standard script — start it BEFORE the scenario:

### US1/US2 — lock overlay (`logs\capture.bat lock_overlay_uat`)
1. Settings → enable app lock; set "Lock after" = Instantly.
2. Open a new expense, type amount 249.50 + title, do NOT save. Screen off, screen on, unlock.
   **Expect**: form intact, cursor preserved, no reload flash.
3. Open the loan payment sheet mid-typing, repeat. **Expect**: sheet still open, amount intact.
4. Recents view while locked → **expect** no content visible.
5. Back press while locked → **expect** nothing happens.
6. Set delay = 5 min, leave for ~1 min → **expect** no lock prompt on return.

### US3 — transfer destination (`logs\capture.bat transfer_destination_uat`)
1. Map a transfer-format template WITHOUT destination → send/receive a matching SMS → Sync now.
   **Expect**: review badge; card shows "needs destination"; no transaction anywhere.
2. Resolve with To=Cash, remember=OFF → **expect** Bank −X, Cash +X visible transfer.
3. Next matching SMS → **expect** review again (not remembered).
4. Resolve with remember=ON → next SMS auto-creates silently. **Expect** zero review items.
5. Template mapping screen → set destination = source wallet attempt: picker must not offer From.

### US4 — ghost rescue (`logs\capture.bat ghost_repair`)
1. On a device that has old invisible transfers (pre-update data): install update, open app,
   let sync run. **Expect** SmsTrace `ghost-repair: found=N…` and N review items appear.
2. Kill + reopen → **expect** `found=0`, no duplicates (version guard).
3. Resolve one rescued item with a destination → money appears with the ORIGINAL SMS date.

### US5/US6 — entry payments (`logs\capture.bat entry_payment_uat`)
1. Loan (Lent) with entry 1,200. Tap circle → sheet pre-filled 1,200/loan wallet/today.
   Confirm. **Expect**: entry settled, wallet +1,200, transaction visible & tagged, loan
   header paid +1,200 exactly (no double count).
2. Second entry: edit amount to 500 → **expect** partial state (half circle, "paid 500 · left 700"),
   next tap pre-fills 700.
3. Try amount 0 / negative / 5,000 (> remaining) → **expect** confirm blocked with reason.
4. Expand entry → payment rows with wallet+date; tap tx-link → lands on the transaction.
5. Un-settle the settled entry → dialog states real counts → confirm → **expect** wallet
   balance restored, entry open again.
6. Legacy entry (settled before update) → **expect** "settled — no transaction" badge; loan
   paid total UNCHANGED vs before the update (migration backfill check); un-settle says
   "no transaction to remove".
7. Double-tap the circle fast → **expect** one sheet, one payment.

### US7 — link to loan (`logs\capture.bat link_to_loan_uat`)
1. SMS-created expense (money out) → transaction detail → Link to loan → pick Lent loan.
   **Expect**: "lent more" (INCREASE), no allocation step, loan remaining grows, wallet
   balance UNCHANGED at link time, no second transaction.
2. SMS income (money in) → link to same Lent loan → **expect** allocation step; AutoOldestFirst
   fills oldest entry first; remainder (if any) becomes loan-level.
3. Verify transaction now shows loan tag; loan payment history row jump-links back to it.
4. Delete that transaction → **expect** loan record gone too, totals recomputed.
5. Attempt link on an already-linked transaction → **expect** action not offered.

## Data safety notes for UAT

- Take a backup export BEFORE installing a build containing migration 137 (one-way schema).
- The ghost repair deletes only rows that were already invisible in the UI; valid transfers
  (`accountId != toAccountId`) are untouched — verify with scenario US4.3.
- All SMS bodies stay on-device; `logs\*.log` is gitignored (bank PII).
