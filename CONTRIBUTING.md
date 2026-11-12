# Contributing to openRS_

Thanks for your interest in contributing! This project gives Focus RS MK3 owners the telemetry dashboard Ford never made.

## Where to Start

| Area | Guide |
|------|-------|
| **Android app** | [`android/CONTRIBUTING.md`](android/CONTRIBUTING.md) — build setup, architecture, adding PIDs/CAN signals, PR checklist |
| **Firmware** | [`firmware/README.md`](firmware/README.md) — build instructions, directory structure, CAN frame reference |
| **Documentation** | PRs against any `.md` file welcome — branch from `main`, PR to `main` (never route docs through `staging`). See [docs source of truth](README.md) |

## Quick Links

- **Bug reports / feature requests:** [GitHub Issues](https://github.com/klexical/openRS_/issues)
- **PID reference:** [`android/docs/pid-reference.md`](android/docs/pid-reference.md)
- **Hardware setup:** [`android/docs/hardware-setup.md`](android/docs/hardware-setup.md)
- **Firmware flashing:** [`android/docs/firmware-update.md`](android/docs/firmware-update.md)

## Pull Requests

Pull requests are welcome, from people and from people working with an LLM.

**Before you start**

- Anything beyond a typo starts from an [issue](https://github.com/klexical/openRS_/issues): open one, or comment on the one you want to take, so the approach is agreed before you write code. Issues labelled [`good first issue`](https://github.com/klexical/openRS_/issues?q=is%3Aissue+is%3Aopen+label%3A%22good+first+issue%22) are fully specified and need no car or adapter.
- Code changes target `staging`; documentation targets `main` (see the table above).

**What a pull request needs**

- A link to its issue.
- Tests for the change. For a bug, a test that fails without your fix and passes with it.
- An honest statement of what you tested and on what: a car, an adapter on the bench, an emulator, or unit tests only. "Not tested on a car" is a fine answer; a guess presented as a measurement is not.
- If an LLM helped write it, say so. That is welcome and does not count against the change.
- A changelog entry in `android/CHANGELOG.md` for an app change, in the format used there. We add "Thanks @you." to it.

**How review works**

- We aim to give a first response within seven days.
- CI does not start by itself on a pull request from a fork: a maintainer approves each run after looking at the diff. CI on your pull request is the test run of record.
- The change is read in full and checked against the issue. Review comments ask for changes; if we hear nothing for 14 days, we may finish the change ourselves, credit you in the changelog, and close the pull request with thanks.
- Pull requests are squash-merged: one commit, with you as its author. The commit carries the e-mail address you committed with; use your GitHub no-reply address (`ID+username@users.noreply.github.com`) if you prefer to keep yours private.

**Timing**

- A change to the Android app merges between releases, never while a release is being prepared, so an approved pull request can wait a few days.
- A change to the adapter firmware, or to the app's drive-mode and ESC commands, can affect a car. It merges only after the maintainer has run it on their own car or adapter, however good the review is.

## PID Research

We especially need help verifying:

- **12V battery voltage** — CAN ID 0x3C0 does not broadcast; needs alternative source
- **Tire temperature PIDs** (0x2823–0x2826) — currently experimental
- **Brake pressure calibration** — CAN ID 0x252 raw ADC 0–4095, need known-pressure reference
- **MS-CAN parameters** — accessible at 125 kbps, requires second adapter
- **Additional BCM/IPC PIDs**

If you have a Focus RS with FORScan or an OBDLink MX+, your PID dumps are incredibly valuable.

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
