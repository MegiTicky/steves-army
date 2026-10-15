# Steve's Army Mod Source

This is the nested mod repository. Before editing or building here, read the
**parent workspace's** real-time coordination protocol — other agents may be
working in the same tree at the same time:

- `../agents-mailbox.md` — the protocol (locks, heartbeats, point-of-action
  git rules) + append-only activity log
- `../agents-lock.sh` — the lock tool. Hold `build` for ANY Gradle command
  (`./../agents-lock.sh await build "<you>: <task>"`), `deploy-test` before jar
  deploys, and a named lock before editing shared files. Never proceed on BUSY.

Mod-specific build/run instructions: `../AGENTS.md`. Architecture:
`ARCHITECTURE.md`. Sim harness: `SIM_HARNESS.md`.

## Soldier telemetry analysis

For reported stuck soldiers, use the flight-recorder bundles described in
`SOLDIER_TRACE.md` (`debug/soldier_traces/<unique>/`). Ask for the complete
export folder so all evidence remains available, but analyze it locally with
scripts before bringing excerpts into the conversation:

- Read `summary.txt` and inspect metadata, the selected actor, capture coverage,
  truncation, and findings first. Findings are leads, not proven causes.
- Parse `trace.json` programmatically. Extract compact state/goal transitions,
  movement failures, target/path changes, and stationary intervals for the
  selected actor; narrow to suspicious time windows and relevant nearby actors.
- Read only the detailed frames/events needed to explain those windows. Use
  `sequence` for event ordering, including within one tick, and `timeMs` for
  elapsed time. Account for coalesced event repeat counts.
- Decode only relevant terrain sections/path geometry when reconstructing a
  case. Avoid dumping the complete JSON, terrain indices, or embedded JSON in
  `viewer.html` into tool output or model context; those duplicate or bulky data
  can consume substantial input tokens without helping the diagnosis.
- Preserve the original bundle and cite actor IDs, ticks/timestamps, and event
  evidence in conclusions. Follow `SIM_HARNESS.md` for a manual simulator
  reproduction; export-time terrain and missing runtime state prevent assuming
  deterministic replay.

## Commit cadence

Commit small, coherent, verified changes frequently: after each completed fix or
meaningful milestone, and before handing work off. Do not accumulate unrelated
uncommitted work. Stage only your claimed files or hunks, preserving other agents'
changes. Follow explicit user instructions to defer commits; committing does not
authorize pushing or deploying.
