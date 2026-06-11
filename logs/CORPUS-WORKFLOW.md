# SMS corpus workflow — fast parser regression loop

The parser used to require: build app → install on device → trigger sync →
read logcat → guess what broke. This loop replaces that with a JVM-only test
that runs in <10s against your real SMS history.

## Files in this folder

- **dump-sms.bat / .ps1** — dumps SMS bodies for the named senders into a
  timestamped `*_sms-dump.txt` (gitignored). UTF-8 throughout.
- **corpus-build.bat / .ps1** — parses a dump file, auto-clusters messages by
  sender + token-count + first-five-stable-tokens (depth 5, kept in sync with
  `DrainParser.PREFIX_DEPTH` and recorded in the corpus header as
  `prefixDepth`), writes the clustered JSON to
  `feature/sms-sync/src/test/resources/sms-corpus.json` (gitignored). It warns
  loudly when the corpus on disk was generated at a different depth.
- **capture.bat** — unrelated: UTF-8-clean capture of `adb logcat -s SmsTrace
  LoanTrace AndroidRuntime ActivityManager` for live debugging.

## End-to-end loop

```powershell
# 1. Pull SMS for the senders you care about (UTF-8 safe).
logs\dump-sms.bat VF-Cash BanK-AlAhly "HDB t 19995" "EGYPT POST"

# 2. Build the corpus from the dump.
logs\corpus-build.bat logs\2026-05-09_19-33-55_sms-dump.txt

# 3. Run the corpus test (no device, no build of the app).
gradlew :feature:sms-sync:testDebugUnitTest --tests "com.ivy.sms.parser.SmsCorpusTest"
```

> **Warning — regeneration destroys annotations.** `corpus-build` always
> writes every cluster with `"annotation": null` and no `expectedValues`, so
> re-running step 2 silently wipes any hand-written annotation work in the
> existing `sms-corpus.json`. Copy the file aside before regenerating and
> port your annotation blocks back in afterwards.

The first run will:

- Fail with "no annotated clusters yet" on the second test — that's expected.
- Print the Drain clustering result on the first test (corpus → drain
  cluster mapping). Use this to spot fragmentations and merges.

## Adding annotations

Open `feature/sms-sync/src/test/resources/sms-corpus.json`. Each cluster
starts with `"annotation": null`. Replace with:

```json
"annotation": {
  "label": "vf-cash-receive-money",
  "expectedPattern": "تم استلام مبلغ <*> جنيه من رقم <*> ؛ المسجل بإسم <*> رصيد حسابك الحالي <*> جنيه",
  "expectedRoles": [
    { "position": 3, "role": "Income" },
    { "position": 6, "role": "Merchant" },
    { "position": 9, "role": "Merchant" },
    { "position": 14, "role": "CurrentTotal" }
  ]
}
```

Run the test again. The second test now exercises that cluster: builds a
fake `SmsTemplate` and calls `extractWildcardValues` for every body in the
cluster. Failures print `body=` plus where alignment broke.

## Adding per-message ground truth

Once a cluster aligns cleanly, you can pin the extracted values:

```json
{
  "rowId": 86,
  "timestamp": 1776318800926,
  "body": "...",
  "expectedValues": {
    "Income": "60",
    "CurrentTotal": "416.44"
  }
}
```

Bootstrap shortcut: run the test once with just the cluster annotation, copy
the printed extractions into `expectedValues`, eyeball for correctness, then
re-run. Any future regression in the parser changes those values and the
test fails.

## Allowed role names

`Income`, `Expense`, `Transfer`, `CurrentTotal`, `TransactionFee`,
`DateFull`, `DateOnly`, `TimeOnly`, `Merchant`, `Ignored`, `Unmapped`.

## Privacy

Both the dump file and the corpus contain real SMS bodies (account numbers,
balances, names). Both are in `.gitignore` and never get committed. To share
a fixture upstream, write a redactor that masks digit runs longer than 4 and
proper names — out of scope for the current loop.
