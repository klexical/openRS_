---
status: accepted
---

# Live telemetry is the only writer of the live readings

What a new connection forgets, and how the running figures (fuel economy, knock count, boost sweep, performance timer)
are fed, used to be written by hand in several places: the connection start, RETRY, the user's reset and demo mode,
each with its own list, and the lists disagreed. We decided that one module, **Live telemetry** (see `GLOSSARY.md`),
is the only code that writes the live readings and feeds or resets the running figures; every other module reads a
read-only view, and a guard test fails on any other writer.

The seam sits on the domain side of decoding: CAN decoding and its diagnostic frame log stay in the connection's frame
path, and Live telemetry receives the decoded change. The module never touches Room, GPS or the drive recorder: a
finished performance run is returned to the caller, which saves it (behind the demo guard), and a GPS fix is handed in
with the OBD reply. The wall and monotonic clocks are passed in, so the module is tested on the plain JVM.

## Considered options

- **Decoding inside the module.** Rejected: the frame log and decode validation belong to the diagnostic record of the
  connection, and would pull `DiagnosticLogger` across the seam.
- **Keep the per-path reset lists and add a test per path.** Rejected: that layers tests on the shallow callers instead
  of giving the rule one home; five Robolectric tests already checked one reset line each.

## Consequences

- Thermal readiness stays an app-level object with its own timer; Live telemetry tells it "new connection" and "user
  reset". The running-figure objects stay global for now, but only Live telemetry feeds or resets them. Owning them as
  instances inside the module is left for later, because every screen that reads them would change.
- Demo mode hands its simulated state to Live telemetry; its start, replay and exit are the same reset events. The
  demo rules in `.claude/rules/demo-mode.md` still bind (nothing simulated persists).
