---
status: accepted
---

# Every new connection forgets every reading

A reading held from an earlier connection is not a reading of this one. Before this decision, only a RETRY tap blanked
tyre pressures, RDU / PTU temperatures, odometer, battery and cabin temperature; an automatic reconnect went on showing
the old values until they were polled again, up to about 30 seconds. We decided one rule for every new connection,
however it started (launch, Connect, RETRY, an automatic reconnect): each reading is "not received" until this
connection reads it, and the running figures restart.

Thermal readiness keeps its own rule and is not reset by a new connection: it starts over whenever the link goes down
(`ThermalReadinessEngine.evaluate` resets while `isConnected` is false, a rule older than this decision), so every
reconnect after a drop, an automatic one included, starts it again; only a new connection begun while the link stays up
(Connect tapped while linked) leaves its warm-up history in place. The user's reset (DIAG) forgets the same as a new
connection, and also clears peaks and thermal readiness, without touching the connection.

## Consequences

- After an automatic reconnect a user briefly sees "—" where an old value used to stay. This is a change a user notices,
  so it ships as its own change with a changelog entry, after the behaviour-preserving refactor of ADR 0001.
