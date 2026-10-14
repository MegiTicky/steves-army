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

## Commit cadence

Commit small, coherent, verified changes frequently: after each completed fix or
meaningful milestone, and before handing work off. Do not accumulate unrelated
uncommitted work. Stage only your claimed files or hunks, preserving other agents'
changes. Follow explicit user instructions to defer commits; committing does not
authorize pushing or deploying.
