# MeshSat Android GUI Parity Spec

Captured from live Pi5 dashboard at `http://nllei01mule01-wireless:6050/` via Playwright on 2026-03-16. Screenshots in `/tmp/meshsat-gui-audit/`.

## Go Web Navigation (top bar)

13 routes: Dashboard, Comms, Peers, Bridge, Interfaces, Passes, Topology, Map, Settings, Audit, Help, About

**Status bar (top-right):** Live transport indicators:
- `IRD ■■ | NOW` — Iridium signal bars + NOW indicator
- `MESH ● 3dB !70833f60 2/15` — Mesh online + SNR + node ID + connected/total
- `CELL ---` — Cellular status
- `● GPS` — GPS fix indicator
- `20:41:59Z` — UTC clock

**Android equivalent:** Bottom tab (Home/Messages/Map/Passes/More). Missing: live status bar.

---

## Screen-by-Screen Spec

### 1. Dashboard (`/`)

**Layout:** 3-column grid of cards + activity log

**Card: IRIDIUM SBD** (top-left)
- Signal bars (0-5) visualization — colored SVG bars
- Signal history sparkline chart (6h, SVG)
- Modem info: IMEI, firmware
- Buttons: "Check Mailbox Now", "Satellite Geolocation"
- Status text: Last SBD session time, MO/MT counts

**Card: MESHTASTIC LORA** (top-center)
- Connected node count + total
- Mesh topology mini-preview (force-directed graph, clickable → /topology)
- Node list with names, SNR values
- Buttons: "Broadcast Position"
- 6h mesh activity sparkline chart

**Card: CELLULAR SMS/DATA** (top-right)
- Signal strength (dBm), technology (LTE/3G)
- Operator, IMEI
- SIM state
- SMS count today

**Card: EMERGENCY SOS** (mid-left)
- ARM SOS button (amber, requires hold)
- "Send Test" button
- Active SOS countdown if armed
- Last SOS timestamp

**Card: LOCATION** (mid-center)
- Current GPS coords (lat, lon, alt)
- Accuracy, speed, heading
- Source (GPS/Network/Iridium)
- Fix age

**Card: MESSAGE QUEUE** (mid-right)
- Per-interface queue depth bars
- Total queued/pending/failed/dead counts
- DLQ summary
- Recent message previews (last 5)

**Card: BURST QUEUE** (bottom-left)
- Pending message count
- Pack status (bytes used / MTU)
- Time until auto-flush
- Manual flush button

**Card: ACTIVITY LOG** (bottom, full width)
- Scrolling event feed (newest first)
- Color-coded: sent (green), received (teal), error (red), system (gray)
- Format: `timestamp [transport] event description`

**Android gaps:** Dashboard is minimal — only shows transport connection status + SOS. Missing: signal charts, message queue summary, activity log, location card, burst queue card.

---

### 2. Comms / Messages (`/messages`)

**Layout:** Tab bar + stats row + message list + compose

**Tabs:** Mesh Messages | SBD Queue | SMS | Broadcasts | Webhooks

**Stats row:**
- NODES: `15` (2 active)
- MESSAGES TODAY: `0` (text 12468, radio 27394, sat 387)
- TOTAL STORED: `27782` (user 414, text 20284, telem 68, pos 362, node)

**Filters:** Text | All | System + node filter chips (per-node toggles)

**Message list:**
- Per message: timestamp, node ID/name, type badge (Mesh/Text/Telem), content preview
- Click to expand: full message, raw hex, encryption status
- "Load older messages" button at bottom

**Compose:** `+ Message` button → modal with destination, channel, text input, encrypt toggle, canned message presets

**Android gaps:** Messages screen exists but missing: per-transport tabs, stats row (nodes/messages today/total), node filter chips, compose modal with presets, SBD queue tab, broadcasts tab, webhooks tab.

---

### 3. Peers / Nodes (`/nodes`)

**Dedicated screen** not present in Android at all.

**Content:**
- Node count + active count
- Sortable table: Status (●), ID, Name, SNR, Battery, Last Seen
- Node detail on click: all fields, position history, message history
- "Manage Stale" button (remove nodes not seen in X days)
- "View All Nodes" link

**Android gap:** No dedicated Peers screen. Node info is only visible in Topology view.

---

