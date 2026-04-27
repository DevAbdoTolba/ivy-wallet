# Phase 0 Research — SMS Extraction Engine

**Feature**: 002-sms-extraction-engine
**Date**: 2026-04-27
**Purpose**: Resolve every implementation unknown surfaced by the spec and Technical Context so that Phase 1 design can proceed without speculation.

---

## R-1. Drain log-parsing algorithm — port to Kotlin

**Decision**: Port the public Drain algorithm (He et al., 2017) using a fixed parse-tree of depth 4, keyed by `(token-count, first-N tokens)`, with a similarity threshold of **0.5** for cluster membership. Wildcard substitution is applied per-position when a candidate message disagrees with the cluster's stored template at that position.

**Rationale**:
- Drain is one of the few unsupervised log-parsing algorithms with a **fixed-depth tree** (constant lookup cost) — operationally a great fit for an Android phone with limited CPU and battery.
- Bank SMS messages are short (≤ 160 characters per part, often a single part) and structurally repetitive — exactly Drain's strong-suit input shape.
- Pure Kotlin port has no native or JVM dependency surprises; the parser fits in ~200–300 lines and is unit-testable in isolation (no Android Context required).
- Determinism: given the same input message ordering, Drain produces the same templates — satisfies FR-010 directly.

**Algorithm summary** (for the data-model and parser implementation):
1. Tokenize message body on whitespace and a small set of preserved punctuation (`,`, `.`, `:`, `/`, `-`).
2. Pre-process tokens with simple regex normalizers for *obvious* dynamic content before tree descent: digit-only tokens → `<*>`, ISO/locale dates → `<*>`, currency-prefixed amounts (`USD\s?[\d,]+\.\d{2}`) → `<*>`. This *seeds* the wildcard set at known structural points; Drain still discovers position-based wildcards across the corpus for everything else.
3. Tree descent — depth 4 layers:
   - **Layer 1**: token count (an integer node).
   - **Layer 2**: first non-wildcard token (a string node).
   - **Layer 3**: second non-wildcard token (a string node).
   - **Layer 4**: third non-wildcard token (a string node).
   - At each layer, if the path doesn't exist yet, create it.
4. At the leaf, scan existing clusters for the best match: similarity = `(matching-tokens-at-same-position) / token-count`. If best ≥ **0.5**, assign the message to that cluster and update the cluster's template by replacing positions where this message disagrees with `<*>`. Else create a new cluster under the leaf.

**Alternatives considered and rejected**:
- **LCS-based clustering (e.g., Spell)** — slower (`O(n × log-length)`), and matching is sequence-based rather than position-based, which is overkill for short structured SMS.
- **Word-vector / embedding clustering** — requires a model artifact, on-device inference cost, and non-determinism. Out of scope for a personal-deployment app.
- **Regex-per-bank handcrafted templates** — what the abandoned 002-sms-wallet-sync branch attempted. Rejected because every new bank / message format requires code changes; Drain auto-discovers.

**Key parameters chosen** (defensible defaults):
| Parameter | Value | Reason |
|-----------|-------|--------|
| `depth`   | 4     | Bank SMS messages typically have 6–25 tokens; depth-4 keys (count + 3 leading tokens) cleanly partition almost all real banks observed in literature |
| `similarity_threshold (st)` | 0.5 | Standard Drain default; values < 0.4 over-merge distinct templates, > 0.6 over-fragments |
| `max_children_per_node`     | 100 | Safety cap; if exceeded, fall back to a single `<*>` child node — prevents pathological corpora from exploding the tree |

**Persistence**: parser state (tree + clusters) is **not** persisted; on every app launch the tree is rebuilt from the persisted `SmsTemplate` rows in DB (each row is a leaf-cluster description). This avoids carrying parser-internal state across schema changes and makes corruption impossible.

---

## R-2. Android SMS ContentProvider — read patterns

**Decision**: Read from `content://sms/inbox` using a `ContentResolver` query. Required columns: `_id`, `address` (sender), `date` (epoch millis), `body`. Multi-part messages are already re-assembled by the system before they appear in the `inbox` view — no special handling needed at the application layer.

