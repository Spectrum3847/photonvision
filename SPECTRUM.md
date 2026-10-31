# Spectrum's PhotonVision

FRC 3847 Spectrum's fork of [PhotonVision](https://github.com/PhotonVision/photonvision), for our
Jetson Orin Nano vision coprocessors ([SpectrumJetson](https://github.com/Spectrum3847/SpectrumJetson))
and, later, a redundant camera on the SystemCore. GPL-3.0, like PhotonVision.

Until 2026 our changes lived in SpectrumJetson as 88 patch files (`patches/photonvision-*.patch`),
applied in order to the FRC 4143 CUDA fork at every build. This branch replaces them: each feature is
a commit on top of upstream, rebased when upstream moves. SpectrumJetson pins a commit (or release) of
this repo instead of carrying patches. The last patch-based build is SpectrumJetson release
v2026.10.30.2 (patches 00-87); it stays the reference for what each feature does.

## Branches

| Branch | What |
| --- | --- |
| `main` | Upstream's `main`, untouched (the fork's default; keep it in sync with upstream) |
| `spectrum-2027` | Our work: upstream `main` plus our feature commits, on WPILib 2027 alpha-8 |

Started 2026-10-30 from upstream `main` at `14cc2298` ("reject negative focal length (#2662)"),
88 commits past `v2027.0.0-alpha-2`.

Remotes: `origin` this fork, `upstream` PhotonVision/photonvision, `frc4143` FRC-Team-4143/photonvision
(the CUDA AprilTag pipeline, `d8c9e8e`).

## Robot code moves with it

Upstream `main` is on **WPILib 2027 alpha-7**, and its PhotonLib messages changed from alpha-2 (the
serde hashes in `photon-serde/`, see SpectrumJetson `docs/UPSTREAM-PORT.md`). A jar from this branch
only talks to robot code with a matching PhotonLib: robot code moves to WPILib alpha-7 and this
PhotonLib in the same step as the first Jetson running this branch. Until then, Jetsons stay on
SpectrumJetson v2026.10.30.2.

**WPILib alpha-8** (released 2026-10-30): upstream's PR #2663 only bumps versions (`wpilibVersion`,
OpenCV `2027-4.13.0-4`), no source or message changes. This branch carries the same bump as its own
commit ("WPILib 2027 alpha-8 (same as upstream #2663)"), so the next rebase drops it once #2663
merges. Robot code is on alpha-8 too: build its PhotonLib from this branch.

## PhotonLib stays upstream's

Robot code should be able to use upstream's PhotonLib, unchanged. So:

- **`photon-lib`, `photon-targeting` and `photon-serde` stay identical to upstream** at the commit this
  branch is based on: our changes are all in PhotonVision itself (the Jetson side). Check before
  pushing: `git diff --quiet $(git merge-base HEAD upstream/main) HEAD -- photon-lib photon-targeting photon-serde`
  (exit 0 = identical).
- **Our own things go next to PhotonLib, not into it**: PhotonVision publishes them on NetworkTables
  topics of their own (`/photonvision/jetson/...`, each camera's `health/...`, Rewind's, the field
  map and clock topics), and robot code reads them with SpectrumVision (SpectrumJetson's
  `robot-vision/`), beside PhotonLib. The message format PhotonLib reads is never changed.
