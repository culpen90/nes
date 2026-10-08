# Pocket NES releases

## First beta

**1.0.0-beta.1** is the first beta, tagged [`v1.0.0-beta.1`](https://github.com/culpen90/nes/releases/tag/v1.0.0-beta.1). The production APK is signed with the project's release key, optimized with R8, and built with resource shrinking. It supports Android 8.0 / API 26 or later and includes `arm64-v8a` and `x86_64` native libraries. Its Android version code is **1**.

The release APK passes signature and 16 KiB ZIP-alignment verification, has no debugging enabled, and preserves the JNI entry points through R8 optimization. The exact signed artifact was exercised on an isolated Android API 36 arm64 emulator: the included demo, touch movement, quick save/load, pause, and rotation work. Android release lint and native regression checks pass.

| File | Purpose |
| --- | --- |
| [pocket-nes-1.0.0-beta.1.apk](https://github.com/culpen90/nes/releases/download/v1.0.0-beta.1/pocket-nes-1.0.0-beta.1.apk) | Installable production APK |
| [SHA256SUMS](https://github.com/culpen90/nes/releases/download/v1.0.0-beta.1/SHA256SUMS) | SHA-256 checksum for the APK |

Download both files into the same directory and verify the APK on macOS with:

```sh
shasum -a 256 -c SHA256SUMS
```

On Linux, use `sha256sum -c SHA256SUMS`. Android may ask you to allow installs from the application used to open the APK. Later official releases can update this installation while retaining its private library and saves.

The existing debug build uses a different signing certificate. Android cannot update it in place with the production APK. Preserve its private ROMs, saves, and preferences before uninstalling it; uninstalling removes that data. The app does not yet provide save export.

## Build a signed APK

Use the SDK and JDK requirements in the [README](../README.md#build). Independent builders should create their own release key. For example, this command prompts for the passwords and certificate details:

```sh
mkdir -p "$HOME/.config/pocket-nes"
keytool -genkeypair \
  -keystore "$HOME/.config/pocket-nes/my-pocket-nes-release.p12" \
  -storetype PKCS12 \
  -alias pocket-nes \
  -keyalg RSA -keysize 4096 -validity 10000
```

Create a private properties file at `$HOME/.config/pocket-nes/release-signing.properties` with these fields, using your keystore's absolute path and credentials:

```properties
storeFile=/absolute/path/to/my-pocket-nes-release.p12
storePassword=YOUR_KEYSTORE_PASSWORD
keyAlias=pocket-nes
keyPassword=YOUR_KEY_PASSWORD
```

For the PKCS12 example, use the keystore password as the key password. Keep both the keystore and properties file outside the repository, restrict their permissions, and retain a secure backup of the key. To use a different properties file, set `POCKET_NES_SIGNING_PROPERTIES` to its absolute path.

Build and verify the signed release:

```sh
./tools/build-release.sh
```

The Gradle release APK is written to `app/build/outputs/apk/release/app-release.apk`. The builder copies the versioned APK and `SHA256SUMS` into `build/release/` for upload. It uses the release build type with R8 and resource shrinking; the JNI bridge remains available to the native emulator. The APK is non-debuggable and uses the supplied private signing key. The first beta's APK is `build/release/pocket-nes-1.0.0-beta.1.apk`.

Each update must use the same signing key as the installation it updates. Increase `versionCode` for each published update and set `versionName` to the user-facing release version. APKs signed with independently generated keys cannot update official installations.

## Source and licenses

The [tagged source](https://github.com/culpen90/nes/tree/v1.0.0-beta.1) includes the Android frontend, build files, complete vendored FCEUmm core, and the original demo's generator. GitHub's source archives for this tag contain the corresponding source used for the APK.

- [Source ZIP](https://github.com/culpen90/nes/archive/refs/tags/v1.0.0-beta.1.zip)
- [Source tar.gz](https://github.com/culpen90/nes/archive/refs/tags/v1.0.0-beta.1.tar.gz)

Pocket NES and the FCEUmm frontend/core are licensed under GPL version 2 or later; see [COPYING](../COPYING) and [third-party provenance](../third_party/README.md). The original **Star Garden** code and artwork are CC0; see [demo notes](demo.md). The release includes the original demo and no commercial game ROMs.