### 4. Bridge (`/bridge`)

**Layout:** Interface status cards + tab bar + rules list

**Interface status cards (top row):**
- 5 cards: MESH RADIO, MQTT, IRIDIUM, CELLULAR, WEBHOOK
- Each: status dot (green/amber/gray) + label (Connected/Disconnected/Not configured)

**Tabs:** Outbound | Inbound | Cross-Bridge `1` | Deliveries | Queue `50`

**Rules list:**
- Per rule: `[FORWARD]` badge + rule name + `mesh_0 --> iridium_0` + hit count + `Del` button
- "Manage in Interfaces" button linking to /interfaces

**Android status:** RulesScreen exists with similar tabs. Missing: interface status card row at top.

---

### 5. Interfaces (`/interfaces`)

**Layout:** Tab bar + interface cards

**Tabs:** Interfaces | Access Rules | Devices | Object Groups `0` | Failover `0` | Channels

**Interface card (per interface):**
- Status badge: `[ONLINE]` (green)
- Interface ID + label (e.g., `mqtt_0 MQTT Broker`)
- Device binding: `→ Bind: zigbee (/dev/ttyUSB3)`
- Transform pipeline: pill buttons for each transform (`zstd`, `smaz2`, `base64`, `encrypt`)
  - Clickable to add/remove
  - `+base64 auto` indicator when auto-added
- Encryption key: "Show encryption key" toggle
- `ON` toggle, `Delete` button, `Unbind` button

**`+ New Interface` button** at top

**Android status:** InterfacesScreen exists with 3 tabs (Interfaces/Channels/Health). Missing: Access Rules tab, Devices tab, Object Groups tab, Failover tab, per-interface transform pipeline editor, device binding, new interface creation, encryption key management.

---

### 6. Passes (`/passes`)

**Layout:** Controls bar + next pass card + signal chart + pass list

**Controls:**
- Constellation toggle: `Iridium` (pill buttons)
- Source dropdown: AUTO / GPS / Custom
- Location display: `● 52.1621, 4.5094 (4km)`
- Time window: `12h` / `24h` / `48h` / `72h`
- Min elevation presets: `Clear Sky 5°` / `Partial 20°` / `Urban 40°` / `Canyon 60°`
- Elevation slider (fine-tune, 0-90°)
- TLE age + `Refresh TLEs` button

**Next pass card:**
- `NEXT PASS` label (teal)
- Satellite name: `IRIDIUM 124`
- Duration: `14m`, Peak: `34deg`, Az: `256deg`
- Countdown clock: `20:41` (large, right-aligned)
- Date: `16 Mar UTC`

**SIGNAL VS PASSES chart (SVG, full width):**
- X axis: time (24h)
- Y axis: elevation (0-90°)
- **Pass arcs:** Purple/blue filled area charts showing satellite elevation over time
- **Signal bars:** Green/red bars overlaid at pass times (signal quality during pass)
- **Legend:** `⌃ Pass` / `● Signal` / `● GSS OK` / `● GSS Fail`
- Current time marker line
- Multiple overlapping passes visible as layered arcs

**Collapsible pass list:**
- `▸ 495 passes Next: IRIDIUM 124 at 20:41`
- Expandable to show all predicted passes

**Android status:** PassPredictorScreen exists with countdown + timeline. **Critically missing: the Signal vs Passes SVG chart** — this is the killer visualization. Also missing: GPS/Custom source selector, elevation slider, GSS correlation.

---

### 7. Topology (`/topology`)

**Layout:** Stats bar + force-directed graph + node details table

**Stats bar:** `NODES 8 / 15  LINKS 8  AVG SNR 1.9 dB`
**Legend:** `● Online  ○ Offline  ─── Good SNR  ── Medium  ── Poor`

**Force-directed graph (SVG):**
- Nodes as circles with names/IDs
- Links with thickness proportional to SNR quality
- Node colors: green (online, good SNR), gray (offline), amber (stale)
- SNR values on link labels
- Battery % shown near nodes
- Interactive: drag nodes, zoom, pan

**Node Details table (below graph):**

| Status | ID | Name | SNR | Battery | Last Seen |
|--------|----|----|-----|---------|-----------|
| ● | 7630e3d3 | - | 6.5 | - | 4d ago |
| ● | 70833f60 | lzrd_echo | 0.0 | 101% | 19m ago |
| ● | db67d078 | Meshtastic Rock | -19.8 | - | 9d ago |

