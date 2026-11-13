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

### Releases

**Stable**:
A release for everyone, named by its version alone (2.2.7). Within one version it comes after every release candidate
and every nightly.
_Avoid_: Production, final; "Stable" alone where the Stable channel could be meant

**Release candidate**:
A test release of the next stable, named by its number (2.2.7-rc.8). rc.8 is the last one: nightlies take their place.
_Avoid_: RC build, beta (for the release), preview

**Nightly**:
A test release built by itself at most once a day, on a day the app has changed, and named by its date in UTC
(2.2.7-nightly.20261201). Within one version it comes after every release candidate and before the stable.
_Avoid_: Daily build, snapshot, beta (for the release), debug build

**Stable channel**:
The update channel that offers stable releases only: never a release candidate or a nightly.
_Avoid_: "Stable" alone where the stable release could be meant

**Beta channel**:
The update channel that offers the newest release of any kind: a stable, a release candidate or a nightly.
_Avoid_: Test channel, nightly channel, pre-release channel
