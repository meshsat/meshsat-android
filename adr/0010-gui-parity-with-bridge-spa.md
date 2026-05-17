# ADR-0010 — GUI parity with Bridge SPA: Playwright-captured spec drives Compose implementation (Phases M-P)

* Status: Accepted — codified after the fact 2026-05-17. Phases M-P completed against `GUI-PARITY-SPEC.md` (MESHSAT-72/73/74/75); 16-gap Bridge feature parity closed in v1.5.0/v1.5.1 (MESHSAT-385).
* Date: 2026-03-16 (spec captured via Playwright on live Pi5 at `nllei01mule01-wireless:6050`); Phases M-P shipped 2026-Q1; ADR recorded 2026-05-17.
* Deciders: `ufwtqkgz@meshsat.net`
* Source: `GUI-PARITY-SPEC.md` (full document, 313 lines)

## Context

After Phases A-G (backend parity) and H-L (initial UI), the Android UI had functional coverage but visual + interaction divergence from the Bridge's Vue SPA. Operators using both surfaces had to context-switch.

The default response — "iterate the Android UI against operator feedback" — produces drift forever: every Bridge UI change requires a re-pass on Android with operator review.

A faster, more honest path: **capture the Bridge's UI as a literal spec**, then implement against the spec. The Playwright-driven capture of the live Pi5 dashboard on 2026-03-16 produced `GUI-PARITY-SPEC.md` — a screen-by-screen, card-by-card, control-by-control inventory of what Android needs to look like + behave like.

## Decision

**`GUI-PARITY-SPEC.md` is the operator-authoritative parity contract.** It enumerates 10 Bridge screens (Dashboard / Comms / Peers / Bridge / Interfaces / Passes / Topology / Map / Settings / Audit) plus the live status bar. Per screen, it lists:

- The Bridge implementation (layout, cards, controls, behavior).
- The Android status (what exists, what's missing).
- The gap (concrete items to implement).

Phases M-P close the gap:

| Phase | Issue | Goal | Status |
|---|---|---|---|
| M | MESHSAT-72 | Dashboard overhaul — sparklines, SOS, location, queue, burst, activity log | Done |
| N | MESHSAT-73 | Pass predictor chart — Compose Canvas bezier elevation arcs (replaces Bridge's SVG) | Done |
| O | MESHSAT-74 | Comms + Peers + Map — message tabs, peers table, map layer toggles + node filters | Done |
| P | MESHSAT-75 | Status bar + topology + interfaces — live status bar, topology stats/SNR, 3 new interface tabs | Done |

Where the Bridge uses SVG (Pass Predictor's elevation arcs, Topology's force-directed graph), Android implements with **Compose Canvas + native rendering**. Where the Bridge uses Vue components (cards, sparklines, filters), Android implements with **Compose Material 3 widgets**.

**The shared semantic model is the contract.** A "node filter chip" that's a Vue toggle on Bridge is a Compose `FilterChip` on Android — same operator concept, language-idiomatic rendering.

## Consequences

**Positive**
- Operators get **isomorphic mental models** across Bridge and Android — once they learn the Bridge dashboard, they know the Android dashboard.
- Future Bridge UI changes become explicit Android tickets: a new Bridge widget triggers a `GUI-PARITY-SPEC.md` update + a corresponding Android Compose implementation. Drift is visible, not invisible.
- `GUI-PARITY-SPEC.md` is a self-documenting test contract — operator can read it and know what each screen should do.
- The 16-gap Bridge feature parity closure (MESHSAT-385) was scoped against this same spec — cross-platform feature parity is a single coherent metric, not a per-screen guessing game.

**Negative**
- The Playwright capture is a **2026-03-16 snapshot** — it ages. Real-time accuracy requires periodic recapture against the live Pi5 dashboard. Mitigation: re-run Playwright on each Bridge minor release; update spec; identify new Android gaps.
- "Same mental model, language-idiomatic rendering" is a judgment call — operator (or contributor) might disagree on what constitutes equivalence (e.g., "your bezier-curve elevation arc doesn't *feel* like the Bridge SVG"). Mitigation: visual side-by-side comparison on Pixel 9a + a desktop browser; operator final-call.
- Compose Canvas rendering for complex visualizations (force-directed graph) is more code than Vue + a JavaScript graph lib. Maintenance burden.
- Bridge's `UX-AUDIT-AND-REDESIGN.md` (`meshsat/UX-AUDIT-AND-REDESIGN.md`) proposes a major UI reshape (Operator/Engineer modes, 5-item nav, Compose/Inbox/People/Map/Radios). When that lands, the parity spec needs a major recapture — Android Phases will be regenerated against the new Bridge SPA.

**Forward direction**
- When Bridge Phase 3 (UI reshape) lands per `meshsat/EXECUTION-PLAN.md` §6.3, recapture parity spec against the new 5-item nav + Operator/Engineer modes. Implement on Android in a coordinated Phase Q+.
- The hybrid Compose + WebView shell (ADR-0007) reduces the GUI-parity burden for Settings/Engineer surfaces — those become "wrap the Bridge SPA" rather than "reimplement in Compose." Daily-use surfaces (Compose/Inbox/Map/People) remain native Compose under this ADR.
- A Compose UI test suite that screenshots key screens and diffs them against `GUI-PARITY-SPEC.md` images could automate parity-drift detection. Not yet in scope.

## Alternatives considered

- **Wrap Bridge SPA in a single WebView (no Compose)**: rejected for daily-use surfaces — loses native feel + battery cost; the hybrid path (ADR-0007) is the right compromise.
- **Independent Android UX (no parity contract)**: rejected — guarantees drift; operator context-switch cost grows over time.
- **React Native shell rendering Vue components**: rejected — adds React Native to a Compose-native codebase; toolchain pollution; doesn't help when Vue components depend on Vue ecosystem APIs.
- **No spec, just iterate against operator feedback**: rejected — iterative drift; never converges.

## Compliance

- New screens on either Bridge or Android MUST be added to `GUI-PARITY-SPEC.md` within the same release that ships them.
- The Bridge SPA is the source-of-truth for **operator concepts** (what filters exist, what the activity log shows, what cards live on the dashboard). Android implementations may deviate on **rendering** (Compose Canvas instead of SVG; Material 3 FilterChip instead of Vue toggle) but NOT on **semantics**.
- When `meshsat/UX-AUDIT-AND-REDESIGN.md` Phase 3 ships, recapture parity spec via Playwright; open new Phase issues against the diff.
- The hybrid Compose + WebView pair shell (ADR-0007) reduces this ADR's scope to native-rendered hot paths — Settings/Interfaces/Rules/etc surfaces are out of scope for `GUI-PARITY-SPEC.md` post-Phase-8.
- A parity-drift detection mechanism (screenshot diff, contributor checklist, periodic Playwright recapture) is a future improvement worth tracking.
