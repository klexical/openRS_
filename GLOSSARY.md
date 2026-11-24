# openRS_

The language of openRS_, a telemetry app for the Ford Focus RS: what the app records while the car is driven, how that
record is named, and how the app's releases are named.

## Language

### Drives

**Drive**:
One record of the car being driven, from START to STOP. A drive may span several connections to the adapter.
_Avoid_: Session, recording (as a noun), trip, run

**Open**:
A drive that has started and is not yet stopped. An open drive is either recording or paused.
_Avoid_: Active, running, in progress, "recording" for a paused drive

**Session**:
The diagnostic log of one connection to the adapter. A drive can touch several sessions; a session is never a drive.
_Avoid_: Drive, connection log

**Recording**:
The phase of a drive in which points are being stored.
_Avoid_: Using "a recording" to mean a drive

**Paused**:
The phase of a drive in which no points are stored and the drive is still open. A pause is either a hand pause or a
link pause.
_Avoid_: Stopped, suspended

**Hand pause**:
A pause the user asked for. Only the user's RESUME or STOP ends it. A link pause becomes a hand pause when auto-record is
switched off while it is open.
_Avoid_: Manual pause, user pause

**Link pause**:
A pause the app applied because the connection to the adapter was lost while auto-record is on, however the drive was
started. It ends when the connection is back in time and the engine is running, or when the user resumes or stops the
drive; a link pause that has waited too long ends with the drive stopped at the moment the connection was lost. The
connection counts as lost in two cases: the app closes it, or an established connection drops (the adapter stops
answering) and the app's normal reconnect attempts fail. A shorter loss is a recording gap. A drop counts once:
attempts that go on failing, or that fail before any connection was established, are not a new loss.
_Avoid_: Auto-pause, disconnect pause

**Auto-record**:
The setting that lets the app act on a drive by itself: start one when a new connection sees the engine running and no
drive is open, and apply a link pause when the connection is lost. With it off, only the user starts and pauses drives.
_Avoid_: Auto-start, auto-drive

**Recording gap**:
A stretch inside a drive that is recording but has no adapter data, because the connection was down. It is not a pause.
_Avoid_: Link gap, dropout, pause

**Reception stall**:
A gap on an open connection that ends with a burst of queued frames: the frames were held up on the way. The connection
stays up, so it is not a recording gap.
_Avoid_: Dropout, freeze, link loss, lag

**Quiet span**:
A stretch of an open connection in which few frames arrive, or none, that does not end with a burst of queued frames:
the rate returns, the connection ends, or it lasts so long that the app stops waiting for either. Its cause is not told
apart: a quiet bus and lost reception look the same.
_Avoid_: Stall, reception loss, dropout

**Cancelled start**:
A START that was stopped before the drive began. It is not a drive and never appears in history.
_Avoid_: Aborted drive, empty drive

**Stopped**:
A drive is stopped at the moment of the STOP command: nothing more is added to it.
_Avoid_: Ended, finished, finalized

**Saved**:
A stopped drive is saved once its record, laps and summary are stored. The only word the app and its exports use for a
completed drive.
_Avoid_: Finalized, written, persisted

**Interrupted drive**:
A drive whose app ended before STOP. It is closed at the next launch with only what its stored points support.
_Avoid_: Orphan, crashed drive

### Laps

**Start/finish line**:
The line the user sets for lap timing. It belongs to the recorder, not to a drive: it stays until cleared or replaced.
_Avoid_: S/F point, gate

**Lap**:
One timed pass from the start/finish line back to it. A lap belongs to the drive it was timed in.
_Avoid_: Sector, split

### Connection

**Adapter network**:
A Wi-Fi network on which the adapter gave its own protocol reply.
_Avoid_: Eligible network, the adapter's SSID

**Request capture**:
A switch for one connection: while it is on, the app sends the PCM its next request only after the previous one is
answered or a set time has passed, so each answer's delay can be read from the session's raw log. Every connection
starts with it off.
_Avoid_: Capture mode, debug mode, slow polling

**Live telemetry**:
What the app currently knows about the car on the current connection: the latest readings, and the running figures
worked out from them (fuel economy, knock count, boost sweep, performance timer, thermal readiness). Every new connection
starts it again, however the connection began: each reading is "not received" until that connection reads it, and the
running figures restart; thermal readiness starts over whenever the link goes down, not at the new connection itself.
The user's reset does the same and also clears peaks and thermal readiness, without touching the connection. In demo
mode it holds simulated readings.
_Avoid_: Session (a session is the diagnostic log), telemetry session, live session