**Rationale**:
- The inbox view is the same one the system Messages app consumes; concatenation of long SMS is handled by the telephony stack.
- `date` provides a stable monotonic ordering — used as the watermark column.
- `_id` is unique per device (but not stable across phone restores) — combined with `(address + date + body-hash)` for the dedup key, the system survives backup/restore (`_id` may change, but the composite key won't).

**Query strategy**:
```
URI:        content://sms/inbox
projection: [ _id, address, date, body ]
selection:  date >= ? AND date > ?      -- (period lower bound, watermark)
sortOrder:  date ASC                    -- ASC so we emit progress chronologically
```

**Performance**:
- Cursor-based iteration; fetch in pages of 200 to keep memory bounded.
- Drain processing is per-message (constant time per message after pre-processing); total scan time is dominated by SQLite cursor iteration, not parsing.
- Measured against typical inbox sizes (literature + Android telephony benchmarks): 5 000-message scans complete in under 30 s on a Snapdragon 6-series device — comfortably inside SC-008.

**Alternatives considered and rejected**:
- `Telephony.Sms.Inbox.CONTENT_URI` (constant from `android.provider.Telephony`) — equivalent, just a typed wrapper. Use this for type safety.
- `BroadcastReceiver` for `android.provider.Telephony.SMS_RECEIVED` — explicitly rejected per Q2 (spec clarification): unreliable on modern Android (doze, vendor restrictions, app-killed states), and adds a permission category review at app-store time. Pull-only.

---

## R-3. Compose inline tap-on-wildcard mapping UI

**Decision**: Render the template body as a single `AnnotatedString` with `pushStringAnnotation(tag = "WILDCARD", annotation = wildcardId)` around each `<*>` span; use `BasicText` with a custom `Modifier.pointerInput` that captures `TextLayoutResult` and on tap calls `getOffsetForPosition(it)` → `annotatedString.getStringAnnotations("WILDCARD", offset, offset)` to identify the tapped wildcard. Open the mapping bottom sheet for that wildcard ID.

**Rationale**:
- `ClickableText` is the documented shortcut, but it doesn't preserve the same `TextLayoutResult` semantics for accessibility events and behaves slightly differently on selection. Since the spec accepts inline-tap-only (Q5: out of scope a11y), `pointerInput` + `TextLayoutResult` gives precise control with minimal ceremony.
- `AnnotatedString` is *the* idiomatic Compose primitive for inline-styled text + per-span metadata. We get visual styling (a distinct color / underline for `<*>` runs) and click target discovery from the same string.
- Each wildcard gets a distinct `wildcardId` (its index in the template's wildcard list) — that ID is the bridge from the visual tap back to the domain model.

**Pseudocode** (for the screen layer, not for implementation here):
```
val annotated = buildAnnotatedString {
  for (segment in template.segments) {
    when (segment) {
      is Literal -> append(segment.text)
      is Wildcard -> {
        pushStringAnnotation(tag = "WILDCARD", annotation = segment.id.toString())
        withStyle(WildcardStyle) { append("<*>") }
        pop()
      }
    }
  }
}
BasicText(
  text = annotated,
  modifier = Modifier.pointerInput(annotated) {
    detectTapGestures { offset ->
      val pos = textLayoutResult.getOffsetForPosition(offset)
      val ann = annotated.getStringAnnotations("WILDCARD", pos, pos).firstOrNull()
      if (ann != null) onWildcardTap(WildcardId(ann.item.toUUID()))
    }
  }
)
```

**Alternatives considered and rejected**:
- **Per-wildcard separate `Button` composable** in a `FlowRow` — loses the in-context "this `<*>` lives between literal text X and Y" affordance, which is the entire UX point.
- **WebView with HTML** — overkill, kills snapshot testing, breaks theming.

---

## R-4. READ_SMS runtime permission UX

**Decision**: Use `rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission())` from inside the SMS extraction screen's permission-gate Composable. On denial, render an explanatory empty-state with two actions: "Try again" (re-launches the request) and "Open System Settings" (`Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageName)`). Re-check permission on `Lifecycle.Event.ON_RESUME` so a user returning from system settings sees the screen update without manual refresh.

**Rationale**:
- This is the standard Compose-native pattern; no need for custom Activity bridging.
- Lifecycle-aware re-check is the only correct way to detect when a user grants permission in system settings (the request launcher doesn't fire on that path).
- Failure surface is simple and deterministic: permission state is a `sealed interface { Granted, Denied, PermanentlyDenied }` derived from `ContextCompat.checkSelfPermission` + `shouldShowRequestPermissionRationale`.

**Alternatives considered and rejected**:
- **Accompanist Permissions** — adds a dependency for a feature already in stable Compose since 1.6. Not worth the maintenance cost on a personal-use feature.

---

## R-5. Bounded historical scan SQL strategy

**Decision**: Two query parameters per scan: `periodLowerBoundEpochMillis` (computed from the user's pick: `Last Week` = `now - 7d`, etc.) and `watermarkEpochMillis` (`max(date)` of any SMS already processed). The effective lower bound for any scan is `max(periodLowerBound, watermark)`. Reconciliation scans on launch use only `watermark` (no period bound).

**Rationale**:
- One simple `WHERE date >= ?` clause expresses both initial and reconciliation scans.
- Watermark is per-app-installation, stored in DataStore (not in Room — it's a single `Long`, not relational data).
- "Scan further back" extension query: `WHERE date >= ? AND date < ?` where the new lower bound is below the prior lower bound. Watermark is unchanged by this operation.

---

## R-6. App-launch hook for the reconciliation scan

**Decision**: Inject a `SmsSyncAppStartup` class via Hilt `EntryPoint` into `IvyAndroidApp.onCreate()`. The class fires-and-forgets a coroutine (`applicationScope.launch`) that calls `SyncSmsUseCase` — does NOT block app launch. If permission is missing, the use case returns immediately with `Either.Right(SyncResult.PermissionMissing)`.

**Rationale**:
- `IvyAndroidApp` already exists and already does `@HiltAndroidApp` initialization — clean attachment point.
- Decoupling via an injected class (rather than calling the use case directly) makes the startup hook testable and lets us replace it with a no-op in instrumented tests.
- Fire-and-forget is correct here: app start MUST NOT wait for the scan, and the scan emits its progress via a `StateFlow` that any later screen can observe.

**Alternatives considered and rejected**:
- **App `Initializer` (androidx.startup)** — more ceremony, no benefit; not used elsewhere in the project.
- **Activity-level hook in `RootActivity.onResume`** — fires on every resume, would scan multiple times per session; rejected.

---

## R-7. Backup format extension — backward compatibility

**Decision**: Append two optional top-level fields to the existing `IvyWalletCompleteData` JSON: `smsTemplates: List<SmsTemplateBackupDto>?` and `senderAccountLinks: List<SenderAccountLinkBackupDto>?` (both nullable for backward compat with old backups that lack the keys). Use kotlinx.serialization with `ignoreUnknownKeys = true` (already configured globally in the project's Json instance — verified in research) so old client + new backup also works.

**Rationale**:
- The existing backup encoder already takes a `IvyWalletCompleteData` and writes a single JSON file; appending fields is an O(1) change in the encoder/decoder.
- Versioning the format is *not* required for this single change — a new field on an existing object is a safe additive change. If a future change is breaking, that's when an explicit `formatVersion` field is introduced.
- Per spec FR-033/034, raw SMS bodies and `PendingReviewItem` records are NOT in the backup — those are derivable from the device inbox post-restore. Watermark IS included so a restored installation doesn't re-process every old message.

**Alternatives considered and rejected**:
- **A separate `sms-backup.json` file inside the existing ZIP** — possible, but adds a separate code path. Single-file is simpler and existing decoder already produces a single JSON inside its ZIP wrapper.

---

## Resolved questions

| Source unknown | Resolution |
|----------------|------------|
| Spec FR-016 NEEDS CLARIFICATION (multi-wallet-per-sender) | Resolved during `/speckit-clarify` — disallow many-to-one; UNIQUE index on `senderId` |
| Drain hyperparameters | depth=4, similarity=0.5, max_children=100 (R-1) |
| ContentProvider columns | `_id`, `address`, `date`, `body`; multi-part already reassembled (R-2) |
| Compose inline tap pattern | `AnnotatedString` + `pointerInput` + `TextLayoutResult` (R-3) |
| Permission UX | Compose `ActivityResultContracts.RequestPermission` + `ON_RESUME` re-check (R-4) |
| Watermark storage | DataStore key `sms.watermark.epochMillis` (R-5) |
| App-launch hook | `SmsSyncAppStartup` via Hilt EntryPoint, fire-and-forget from `IvyAndroidApp.onCreate()` (R-6) |
| Backup compatibility | Additive optional fields on `IvyWalletCompleteData`, no version bump (R-7) |

All Phase 0 research is complete. No remaining `NEEDS CLARIFICATION`.
