# Public warnings relayed over the mesh (MESHSAT-1734)

The Android half, written so the iOS port can mirror it screen for screen. The wire is the
Bridge's (`internal/warnings/relay.go`, `MeshText`); nothing here changes it.

## What the kit sends

A MeshSat kit that holds a verified warning for its area puts the same words on the mesh twice:

- on **ALERT_APP, port 11**, with a BEL (0x07) at the end, so a node with its alert setting on
  buzzes by itself;
- as a **plain text on the primary channel**, for the phones and nodes that show texts only.

Two lines, at most 200 bytes, the first cut when it has to be, the second kept whole:

```
[TEST ][UPDATE: ]WARNING <event> <severity>[ until HH:MM UTC][: <text>]
Relay of <source> warning, issued <d Mon HH:MM> UTC via MeshSat, not an official NL-Alert
```

and for a warning taken back:

```
[TEST ]CANCELLED: <event>[ - <text>]
Relay of ...
```

Events are one word: Other, Wind, Rain, Snow/ice, Thunderstorm, Fog, Heat, Cold, Coastal, Flood,
Avalanche, Wildfire, Tornado. Severities: Unknown, Minor, Moderate, Severe, Extreme. Sources:
"KNMI via MeteoAlarm", "DWD", "MeshSat test".

## How the phone recognises one (`warnings/WarningText.kt`)

| Heard on | Is a warning when | Otherwise |
|---|---|---|
| port 11 | always; parsed when it has the shape above | kept whole as an alert, event "Alert" (another app's alert still alarms) |
| port 1 (a text) | the first line has the shape above **and** the second line starts with `Relay of ` | an ordinary chat message: a person typing "WARNING ..." has no relay line |

The relay line is the kit's mark. The sender is any mesh node: the kit's own node, or a node that
rebroadcast it. "Relay of <node>" on the banner names the node the phone heard it from.

`WarningText.recognise(text, port)` gives `kind` (alert, update, cancel), `test`, `event`,
`severity` ("" when the line carried none), `until` ("HH:MM" UTC or ""), `text`, `label` (the relay
line), `headline`. A first line cut mid-way still gives the event and the severity. The BEL and
trailing whitespace are dropped first.

## State (`warnings/WarningIntake.kt`, Room table `warnings`, migration 23 -> 24)

| column | |
|---|---|
| `id` | row id |
| `received_at` | ms (index) |
| `port` | 11 or 1 |
| `sender`, `sender_name` | the node ("!a1b2c3d4") and its long name when known |
| `kind` | `alert`, `update`, `cancel` |
| `test` | 0/1 |
| `event`, `severity`, `until_utc`, `text`, `label` | as parsed |
| `raw` | the text as it came, BEL dropped |
| `dedup_key` | the two lines with whitespace folded (index) |
| `cancelled_at`, `replaced_at`, `acknowledged_at` | ms, 0 = not |

Rules, unit-tested on an in-memory copy of the table and on the real one:

- **One warning, one alarm.** The same words heard again within **15 minutes** (`DEDUP_MS`) add
  no row: the kit's port-11 and port-1 copies, a second kit in range, a mesh rebroadcast.
- **The newest stands.** An alert or an update of an event marks the standing warnings of that
  event `replaced_at`; the list keeps them.
- **A cancel clears its event.** `CANCELLED: <event>` marks the standing warnings of that event
  `cancelled_at` and is kept as a row of its own (the all-clear in the list), acknowledged from
  the start. A test cancel clears test warnings only; a test never replaces a real warning.
- **Dismiss is not clear.** Dismissing the banner sets `acknowledged_at`; the warning stands
  until its cancel or a newer one.
- Rows older than **30 days** are pruned when the gateway starts.
- A relayed warning is never a message in the mesh chat and is never forwarded by the routing
  rules: the kit relays, the phone alarms.

## Notification (`warnings/WarningNotifier.kt`)

Two channels, created at app start (a channel's sound cannot change once it exists):

| Channel | id | Importance | Sound | For |
|---|---|---|---|---|
| Warnings | `meshsat_warnings` | HIGH | the phone's alarm sound, `USAGE_ALARM`, vibration 600/250 x3, red light, public on the lock screen, `setBypassDnd(true)` | an alert or an update |
| Warning tests and all-clears | `meshsat_warning_tests` | HIGH | the normal notification sound, vibration | a `TEST` warning, a cancel |

Do Not Disturb is respected as the platform does for alarms: a DND mode that lets alarms through
lets a warning through; the bypass flag is honoured only once the person grants the app Do Not
Disturb access, never before. The phone never asks for that access by itself.

The notification: title `WARNING Wind Severe until 10:11 UTC` (`TEST WARNING ...`, `UPDATE ...`,
`CANCELLED Wind`), big text = the warning's text, the relay line, then `Relay of <node>`; category
ALARM; a tap opens the Warnings list (`MainActivity.EXTRA_ROUTE = "warnings"`). The id follows the
event (`idFor(event, test)`), so the cancel of an event takes its alarm down and posts the
all-clear on the quiet channel; a test keeps its own id.

## UI

- **While the app is open** (`ui/screens/WarningsScreen.kt`, `WarningOverlay`, drawn over every
  screen but the list): the newest standing warning not yet dismissed fills the screen on a
  scrim, in a card bordered red (amber for a test): the title, "A test. No action is needed." for
  a test, the text, the relay line, `Relay of <node>`, "Heard <time>", **Dismiss** and **All
  warnings**. Nothing under it takes a tap.
- **Setup > Safety > Warnings** (`WarningsScreen`, route `warnings`): every warning heard, newest
  first, each with its state: Standing, Test, Replaced <time>, Cancelled <time>, All-clear; the
  relay line; "<node>, alert|text, heard <time>".

## Tests

- `WarningTextTest` (JVM): the Bridge's vectors on port 11 and as a text, a chat with no relay
  line, update, cancel, test, cut lines, an alert of another shape, the dedup key.
- `WarningIntakeTest` (JVM): the rules above on an in-memory `WarningDao`.
- `WarningsChannelTest` (instrumented): the two channels as created, the id and channel per kind.
- `WarningRoomTest` (instrumented): the migration 23 -> 24 keeps the messages; the intake on SQLite.