**Android status:** TopologyScreen has force-directed graph + node list. Missing: stats bar (node/link/SNR averages), link quality lines (thickness by SNR), SNR column in node table, sortable/filterable table.

---

### 8. Map (`/map`)

**Layout:** Map + controls + layer toggles + node filters + geofence panel

**Controls (top-right):** `Auto: OFF` | `Light` | `Fullscreen` | `Refresh`

**LAYERS panel:**
- Checkbox toggles: `☑ GPS` `☑ Custom` `☑ Iridium` `☑ Cellular` | `☑ Messages` `☑ Tracks`
- Quality legend: `● Good  ● Fair  ● Bad`

**NODE FILTERS panel:**
- Per-node checkbox toggles with status dots (green/amber/red)
- `Show all` / `Hide all` buttons
- 8+ nodes listed by ID

**GEOFENCE ZONES panel:**
- Zone count: `0 zones configured`
- `Manage` link → geofence editor

**Footer:** `8 nodes with GPS`

**Android status:** MapScreen shows nodes + phone GPS + track lines. Missing: layer toggles, node filters (show/hide per node), auto-refresh toggle, light/fullscreen buttons, message markers, quality legend, geofence zone panel.

---

### 9. Settings (`/settings`)

**Layout:** Config section tabs + JSON viewer/editor

**Tabs:** Radio | Channels | Position | Canned Msg | MQTT | Device MQTT | Iridium | Cellular | ZigBee | S&F | Range Test | Dead Man | Export/Import | About

**Per tab:** Shows raw JSON config fetched from radio, with:
- Source dropdown (e.g., `LoRa Radio`, `Device`, `Position`, `Power`, `Bluetooth`)
- `Refresh` button
- `Edit JSON` button → inline JSON editor

**Android status:** RadioConfigScreen has LoRa region/preset/TX power. Missing: most config sections (Channels, Position, MQTT, Device MQTT, Iridium, Cellular, ZigBee, S&F, Range Test, Export/Import). Missing JSON viewer/editor.

---

### 10. Audit (`/audit`)

**Layout:** Filter bar + event list

**Filter bar:**
- Interface filter buttons
- Event type filter
- `Verify Chain` button (green) + `Export` button

**Event list (full-page scrollable):**
- Per event row: timestamp, event type, interface, direction, detail, hash snippet
- Color-coded by type
- Very long list (100+ entries visible)

**Android status:** AuditScreen exists. Matches well.

---

## Summary: What Android Needs to Clone the Pi GUI

### High Priority (functional parity)

1. **Dashboard overhaul** — Transform from basic status to full card grid matching Pi:
   - Signal history charts (6h sparklines per transport)
   - Message queue summary card
   - Activity log feed
   - Location card
   - Burst queue card

2. **Pass Predictor chart** — The SVG "Signal vs Passes" elevation arc chart with signal overlay is the signature feature. Needs Compose Canvas or WebView SVG implementation.

3. **Comms tabs** — Add SBD Queue, Broadcasts, Webhooks tabs + stats row + compose modal with canned presets

4. **Peers screen** — Dedicated sortable node table (Status, ID, Name, SNR, Battery, Last Seen)

5. **Map layer controls** — Layer toggles (GPS/Custom/Iridium/Cellular/Messages/Tracks), per-node filter checkboxes, quality legend

6. **Interfaces full CRUD** — Transform pipeline editor (add/remove transform pills), device binding, new interface creation, access rules tab, object groups, failover groups

### Medium Priority (visual parity)

7. **Live status bar** — Transport indicators in top bar or persistent header (IRD signal, MESH SNR+nodes, CELL status, GPS fix, UTC clock)

8. **Topology enhancements** — Stats bar, SNR-weighted link lines, sortable node table

9. **Settings config sections** — All 15 config tabs from Pi (Channels, Position, MQTT, etc.) with JSON view/edit

### Low Priority (nice-to-have)

10. **Help screen** — Documentation/usage guide
11. **Watermark logo** — Subtle MeshSat background watermark (matching Pi dashboard)
12. **Bridge interface cards** — Status card row at top of rules screen
