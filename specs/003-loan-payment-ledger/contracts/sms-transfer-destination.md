# Contract: SMS Transfer Destination & Ghost Repair (Project C)

**Provider**: `feature/sms-sync` + `shared/data/core` (schema)
**Consumers**: routing pipeline, pending review UI, template mapping UI, backup

## Template surface

- `SmsTemplate.transferToAccountId: AccountId?` (entity column `sms_template.transferToAccountId`,
  migration 135→136). NULL = ask-per-message. Set/cleared in `TemplateMappingScreen` (row appears
  only while a `WildcardRole.Transfer` slot is assigned) and via the review card's "remember"
  checkbox (`SmsTemplateRepository.updateTransferDestination(templateId, accountId?)`).
- Frozen-template invariant is NOT violated by destination writes: `transferToAccountId` is
  routing config, not pattern/slot data. Drain re-merge freezing (pattern/slots) is untouched.

## Routing behavior (`CreateTransactionFromSmsUseCase` + `RouteSmsUseCase`)

| Condition at route time | Result |
|---|---|
| destination set, ≠ source, account exists | `Transfer(from=linked, to=destination, toValue=fromValue)` committed silently (Tier 1) |
| destination NULL | `CreateFromSmsOutcome.MissingTransferDestination` → quarantine `TRANSFER_NEEDS_DESTINATION` |
| destination == source (misconfig) | same quarantine — never an error toast, never a write |
| destination account deleted | same quarantine (validated against account repo each route) |

- **Write-guard (FR-009)**: the use case refuses to persist any transfer with
  `fromAccount == toAccount` on EVERY path including manual resolve — final assertion before
  `transactionRepository.save`, returns typed `Left`, never throws.
- New quarantine reason `TRANSFER_NEEDS_DESTINATION` joins the existing enum
  (`SENDER_NOT_LINKED`, `TEMPLATE_NOT_MAPPED`, `AMOUNT_NOT_PARSEABLE`, `CURRENCY_MISMATCH`,
  `AUTO_ROUTE_DISABLED`). Precedence: evaluated AFTER existing checks — an unmapped/currency-
  mismatched message keeps its existing reason (spec edge case).
- Fee aggregation for transfers (`amount + fee`) is unchanged.

## Pending review surface

- Items with `TRANSFER_NEEDS_DESTINATION` render: SMS snippet, fixed From (linked wallet),
  destination picker (all wallets except From), "Remember for this template" checkbox,
  CTA `Create transfer −{amount}`.
- Resolve path: `ResolvePendingItemUseCase` gains an optional `destinationOverride: AccountId`;
  resolve with override commits the transfer; with remember=true also persists the template
  default BEFORE the drain replays (so `drainPending` fanout routes siblings consistently).
- `drainPending` triggers: existing active-template-set trigger PLUS destination-set events
  (template gaining a destination re-drains its quarantined siblings).

## Ghost repair (`RepairGhostTransfersUseCase`)

- One-shot, versioned via SharedPrefs int key `GHOST_TRANSFER_REPAIR_VERSION` (target = 1);
  runs inside the SyncSms mutex, after renormalization, before parser seeding.
- Selection: `type=TRANSFER AND accountId==toAccountId AND smsSourceDedupKey IS NOT NULL`.
- Action: delete rows → per affected sender, run `ReprocessHistoricalUseCase` from
  `min(ghostDate)-1ms`. Re-encountered messages flow the table above (⇒ review items).
- MUST NOT: reset any watermark, touch non-SMS transactions, touch valid transfers, run twice
  (version guard), or block first frame (IO dispatcher inside existing sync flow).
- Telemetry: SmsTrace lines `ghost-repair: found=N deleted=N senders=[...] resurfaced=M`
  (owner captures via `capture.bat ghost_repair`).

## Invariant preservation checklist (FR-025 / SC-007)

- dedupKey identity: untouched (raw-body hash). Deleting ghost rows FREES their keys —
  intended, so reprocess can re-route the same messages.
- Per-sender watermarks: reprocess uses `historicalLowerBound` mechanism; global watermark
  never reset.
- Frozen templates: no pattern/slot rewrites anywhere in this project.
- Single aligner: unchanged; destination logic sits after extraction, not in alignment.
- Singleton + dedup mutex on `CreateTransactionFromSmsUseCase`: unchanged; new outcome type
  only adds a return variant.
