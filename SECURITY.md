# Security

openRS_ talks to a car. The app reads the CAN bus through an adapter, and with the openrs-fw adapter firmware it can ask
the adapter to send the same messages as the car's own buttons (drive mode, ESC, Auto Start-Stop, Launch Control). A
weakness here can matter more than in an ordinary app, so please report one privately.

## Reporting

Use [a private security advisory](https://github.com/klexical/openRS_/security/advisories/new) on this repository. Please
do not open a public issue or discussion for a weakness that could let someone else read from or send to a car or an
adapter.

Include what you found, how to reproduce it, the app version and build, the adapter and its firmware, and whether it
needs physical access, the adapter's Wi-Fi network, or Bluetooth range. This is a one-maintainer project: expect a first
reply within a week.

## What is in scope

- The Android app, the openrs-fw adapter firmware in `firmware/`, and Sapphire in `web/`.
- The release pipeline in `.github/workflows/`.

The MeatPi adapter hardware and its stock firmware belong to MeatPi; report those to the vendor.

## Things to know

- An adapter creates its own Wi-Fi network with a default password, and openrs-fw's command interface is reachable by
  anyone on that network. Change the adapter's Wi-Fi password in its web page, and unplug the adapter when the car is
  left with someone else.
- Supported builds are the newest stable release and the newest pre-release. Fixes are not back-ported to older ones.
