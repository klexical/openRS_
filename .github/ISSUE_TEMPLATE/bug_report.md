---
name: Bug report
about: Report a bug or unexpected behavior
title: ''
labels: bug
assignees: ''
---

## What happened
A clear description of the bug, and what you expected instead.

## Steps to reproduce
1. Connect to the adapter over ...
2. Open ...
3. See ...

## Setup
- **openRS_ version and build**: (Settings shows it, e.g. v2.2.7-rc.8 · Build 39)
- **Phone and Android version**: (e.g. Pixel 8 Pro, Android 15)
- **Adapter**: MeatPi USB (C3) / MeatPi Pro (S3)
- **Adapter firmware**: stock (version) / openrs-fw (version)
- **Connection**: Wi-Fi / Bluetooth LE
- **Car**: model year and market (e.g. 2017 Focus RS, Europe)
- **Seen on**: a car / an adapter on the bench / an emulator

## Diagnostic export
A diagnostic export helps most: the DIAG screen (GARAGE → DIAG in the release candidates) → export, then attach the ZIP.

**Before you attach it:** a drive export contains your route (GPS positions and times), and a diagnostic export contains
the car's raw CAN traffic and the app's logs. The v2.2.7 release candidates mask the VIN; v2.2.6 does not. Leave out
anything you do not want public.

If you cannot export, logcat output helps too:
```
adb logcat --pid=$(adb shell pidof com.openrs.dash)
```

## Screenshots
If they help.
