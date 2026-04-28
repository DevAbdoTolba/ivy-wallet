# SMS Sync — extending wildcard mapping targets

The SMS extraction pipeline maps each `<*>` wildcard in a discovered template to one of a small fixed set of *roles* (Amount, Merchant, DateTime, Reference, Ignored). To add a new role — for example, when bank SMS start carrying a category hint — extend the `WildcardMapping` sealed interface in `feature/sms-sync/src/main/java/com/ivy/sms/domain/model/SmsTemplate.kt`, add a new `data object` next to the existing entries, then thread it through three places:

1. The mapping bottom sheet `feature/sms-sync/src/main/java/com/ivy/sms/ui/templates/WildcardMappingBottomSheet.kt` — add a new label/option entry.
2. `CreateTransactionFromSmsUseCase` — read the new wildcard value and feed it into the appropriate `Transaction` field.
3. `SmsTemplateMapper.asString` / `mappingFromString` — extend the string round-trip so the new mapping survives Room storage and JSON backup.

The Drain parser, the inbox data source, and the routing logic stay the same — they don't care about role semantics, only positional alignment.