### Exports

**Diagnostic export**:
The ZIP made from the DIAG tab: the diagnostic record of the current connection, with the open drive folded in when one
is open. "Snapshot" is only the word on its button.
_Avoid_: Snapshot (for the file)

**Drive export**:
The ZIP made from TRIP for one or more saved drives.
_Avoid_: Snapshot

### Sapphire

**Import**:
One export file (a ZIP from the app) brought into Sapphire. An import can hold one or more drives, a session, or both;
what Sapphire keeps is the drives and the session, not the file.
_Avoid_: Upload (nothing leaves the browser), "session" for whatever was imported

**Place**:
A top-level destination in Sapphire: Drives, Car, Settings.
_Avoid_: Panel, tab, section

**Page**:
The screen of one drive, or of a session-only entry, in Sapphire.
_Avoid_: Panel, detail view, dashboard

**View**:
One tab inside a drive's page.
_Avoid_: Panel, page, place

**Not checked**:
What Sapphire says of a warning cause when the import did not hold what answers it. It is never shown or counted as
"none".
_Avoid_: Not checkable, unknown, n/a, clean

**Partial drive**:
A drive imported from an export made while the drive was still open. It ends where that export was made and is listed
as a drive of its own, apart from the finished drive.
_Avoid_: Snapshot, incomplete import, duplicate

**Drive library**:
The drives, and the session-only entries, Sapphire keeps in this browser. The Drives place lists it, an import adds to
it, and "clear all" empties it.
_Avoid_: Database, store, sessions

**Session-only entry**:
A row in the drive library for an import that held a session and no drive. It has a page but no route, charts or laps.
_Avoid_: Drive (a session is never a drive), empty drive

**Current drive**:
The drive, or session-only entry, whose page is on screen in Sapphire.
_Avoid_: Open drive (open means started and not yet stopped), active session, selected drive

**Compare**:
Showing two to four ticked drives side by side, started from the Drives list. An action, not a place.
_Avoid_: Compare panel, Compare page

**Base drive**:
The first drive ticked for Compare; the other drives' differences are measured against it.
_Avoid_: Reference, baseline

**Fill-in**:
What an import of a drive Sapphire already keeps does to the stored drive. It adds the profile, or the session log, only
when the stored drive has none. It adds the fault-code scan when the stored drive has none, or when its fault codes are
"not checked" and this import's scan answers them; an answer is never replaced or turned back into "not checked". The
drive stays one drive: its points, name, note and tags stay, and only the fields derived from them are worked out again.

**Palette**:
The set of colours Sapphire is drawn in: Match device (Night or Day, from the device's setting), Night, Day or Ultra.
_Avoid_: Auto, theme, mode, look

**Paint colour**:
The RS paint picked in Sapphire's Settings, which its accent comes from: on buttons, tabs and highlights only.
_Avoid_: Theme, palette

### Releases

**Stable**:
A release for everyone, named by its version alone (2.2.7). Within one version it comes after every release candidate
and every nightly.
_Avoid_: Production, final; "Stable" alone where the Stable channel could be meant

**Release candidate**:
A test release of the next stable, named by its number (2.2.7-rc.8).
_Avoid_: RC build, beta (for the release), preview

**Nightly**:
A test release built by itself at most once a day, on a day the app has changed, and named by its date in UTC
(2.2.7-nightly.20261201). Within one version it comes after every release candidate and before the stable.
_Avoid_: Daily build, snapshot, beta (for the release), debug build

**Dry run**:
A nightly run that builds, signs, checks and attests but publishes no release and makes no tag.

**Stable channel**:
The update channel that offers stable releases only: never a release candidate or a nightly.
_Avoid_: "Stable" alone where the stable release could be meant

**Beta channel**:
The update channel that offers the newest release of any kind: a stable, a release candidate or a nightly.
_Avoid_: Test channel, nightly channel, pre-release channel

**Launch check**:
A check that a nightly's APK installs and starts on an emulator and is still running, with no crash in the log, a set
time later. It does not show that any screen or reading works. A nightly is published only after its launch check has
passed. A launch check is repeated once in the same run only for a vanished emulator.
_Avoid_: Smoke test (a smoke test also looks at every tab), launch test

**Vanished emulator**:
The emulator's process is gone during the launch check while the app's own process had not changed.
_Avoid_: Emulator crash (the cause is not known), flaky emulator
