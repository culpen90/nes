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
behavior and private storage model, and explain any proposed change to these
behaviors in the pull request.

## Validate your change

Run checks that exercise the behavior you changed. Add or update a regression
test for a bug fix when practical. Documentation-only changes need a review of
commands and links; Android or native code changes need the relevant checks
below. State any checks you could not run in the pull request.

### Android build and lint

```sh
./gradlew lintDebug assembleDebug assembleDebugAndroidTest
```

### Android device tests

Use an API 26+ device or emulator with a supported ABI. Enable debugging and
check that `adb devices -l` reports it as `device`. For wireless pairing and the
build/install helper, see [the README](README.md#wireless-debugging).

With one target attached, install both APKs and run the custom instrumentation:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
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
the library. Controller changes should be checked on the relevant hardware;
report the device and input method actually tested.

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
