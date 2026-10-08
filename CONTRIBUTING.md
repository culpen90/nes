# Contributing to Pocket NES

Pocket NES is a native Android NES emulator written in Java and C++, with a
vendored FCEUmm core. Contributions to the app, documentation, tests, and original
Star Garden demo are welcome.

## Report a bug or propose a feature

For bugs, include the steps to reproduce, expected and actual behavior, app
version, Android version, device model, and whether you used touch, a keyboard,
or a hardware controller. For emulation problems, include the cartridge format
and mapper if known, and say whether the included Star Garden demo also fails.
Attach relevant logs or screenshots after removing private information.

Use Star Garden or a redistributable homebrew cartridge for reproduction when
possible. Do not attach commercial ROMs. Describe a proposed feature's use case
and expected behavior; discuss substantial changes before implementing them.

## Set up a checkout

1. Fork the repository and clone your fork.
2. Create a focused branch from the latest `main`.
3. Install the tools below, then run commands from the repository root.

The complete core source is included in `third_party/fceumm/`; no submodule
initialization or separate core download is needed.

Requirements:

- JDK **17–23**; JDK **21** is the version used by the project.
- Android SDK platform **36**, Build Tools **35.0.0**, NDK **28.2.13676358**,
  and CMake **3.22.1**.
- Android SDK Platform Tools (`adb`) for device testing.
- A C/C++ compiler and host CMake **3.22.1 or later** for native tests.
- Python **3** for demo generation and its functional test; no Python packages
  are required.

Use Android Studio's SDK Manager or the command-line SDK tools to install the
Android components:

```sh
sdkmanager 'platforms;android-36' 'build-tools;35.0.0' \
  'ndk;28.2.13676358' 'cmake;3.22.1' 'platform-tools'
```

Set `JAVA_HOME` to your JDK and `ANDROID_HOME` to your Android SDK directory.
Alternatively, set `sdk.dir=/absolute/path/to/Android/sdk` in an untracked
`local.properties` file. Use the checked-in Gradle wrapper, which pins Gradle
**8.13**; the Android Gradle Plugin is pinned to **8.13.2**.

