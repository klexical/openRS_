---
status: accepted
---

# Every new connection forgets every reading

A reading held from an earlier connection is not a reading of this one. Before this decision, only a RETRY tap blanked
tyre pressures, RDU / PTU temperatures, odometer, battery and cabin temperature; an automatic reconnect went on showing
the old values until they were polled again, up to about 30 seconds. We decided one rule for every new connection,
however it started (launch, Connect, RETRY, an automatic reconnect): each reading is "not received" until this
connection reads it, and the running figures restart.

The session knock log and the header's knock count are the exception among the running figures (the owner's decision):
they survive a connection the app starts by itself (an automatic start, the same job's reconnect after a drop), because
a short drop must not empty the knock record that the diagnostic export (`drive_knock_session_*.csv`) and PERF's session
scope show. A connection the user starts (Connect, RETRY) begins them fresh, as do the user's reset and demo mode.

Thermal readiness keeps its own rule and is not reset by a new connection: it starts over whenever the link goes down
(`ThermalReadinessEngine.evaluate` resets while `isConnected` is false, a rule older than this decision), so every
reconnect after a drop, an automatic one included, starts it again; only a new connection begun while the link stays up
(Connect tapped while linked) leaves its warm-up history in place. The user's reset (DIAG) forgets the same as a new
connection, and also clears peaks, the knock log and count, and thermal readiness, without touching the connection.

## Consequences

- After an automatic reconnect a user briefly sees "—" where an old value used to stay. This is a change a user notices,
  so it ships as its own change with a changelog entry, after the behaviour-preserving refactor of ADR 0001.