- **If PhotonLib really has to change**, we carry the change on this branch, and robot code uses the PhotonLib built from it.
- **Until upstream publishes a PhotonLib for the WPILib we're on**, we build it from this branch,
  which is upstream's PhotonLib code, and switch robot code to upstream's vendordep as soon as
  there is one built from the same `photon-serde` (the message hashes must match the Jetson's).
  2026-10-30: WPILib alpha-8 is out, and upstream hasn't published an alpha-8 PhotonLib yet
  (#2663 is open), so robot code uses ours for now.

## Status

| Feature | State |
| --- | --- |
| WPILib alpha-8 | **Done**, builds (2026-10-30) |
| 1 CUDA AprilTag pipeline | **Ported, builds** on alpha-7 and alpha-8 (2026-10-30). Not yet run on a Jetson: needs the detector rebuilt for WPILib 2027 (below) |
| 6 Thriftiest Cam | **Ported, builds, unit tests pass** (2026-10-30): serial identity (86), firmware card (87). 86's USB-port guard comes with feature 5 |
| 11 Field map from the robot (74) | **Ported, builds, unit tests pass** (2026-10-30), WPILib 2027 Field JSON (below). Rest of feature 11 not started |
| 2-5, 7-10, 12-14 | Not started |

**The CUDA detector must be rebuilt against WPILib 2027 before feature 1 can detect a tag.**
`lib971apriltag.so` (SpectrumJetson `detector/`) creates the Java `AprilTagDetection` objects it
returns by class name, and WPILib 2027 moved that class from `edu/wpi/first/apriltag` to
`org/wpilib/vision/apriltag` (same constructor, `(String, int, int, float, double[], double, double,
double[])`). It also links WPILib's C++ libraries (`libwpiutil` for its JNI helpers, `RawFrame`,
timestamps), built from allwpilib 2026 and installed in `/usr/local/lib`; a 2027 PhotonVision loads
WPILib 2027's libraries of the same names into the same process, so the detector would bind to
libraries it wasn't built against. So: build it against WPILib 2027's C++ (with 2027's namespace and
header moves), and look the class up by its 2027 name, falling back to the 2026 one so a single
library serves both during the switch. That needs a Jetson set up from source (cmake and allwpilib);
this is the `bos`/`gpudetector` fork step, moved ahead of feature 2.

## Settings from a 2026 Jetson

Upstream's migration (`DbMigration`, versions 202701-202704) moves a 2026 settings database to
2027's layout, and `SpectrumDbMigrationTest` checks it on a real robot's database (skipped unless
`SPECTRUM_MIGRATION_DB` names one). 2026-10-30, the robot's database from before the camera serials
(SpectrumJetson laptop `~/pv-backups/photon-20261029-before-serials.sqlite`): both cameras, all 3
calibrations (8 coefficients) and both `AprilTagCuda` pipelines load. Everything else in the
pipelines survives a save except 5 settings, which reset to defaults the first time anything is
saved: `cameraContrast`, `cameraGamma`, `cameraSharpness`, `cameraBacklightCompensation` (feature 4)
and `detectionMask` (feature 11). So those two features land before a Jetson boots this branch on
its 2026 settings. Rerun the test on a current database (with patch 86's serial identity) before the
switch.

## Field map: WPILib 2027 Field JSON

Robot code (2026-FM-SystemCore `upgrade/alpha-8`, `61baeeb`) sends `/SpectrumVision/FieldMap/Json` as
WPILib 2027 Field JSON (`field-tags`, `field-dimensions`, plus `name`, `season`, `game`,
`program`); `FieldMapSync` reads it with WPILib's own parser (as upstream's
`/photonvision/field_layout` does) and still reads the 2026 form (`tags`, `field`), which is what a
2026 Jetson saved in `spectrum/field-map.json`. The hash text is unchanged and both forms hash the
same (2026 AndyMark `2792cd4e868967b8`, Welded `ca28a7c1139ed099`, as robot code's tests).

The sign of zero is part of the hash, and four 2026 tags have a `-0.0` quaternion X. WPILib's Field
JSON writer, and avaje's JSON nodes, write `-0.0` as `0`. So the robot's JSON text is parsed as
sent, never re-written first, and kept as sent for the next boot; a map PhotonVision re-wrote (its
settings database, an export) can hash differently from the robot's, and is then just reloaded from
the robot.

## Already upstream (patches that go away)

Checked 2026-10-30 against upstream `main` (`git log upstream/main --grep "(#N)"`):

| Patch | Upstream | Status |
| --- | --- | --- |
| 00 upstream v2026.3.4 | the merge itself | gone: `main` is newer |
| 11 `setEnabled` | #2484, #2499 | in `main` |
| 12 OpenCV leaks | #2511, #2356 | in `main`; our two extra leaks still to check |
| 21 Thriftiest Cam support | #2478 | in `main`; our addition (saved cameras get the new quirks) still to port |
| 23 calibration | #2437, #2479, #2480 | in `main`; #2149 (Auto Snapshots) still ours |
| 42 video format undefined | #2617 | in `main` |
| 62 2027 control word | #2566 (WPILib alpha-7) | check: may be covered |
| others | #2338, #2344, #2364, #2368, #2398, #2407, #2411, #2412, #2429, #2493, #2528, #2574 | in `main` |

Not in `main`: #2138, #2149, #2263 (skipped on purpose), #2351, #2352 (our 44), #2443, #2477 (our 22
is our own design), #2586, #2613, #2614, #2628, #2629, #2633. Recheck each when its feature is ported.

## Feature commits, in porting order

Each line becomes one commit (or a few, if a feature is big), ported from the patches listed, rebuilt,
and tested before the next. **Jetson** = only does anything on a Jetson; it must switch itself off
where its hardware or library isn't there, so the same jar runs on the SystemCore.

We don't send changes upstream as PRs: everything here stays ours. Once it's all working we may
tell PhotonVision what we've done, but not one change at a time.

| # | Feature | From patches | Jetson |
| --- | --- | --- | --- |
| 1 | **CUDA AprilTag pipeline**: the `AprilTagCuda` pipeline, its UI tab, the detector JNI (FRC 4143: 17 commits, 20 files) | 4143 `d8c9e8e`, 01 | yes |
| 2 | **Capture and decode**: gray capture, direct MJPEG→gray decode, decode recovery, shared-gray video mode, mid-exposure timestamps | 02, 09, 13, 27, 37 | no (CPU; a big win on the SystemCore) |
| 3 | **Hardware JPEG decode** (NVJPG) | 18 | yes |
| 4 | **Camera controls**: 8 distortion coefficients, team defaults, exposure in ms, extra sliders, gain, focus score, yaw sign, duration format | 03, 04, 06, 10, 28, 34, 36 (done: 2027 still computes yaw positive right, so the doc fix and Targets-tab labels are ported; PhotonTrackedTarget's comments left as upstream's), 39, 54 | no |
| 5 | **Camera reliability**: stuck-camera watchdog and real reopen, USB bandwidth and its page, quiet frame errors, startup and pipeline races, duplicate activation, dead camera, USB port-power recovery | 29, 31, 32, 33, 38, 43, 44, 66, 81, 84 | no (port power needs a hub that switches it) |
| 6 | **Thriftiest Cam**: quirks for saved cameras, serial identity, firmware card | 21 (our part), 86, 87 | no |
| 7 | **Rewind** and its vision log | 07, 75, 76, 77, 78, 79, 65 (Rewind timers) | no |
| 8 | **Robot clock, diagnostics, logging**: robot clock, clock marks and source, log mirror to the robot, NT diagnostics | 08, 80, 82, 83, 85 | 08 no (off on the SystemCore: it *is* the robot clock); 82 partly |
| 9 | **Jetson telemetry**: power, throttling, GPU chart, Device card | 16, 19, 20, 72 | yes |
| 10 | **Robot state**: idle while disabled, idle switch, event profile, quiet mode, thermal limit, cooling | 49, 50, 51, 56, 57, 62?, 64, 65, 71 | 56, 57, 71 yes |
| 11 | **Field and pose tools**: excluded tags, field calibration, detection mask, tag quality, field map from robot, mount estimate, calibration at low fps, Auto Snapshots | 17, 22, 23 (#2149 part), 30, 40, 61, 70, 74 | no |
| 12 | **Far-tag search** | 55, 69 | yes (CUDA) |
| 13 | **Operations UI**: Match Ready, settings snapshots, settings JSON, copy settings, tuning guide, theme, radio switches, stream-only-when-watched, setting broadcasts, switch-all, test hooks, UI state, dashboard guards | 05, 15, 24, 25, 26, 35, 41, 45, 46, 47, 48, 52, 53, 58, 59, 60, 67, 68, 73 | 52 (via health-check) |
| 14 | **TensorRT object detection** | 14 | yes |
| - | **Audit fixes**: split into the features above as they're ported | 63 | |

The CUDA detector (`bos-*`, `gpudetector-*` in SpectrumJetson) is a separate library and moves to its
own fork of frc971/bos later; until then SpectrumJetson keeps building it.

## Later

- **Quick centre-only calibration** (2026-10-30): a camera model's generic calibration gets fx, fy and
  distortion about right, but the image centre varies per unit (two Thriftiest Cams: cy 418 vs 370,
  up to ~2 deg of pitch). Refit only cx, cy from 10-15 board snapshots, keeping the model's other
  values: minutes per camera instead of a full calibration.

## How to port a feature

1. Read the feature's patches in SpectrumJetson (and its docs: `README.md`, `docs/TECHNICAL.md`).
   The 2026 tree with every patch applied is the reference for the end state:
   `SpectrumJetson/scripts/host/03-build-photonvision-fork.sh` builds it.
2. Port onto `spectrum-2027`: `git apply --3way` where the files still match, by hand where upstream
   moved the code. Keep the `SpectrumJetson (photonvision-NN)` comments, so a reader can trace a change
   back to its patch, its reasons, and its tests.
3. Build (`./gradlew photon-targeting:jar photon-server:shadowJar -PArchOverride=linuxarm64`), run the
   unit tests the patches added, and the matching SpectrumJetson tests on a Jetson.
4. One commit per feature, message `Spectrum: <feature> (photonvision-NN, ...)`.
5. Check whether `main` gained something equivalent first: then use upstream's.

## Keeping up with upstream

```bash
git fetch upstream
git switch main && git merge --ff-only upstream/main && git push origin main
git switch spectrum-2027 && git rebase upstream/main     # conflicts per feature commit
```

Rebase, don't merge: the feature commits stay a readable list on top of upstream. Retest after each
rebase, then bump the pin in SpectrumJetson.