```sh
./gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. It supports
Android **8.0 / API 26** or later on `arm64-v8a` and `x86_64`. Debug builds do not
need release signing credentials. Keep SDK paths, keystores, passwords, and
generated build outputs out of commits. Signed builds are documented in
[the release guide](docs/releases.md).

## Find the relevant code

| Path | Purpose |
| --- | --- |
| `app/src/main/java/com/culpen/nes/` | Android UI, cartridge library, emulation thread, audio, and input |
| `app/src/main/cpp/` | Native session, JNI bridge, CMake configuration, and host tests |
| `app/src/androidTest/` | Custom Android instrumentation tests |
| `third_party/fceumm/` | Pinned upstream core source |
| `tools/generate_demo.py` | Source and generator for Star Garden |
| `tests/demo_smoke.py` | Demo gameplay, audio, and save/restore checks |
| `docs/` | Demo and release documentation |

Follow the surrounding Java, C++, Python, or Gradle style and keep unrelated
formatting changes out of a patch. Use focused commits with descriptive messages;
follow the existing commit style, such as `feat(emulator): ...`.

The core is process-global. Keep native calls serialized on the emulation
session thread, and preserve lifecycle cleanup, input release, and save
persistence when changing the frontend. Read [the native frontend notes](app/src/main/cpp/README.md)
before changing JNI, audio, or save-state behavior. Maintain the app's offline
gameplay and private storage model. Optional release notifications fetch public
GitHub metadata only after the user enables alerts; cartridges and saves must
remain local. Explain any proposed change to these behaviors in the pull request.

## Validate your change

Run checks that exercise the behavior you changed. Add or update a regression
test for a bug fix when practical. Documentation-only changes need a review of
commands and links; Android or native code changes need the relevant checks
below. State any checks you could not run in the pull request.

### Android build and lint

```sh
./gradlew lintDebug assembleDebug assembleDebugAndroidTest testDebugUnitTest
python3 -m unittest discover -s tests -p 'test_*release*.py' -v
```

### Android device tests

Use an API 26+ device or emulator with a supported ABI. Enable debugging and
check that `adb devices -l` reports it as `device`. For wireless pairing and the
build/install helper, see [the README](README.md#wireless-debugging).

With one target attached, install both APKs and run the custom instrumentation:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

On Android 13 / API 33 or later, grant notification permission before the
delivery tests, and keep the **New versions** channel enabled:

```sh
adb shell pm grant com.culpen.nes android.permission.POST_NOTIFICATIONS
```

Then run the suite:

```sh
adb shell am force-stop com.culpen.nes
adb shell am instrument -w com.culpen.nes.test/com.culpen.nes.EmulatorInstrumentation
```

With multiple targets, add `-s SERIAL` after `adb` in each command. Check that the
runner reports zero failures. It uses a separate demo copy and does not launch
the app Activity; reopen the app after it finishes.

The debug APK cannot replace an official release signed with a different key.
Use a separate device or emulator for development if you need to retain an
official installation's library and saves. Uninstalling or clearing app data
removes them, and save export is not implemented.

For UI and lifecycle changes, also exercise Star Garden manually: movement and
multitouch, sound, pause/resume, quick save/load, returning to the library,
backgrounding, and rotation. Test direct `.nes` and ZIP imports when changing
the library. When accessory hardware is available, controller changes should be
checked on it; otherwise report external input as unverified. Report the device
and input method actually tested. External accessory testing is optional and
does not block first stable; full on-device touch testing is required.

For release notification changes, exercise the first-launch offer and
**Info → Updates**, including enabling and disabling alerts, Android 13+
permission grant and denial, and a blocked **New versions** channel. Verify
that published newer betas and stable releases each alert once, repeated checks
do not alert again, and tapping different alerts opens their respective release
pages in a browser. Check that disabling alerts cancels pending work, offline
checks can recover when connectivity returns, and gameplay still works offline.
Use controlled release fixtures for these checks; do not publish fake releases
to test notifications. Record which permission, scheduling, and browser behaviors
were actually observed on a device.

### Native host tests

These checks do not require Android SDK components:

```sh
cmake -S app/src/main/cpp -B build/native -DCMAKE_BUILD_TYPE=Release
cmake --build build/native --parallel
ctest --test-dir build/native --output-on-failure
build/native/nescore_smoke app/src/main/assets/demo.nes
```

CTest runs the native regression suite for cartridge validation, battery RAM,
save-state rejection and restoration, and audio handling. The separate smoke
runner checks the bundled demo through the native frontend. Add native
regressions in `app/src/main/cpp/regression_main.cpp` and device regressions in
`app/src/androidTest/java/com/culpen/nes/EmulatorInstrumentation.java` as appropriate.

### Star Garden changes

Edit `tools/generate_demo.py` and regenerate the bundled cartridge:

```sh
python3 tools/generate_demo.py
```

Include both the generator and updated `app/src/main/assets/demo.nes` when its
output changes. Update gameplay assertions in `tests/demo_smoke.py` if the
intended behavior changes. That test requires a built FCEUmm libretro shared
library; [the demo notes](docs/demo.md) include a macOS build-and-run example.

## Updating the core and licenses

Prefer frontend changes over incidental edits to vendored source. For an
FCEUmm update, document the upstream commit and any local modifications in
[third-party provenance](third_party/README.md). Review the CMake source list,
`GIT_VERSION`, and save-state core identity in `nes_session.cpp`, and explain
the effect on existing saves. Run the native and Android checks for core updates.

Preserve upstream copyright and license notices. The app and native frontend
use GPL version 2 or later, as recorded in [COPYING](COPYING). The original Star
Garden code and artwork are CC0, as recorded in [the demo notes](docs/demo.md).
Identify the source and license of any added dependency, asset, or test fixture.

## Submit a pull request

Keep each pull request focused on one change and target `main`. Explain the
problem, resulting behavior, and any issue it addresses. Include the commands
and results of relevant checks, device details for manual testing, and screenshots
for visible UI changes. Call out changes to cartridge compatibility, saves,
permissions, dependencies, or vendored code so reviewers can assess their effect.

Update the relevant documentation alongside behavior changes. Before submitting,
review `git diff` and run `git diff --check` to catch whitespace errors and
unintended files.

## Automatic versions and the first stable release

Every merged pull request targeting `main` is eligible for an automatic release,
including pull requests from first-time contributors and forks. The author does
not need to be a maintainer or repository owner. Maintainers still review and
merge changes using the repository's normal permissions. Writing a release
marker in an unmerged pull request does not publish anything.

Until the first stable release, releases are `1.0.0-beta.N` and are marked as
prereleases. To request the first stable release, include this exact text on its
own line in the pull request body, outside code fences and quotations:

```text
[release:stable]
```

The marker is case-sensitive and MUST have no leading/trailing spaces, checkbox,
list prefix, or other text on that line. Fenced examples, HTML comments,
quotations, inline mentions, and PR discussion comments do not request promotion.

That merged pull request promotes the project to **1.0.0**. Subsequent releases
are stable permanently; the marker is not needed again. Stable releases use a
Conventional Commit pull request title: `feat: ...` or `feat(scope): ...` bumps
the minor version; a title such as `feat!: ...` or `fix(scope)!: ...`, or a
`BREAKING CHANGE:` line in the body, bumps the major version. Other titles bump
the patch version. A standalone `[release:major]`, `[release:minor]`, or
`[release:patch]` body line explicitly overrides the inferred stable bump. Use
only one marker per pull request; conflicting bump markers are rejected.
Ordinary bump markers do not bypass the beta period. The first stable version
is always `1.0.0`, rather than an inferred major/minor/patch increment.
When several unreleased PRs are included in one publication, the highest
requested stable bump wins. After stable promotion, another `[release:stable]`
line does not reset the version or reopen the beta period.

**The release bot reads release markers and version metadata. It does not
evaluate the criteria below, inspect evidence, or certify readiness. Maintainers
MUST enforce this policy before merging the first stable request. A passing
workflow, an accepted marker, or a contributor's checked box is not evidence
that these requirements were satisfied.** The same criteria apply regardless
of who authored the pull request. Do not merge a stable marker on the assumption
that the bot will stop an unready release.

The [release guide](docs/releases.md#automatic-releases) explains signing setup,
version codes, artifacts, and recovery. No release signing key is required to
contribute a pull request; maintainers handle official signed candidates.

## Mandatory first-stable acceptance policy

The requirements in this section are cumulative. **MUST**, **MUST NOT**, and
**REQUIRED** are mandatory maintainer rules for the first stable release, not
suggestions. They apply to the actual shipping app, its native core, bundled
content, build tools, and release artifacts. They do not assert that the current
beta has passed them.

### 1. Freeze the candidate and identify its evidence

1. Appoint a release owner responsible for collecting evidence and an independent
   technical reviewer responsible for checking it. These MUST be different
   people; at least the release owner MUST be a maintainer authorized to merge.
   The contributor requesting stable may be either person if otherwise eligible.
2. Record the full 40-character candidate commit SHA and prospective merge SHA,
   the last official beta tag, and every application, native, asset, dependency,
   Gradle, CMake, and release-tool change since that beta. Do not use a branch
   name alone as a source identity. Evidence MUST identify its tested SHA.
3. Build a **production release candidate** from the prospective merge tree with
   `versionName=1.0.0`, the planned higher Android `versionCode`, R8, resource
   shrinking, both shipped ABIs, and the official signing certificate. The
   release key MUST stay with the maintainer; fork contributors MUST NOT receive
   it. Debug-build results supplement this candidate and cannot replace testing
   the signed, optimized APK.
4. Record the candidate APK SHA-256, signing certificate SHA-256, package name,
   embedded `versionName` and `versionCode`, build command, tool versions, and
   build date. Store the commands and unabridged relevant output in durable PR
   attachments, repository files, or linked CI artifacts with adequate retention.
   Redact passwords, keys, personal ROM names, accounts, device serials, and
   unrelated logs; do not redact failures or remove context needed to assess them.
5. Finish application/native/assets/build/release-tool changes before the final
   stable request. Prefer a final documentation-only promotion PR that contains
   the evidence links and marker. The tested prospective merge tree MUST have
   the same application/native/assets/build/release-tool payload as the final
   merge; the release's injected version values and release-report metadata are
   the only permitted build differences. A new code commit, dependency update,
   changed build setting, changed ROM, or intervening merge invalidates affected
   evidence and requires a refreshed candidate and rerun of affected checks.
6. Each result MUST be `PASS`, `FAIL`, or `NOT RUN`, with an actual observer and
   date. For mandatory coverage, `NOT RUN`, an unavailable required Android
   target, an expired evidence link, a skipped test, or an unverified assertion
   is not a pass. Optional external-accessory coverage may remain `NOT RUN`
   without blocking promotion. Screenshots alone do not establish persistence,
   input, audio, or performance. Never fabricate logs, performance numbers,
   devices, reviewer names, or test results.

### 2. Required Android and input matrix

Complete every applicable test in sections 3–7 on each of these targets using
the signed candidate. Run the debug instrumentation suite separately on each
target. A single recent phone or one emulator is insufficient.

| Required target | Minimum details to record | Required coverage |
| --- | --- | --- |
| Android 8.0 / **API 26**, `x86_64` emulator | System-image identifier, ABI, API, emulator version, host/CPU/RAM and graphics configuration | Installation; all app flows; imports; saves; lifecycle; audio/video; performance; instrumentation |
| **API 36**, `x86_64` emulator | Same details, plus page size | Both ABI packaging and current target-SDK behavior; all app flows; instrumentation |
| Recent supported Android, **physical `arm64-v8a` device** | Manufacturer/model, OS/API/build fingerprint, display refresh rate, page size, available storage and power mode | All app flows; real sound, on-device multitouch, sustained performance; official upgrade |
| **16 KiB-page** Android device or emulator using a shipped ABI | API, ABI, `getconf PAGE_SIZE`, system image/model | Signed APK installation, native-library loading, demo and every compatibility fixture, sound, save/load and rotation |

"Recent supported Android" means the newest publicly stable Android release
available when the candidate is approved, or a newer supported Android version
already used by the project. Record the choice and version; a preview-only
result cannot replace the API 26 and API 36 rows. The 16 KiB row may reuse another
target only if its measured page size is 16384. Otherwise add another target.
Root is not required on the physical device. Use a disposable, root-capable
emulator for private-file corruption tests where necessary; describe that setup.

On the physical `arm64-v8a` device, MUST test the complete **digital on-device
touch controls** in portrait and landscape: all D-pad directions, diagonal
movement, A, B, Select, Start, two-finger direction plus A/B, sliding between
directions, overlapping fingers on one button, lifting either finger first, and
cancellation/backgrounding. Hold and release simultaneous controls and rotate,
pause, lock, or return to the library while touching a direction or action.
Controls MUST release accurately, never stick, and remain clear of system
navigation/cutouts. These touch requirements apply regardless of accessory
availability; the app MUST be fully usable with its on-screen controls alone.

**External gamepads, controllers, mice, and hardware keyboards are optional
coverage. No such accessory is required for first stable, and absence of real
or synthetic accessory testing MUST NOT be a promotion blocker.** Existing
Android gamepad/keyboard event handling may ship without physical validation,
provided the README and release evidence describe it as implemented but
unverified on external hardware. Do not claim a tested accessory or mouse
support without evidence. If volunteers provide optional accessory results,
record device/transport/mappings, press/release, focus loss, and disconnect/
reconnect behavior; those results supplement the mandatory touch matrix.

### 3. Build, core, demo, and compatibility evidence

From a clean checkout of the candidate, MUST run and retain successful output:

```sh
git rev-parse HEAD
git status --short
./gradlew lintDebug lintRelease assembleDebug assembleDebugAndroidTest
cmake -S app/src/main/cpp -B build/native -DCMAKE_BUILD_TYPE=Release
cmake --build build/native --parallel
ctest --test-dir build/native --output-on-failure
build/native/nescore_smoke app/src/main/assets/demo.nes
./tools/build-release.sh
git diff --check
```

Supply the planned version through `POCKET_NES_VERSION_NAME` and
`POCKET_NES_VERSION_CODE` when building the stable candidate; see the release
guide. `git status` MUST identify any untracked local configuration; the source
and bundled demo MUST have no unexplained modifications. There MUST be zero
lint errors and no unreviewed warnings affecting shipped behavior. Record each
remaining warning, why it is harmless, and the reviewer's decision; blanket
lint suppression is unacceptable.

Run the Android instrumentation commands in [Validate your change](#validate-your-change)
on every required API/ABI target. The custom runner MUST report zero failed
tests and no setup error. Its current seven tests do not launch the Activity;
they cannot substitute for the manual app-flow matrix.

MUST also build the host libretro test library and run `tests/demo_smoke.py`,
using the platform-specific command in [the demo notes](docs/demo.md). Regenerate
Star Garden in a clean checkout and verify the bundled bytes match exactly.
Verify movement, boost, both screen boundaries on each axis, star collection,
score, chirp, reset, video replay, and sound after state restoration in the real
app. A static boot screenshot is insufficient.

Define and publish a finite compatibility matrix before approval. At minimum it
MUST include original Star Garden/mapper 0, and legally redistributable executable
homebrew or test cartridges for **mappers 1, 2, 3, and 4**, a battery-backed
cartridge, a valid NES 2.0 cartridge, and both NTSC and PAL timing. A single
fixture can cover several requirements if it actually exercises those features.
Changing a ROM header to claim a mapper without exercising its banking is not
coverage. Record fixture title/version, mapper/submapper, format, timing, source
URL or in-repository source, license/permission, SHA-256, expected behavior,
tested targets, and observed results.

For each fixture, verify boot, at least **10 minutes** of gameplay or its complete
test program, input, moving video, audible output, pause/resume, reset,
quick-save/restore, and reopening after a background save. Compare behavior
against the fixture's documented expected result, not another unverified app.
Do not add commercial ROMs to the repository, PR, logs, or release assets.

Stable means the published app's tested scope is reliable. It MUST NOT promise
every game or every FCEUmm mapper works. Clearly distinguish a tested working
mapper, a known failing cartridge, and an intentionally unsupported frontend
feature. Existing exclusions such as FDS/BIOS setup, second-player input,
cheats, rewind, remapping, and save export may remain if accurately documented
and not advertised as working. These exclusions cannot excuse failures of
import, one-player controls, sound, rotation, or the documented save behavior.

### 4. Import limits, malformed input, and privacy

Test imports through the real Android file picker, including cancellation and
denied/revoked document access. MUST verify all of the following:

- A valid direct iNES ROM and a valid direct NES 2.0 ROM; a ZIP containing exactly
  one valid ROM, including a nested path; and a ROM with a legal trainer.
- Duplicate import of the same bytes with different filenames creates one
  cartridge identity and keeps its existing saves. Different ROM bytes keep
  separate entries and saves. Move/delete the original picked document after
  import: the private copy MUST still launch after app and device restart.
- Empty files, invalid signatures, truncated headers/payloads, zero PRG size,
  overflow-sized NES 2.0 declarations, unsupported data, and a corrupt ZIP are
  rejected with an understandable message and no crash or partially added game.
- Direct-file size boundaries at **32 MiB** and **32 MiB + 1 byte**; ZIP total
  expanded-content boundaries at the same values, including skipped non-ROM
  entries. Exactly 32 MiB is the current maximum; crossing it MUST reject.
- ZIPs with zero games, multiple games, more than **128 entries**, a highly
  compressed over-limit payload, misleading extensions, and nested traversal
  names such as `../../outside.nes`. No archive entry may write outside private
  import storage; rejecting or safely treating a path as a name is acceptable.
- At least three invalid import attempts while another cartridge has saves.
  That cartridge and its saves MUST remain usable. Picker/provider read failures
  and low-storage write failures MUST not replace good data with partial bytes.

Use synthetic/redistributable fixtures and record their construction and sizes.
All malformed/over-limit cases MUST terminate without ANR, out-of-memory crash,
native crash, or unbounded expansion. On required targets allow at most
**30 seconds** per rejection using a local provider; a valid demo import and a
small malformed input MUST finish within **5 seconds**. Record actual elapsed
times, storage state, and provider. A slow network document provider is a
separate provider failure and is not offline import evidence.

Inspect the signed APK's manifest and dependencies. It MUST request no Internet,
broad storage, or unnecessary sensitive permissions, and include no analytics,
advertising SDK, telemetry, automatic uploads, or silent external download.
MUST verify airplane-mode installation/launch/import from a local provider,
gameplay, quick save/load, automatic resume, battery saves, and reopening after
device restart. App operations MUST work without an account or remote service.
Verify files remain in private app storage and no ROM/saves leak to shared
storage or logs. Inspect app traffic using a documented device observation
method or verify absence of network capability from the packaged manifest plus
a source/dependency review; record exactly what was observed.

### 5. Saves and the official beta-to-stable upgrade

Validate **all three** persistence paths separately: automatic `.auto` resume,
manual `.state` quick save, and cartridge `.sav` battery RAM. A state restore
test alone is not battery-save evidence, and an in-memory quick load is not
process-restart persistence evidence.

For each required API/ABI target:

1. In Star Garden, reach a recognizably different position and score; quick-save,
   change both, load, and verify the original position/score/video returns.
   Leave the app, force-stop only after it has completed its normal background
   checkpoint, reopen, and verify the quick-save slot still works.
2. Create a different automatic checkpoint by backgrounding and by returning to
   the library. For each path, wait for completion, force-stop/reopen, and verify
   the correct game, position, score, and audible gameplay resume. Repeat across
   a device/emulator reboot. The quick-save and automatic checkpoints MUST not
   overwrite one another.
3. Use a battery-backed fixture with an observable save value. On its first
   launch, change battery RAM, remain in foreground at least **7 seconds** so the
   five-second periodic write can occur, then force-stop without backgrounding
   first. Reopen and verify the value. This first-launch setup isolates `.sav`
   from any existing `.auto`. Also verify background/normal-exit battery writes
   and survival through device reboot. Record the fixture-specific observation.
4. Verify at least two cartridges cannot load each other's state/battery save,
   duplicate imports preserve their save identity, and restart/reset behavior
   matches the UI's promise. Missing quick saves MUST show a useful message.
5. On an isolated test setup, inject truncated/corrupt `.state` and `.auto`
   files, a wrong-cartridge state, a wrong-core-identity state, and a truncated
   battery file. Invalid data MUST be rejected without a crash, memory misuse,
   destruction of the running game, or deletion of another cartridge's saves.
   After rejection a valid save MUST still load. Explain how private files were
   prepared; don't claim a non-debuggable APK supports `adb run-as` access.
6. Exercise low-storage/write failure and interrupted atomic writes. A failed
   write MUST leave the last complete save readable and provide an accurate
   message. A sudden process kill may lose changes since the last completed
   checkpoint/periodic battery write; it MUST NOT corrupt earlier committed
   files. Record the tested boundary and document it for users.

For the official upgrade, use a separate target or preserve its data without
uninstalling. Install the **latest published official beta APK**, create at
least two imported cartridges, a manual quick save, a distinct automatic state,
battery-backed progress, and sound preference; then force-stop. Capture the
beta package/version and signing certificate. Install the signed stable
candidate over it with `adb install -r`, with no uninstall, `pm clear`, data
reset, or substitute debug installation. Verify:

- Both APKs have package `com.culpen.nes`, the identical official signing
  certificate, and the candidate has a strictly greater embedded `versionCode`.
- The stable candidate reports `1.0.0`; every imported cartridge, duplicate
  identity, preference, `.auto`, `.state`, and battery save remains usable.
- Each game opens, has sound/input, can save again, and survives another reboot.
  Verify the same upgrade on the minimum-API emulator and physical arm64 device.
- If any published beta used a different core/state identity, test that beta
  too. For **first stable**, unexplained loss of an advertised beta save or a
  requirement to uninstall/clear data is a blocker. Implement and test a safe
  migration before promotion; release notes alone do not satisfy preservation.

Save export is currently unavailable. A backup claim or a fresh installation
MUST NOT replace this upgrade test. State audio need not be sample-identical,
as described in the native notes, but restored sound MUST remain functional
without cumulative timing error or sustained stale audio.

### 6. Lifecycle, usability, sound, and sustained performance

MUST perform each lifecycle sequence at least **10 times per required target**:
pause/resume, library/game switching, foreground/background, screen lock/unlock,
rotation while running, rotation while paused, and activity recreation. Include
rapid repeated actions while touching an on-screen control or requesting a
save/load. Verify no stuck input, overlapping native sessions,
continued background sound, frozen UI, duplicate emulation, lost valid saves,
black image, or crash. Test system Back, recent-app switching, and process
death after a completed checkpoint. Record actual recreation/death commands;
ordinary rotation is not proof of Activity destruction because this app handles
orientation configuration changes itself.

Inspect portrait and landscape on a small API 26 screen and the physical
device, with a cutout/system navigation and increased font/display size.
Library, import button, pause, save/load, menus, error messages, and controller
help MUST remain reachable and legible. Touch buttons MUST expose meaningful
accessibility labels and independent touch targets. Verify screen-reader
navigation of library/actions; document any intentionally limited gameplay
accessibility without describing it as fully supported.

Use the unmodified shipping playback loop and document target, fixture, room
conditions, device power/thermal mode, host load, refresh rate, measurement
method, and raw timestamps/samples. MUST meet these budgets:

| Measurement | Required result |
| --- | --- |
| Cold launch to usable library; demo selection to playable video | Each at most **5 seconds**, measured over 5 attempts per required target |
| Physical arm64 continuous demo session | At least **30 minutes** with sound and normal input; no crash, ANR, audio-thread failure, or thermal degradation below the frame-rate budget |
| API 26 and API 36 emulator continuous demo session | At least **10 minutes** each with sound; same correctness requirements; record adequate host resources |
| Steady-state emulation speed | Mean at least **98%** of the fixture's nominal fps, excluding the first minute and intentional pauses; no continuous **5-second** interval below **90%**. Use NTSC/PAL nominal fps, not display refresh rate |
| Sound | Audible B chirp and fixture music at start, midpoint, and end; no repeated dropout, runaway pitch, cumulative desynchronization, or unintended sustained sound after pause/background; record route/volume and observed interruptions |
| Memory stability | Sample `dumpsys meminfo com.culpen.nes` every minute; mean PSS of the final 3 minutes no more than the first 3 warmed-up minutes plus the greater of **20 MiB or 20%**; no monotonic unbounded growth |
| Reload stability | After 10 game-load/library cycles, warmed-up PSS within the greater of **10 MiB or 10%** of the comparable initial measurement; no orphan playback thread/native owner |

App-displayed rounded fps can support a recording but MUST NOT be described as
raw frame timing. If more precise measurements need a temporary probe, retain
the probe patch, show it changes observation only, and repeat the shipping APK's
manual soak. Audio recordings/video may supplement logs; do not post private
screen content. A headless host benchmark is not physical-device performance.
These budgets apply to the declared required fixtures and device matrix; do
not extrapolate to untested devices or every possible ROM.

### 7. Production artifacts, licensing, and security review

The release owner MUST check the exact signed candidate with the pinned Android
build tools, retain output, and confirm both native ABIs are present:

```sh
apksigner verify --verbose --print-certs CANDIDATE.apk
zipalign -c -P 16 -v 4 CANDIDATE.apk
aapt dump badging CANDIDATE.apk
aapt dump xmltree CANDIDATE.apk AndroidManifest.xml
aapt dump permissions CANDIDATE.apk
unzip -l CANDIDATE.apk
shasum -a 256 CANDIDATE.apk
```

Use the build tools' full paths if they are not on `PATH`. The signing
certificate MUST match the latest official beta and the configured release
certificate fingerprint. The manifest MUST contain the approved stable version,
planned higher version code, API/ABI scope, and `debuggable=false`. JNI MUST load
under R8 and both ABIs MUST run; ZIP alignment alone is not proof of native ELF
page-size compatibility. Keep the native alignment check and the actual 16 KiB
runtime result together.

MUST produce the versioned signed APK, `SHA256SUMS`, machine-readable
`release.json`, and available corresponding source for the exact release tag.
The checksum file MUST name the APK it accompanies and verify from an otherwise
empty download directory. `release.json` MUST agree with the APK and record the
merged source commit and official certificate/version metadata. GitHub source
archives or an explicit source asset MUST contain complete Android/native/core
source, generator, build scripts, modifications, and required license notices;
check no ignored local source or downloaded prebuilt core is necessary.

The release owner MUST perform a second clean rebuild using the documented
pinned toolchain and version inputs and record its source SHA, commands,
environment, and artifact hashes. If signed APK bytes are reproducible, record
that fact. If signing/container timestamps make them differ, account for the
differences and compare manifest, resources, DEX, native libraries, bundled ROM,
licenses, and build inputs; do not claim byte-for-byte reproduction without it.
After automatic publication, retain an attestation tying the final merged SHA
to its actual signed asset and repeat install/demo/save/audio smoke testing on
that asset. An unexplained difference MUST trigger release incident handling,
not a retroactive claim that the pre-merge candidate proved the final asset.

MUST review GPL corresponding-source completeness, COPYING and vendored
copyright notices, core provenance/version identity, CC0 demo attribution, and
every added dependency/fixture license. Release notes MUST include supported
Android/ABI scope, finite tested compatibility, current limitations, known
issues, save compatibility, and update instructions. No commercial ROM,
proprietary fixture, signing key, passwords, personal document, or private log
may appear in source archives or assets.

MUST review changed parsers, JNI bounds, state deserialization, archive limits,
private-file paths, lifecycle serialization, dependencies, and release workflow
permissions for exploitable errors. Review secrets and workflow logs for leaks;
untrusted PR code MUST NOT receive official signing credentials. Record the
reviewer, reviewed commits, dependencies and advisory lookup date, findings,
and resolution. A clean scanner alone is not a completed review.

### 8. Blockers, signoff, and the final stable request

Promotion MUST remain beta if any requirement is incomplete, evidence is stale,
or the candidate has any unresolved security issue, crash/ANR, install/upgrade
failure, data loss/corruption, wrong signature/version, broken required input,
broken import limit, nonfunctional required sound/video, or failure of the
defined compatibility/performance matrix. There MUST be zero open release
blockers and zero unexplained required-test failures. A known minor cosmetic
issue may remain only if it does not violate an acceptance requirement and its
impact, reproduction, issue link, and independent reviewer decision appear in
the evidence and release notes. "Works on my phone", a deadline, or lack of a
required Android target is not a waiver. Missing mandatory touch/device coverage
means continue shipping betas; missing optional external-accessory coverage does
not.

Before merging, the release owner and independent reviewer MUST independently
write dated signoffs identifying the final prospective merge SHA, candidate APK
hash/certificate/version code, every evidence link, scope exclusions, and zero
remaining blockers. Each MUST explicitly state that they have read this policy
and verified every mandatory requirement. A contributor's self-attestation
cannot replace maintainer review. If the PR changes after signoff, review its
effect and refresh the affected evidence/signoffs before merging.

Use this evidence index in the first-stable pull request body. Expand each row
with links, commands, targets, observer/date, results, and issue resolutions;
writing `PASS` without supporting evidence does not satisfy the row.

| Evidence | Required record |
| --- | --- |
| Candidate/source identity | Candidate and prospective merge SHA; delta from last beta; no intervening untested payload |
| Signed production candidate | APK hash; certificate fingerprint; package; embedded `1.0.0` and planned version code; release build command/output |
| Android/input matrix | Every required API/ABI/page-size/device and full on-device touch results; external-input implementation accurately labeled unverified unless optional evidence exists |
| Automated checks | Clean build/lint; native regression/smoke; original-demo functional test; instrumentation on every target |
| Compatibility | Fixture provenance/licenses/hashes; mapper/format/timing expectations and per-target gameplay results |
| Import/privacy | Size/entry boundaries, malformed/abuse/provider/storage cases; offline/permissions/data review |
| Persistence/upgrade | Independent `.auto`, `.state`, `.sav` results; corruption/atomic failure; official beta in-place upgrade on API 26 and physical arm64 |
| Lifecycle/usability | Repetition counts, real Activity recreation/process death, layouts, control release and accessibility |
| Audio/performance | Soak duration; fps method/raw samples; PSS samples; sound observations and target conditions |
| Release/source/license/security | Signature/manifest/ABI/alignment checks; reproducibility record; complete source; notices; human security review |
| Blockers and release notes | Issue inventory; zero blockers; supported scope, limitations and accurate update guidance |
| Signoffs | Two named, dated independent human statements bound to candidate/source/version |
| Publication follow-up owner | Person responsible for merged-SHA artifact attestation and downloaded-asset smoke |

Add `[release:stable]` as a standalone body line only once maintainers have
confirmed the evidence and readiness. Anybody may propose that line; the merger
is responsible for enforcing this policy. After `1.0.0` is published, ordinary
reviewed contributor PRs trigger stable updates under the semantic-version rules
without another first-stable ceremony. Review and appropriate regression
validation still apply to each update.
