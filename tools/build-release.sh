#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/Library/Android/sdk" ]]; then
  export ANDROID_HOME="$HOME/Library/Android/sdk"
fi
nes_signing="${POCKET_NES_SIGNING_PROPERTIES:-$HOME/.config/pocket-nes/release-signing.properties}"
if [[ ! -f "$nes_signing" ]]; then
  echo 'Configure private release signing first. See docs/releases.md.' >&2
  exit 1
fi
if [[ -z "${ANDROID_HOME:-}" ]]; then
  echo 'Set ANDROID_HOME to the Android SDK directory.' >&2
  exit 1
fi
nes_tools="$ANDROID_HOME/build-tools/35.0.0"
for nes_tool in apksigner aapt2 zipalign; do
  if [[ ! -x "$nes_tools/$nes_tool" ]]; then echo 'Install Android Build Tools 35.0.0 first.' >&2; exit 1; fi
done

./gradlew assembleRelease lintRelease --console=plain
nes_apk='app/build/outputs/apk/release/app-release.apk'
nes_signature="$("$nes_tools/apksigner" verify --verbose --print-certs "$nes_apk")"
printf '%s\n' "$nes_signature"
if [[ "$nes_signature" == *'CN=Android Debug'* ]]; then echo 'Refusing the Android debug signing certificate.' >&2; exit 1; fi
"$nes_tools/zipalign" -c -P 16 4 "$nes_apk"
nes_manifest="$("$nes_tools/aapt2" dump badging "$nes_apk")"
if [[ "$nes_manifest" == *'application-debuggable'* ]]; then echo 'Refusing a debuggable APK.' >&2; exit 1; fi
if [[ "$nes_manifest" != *"name='com.culpen.nes'"* ]]; then echo 'Unexpected release application ID.' >&2; exit 1; fi
nes_version="$(printf '%s\n' "$nes_manifest" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
if [[ ! "$nes_version" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-z0-9.]+)?$ ]]; then echo 'Unexpected release version.' >&2; exit 1; fi
nes_artifact="pocket-nes-$nes_version.apk"
mkdir -p build/release
cp "$nes_apk" "build/release/$nes_artifact"
(
  cd build/release
  if command -v sha256sum >/dev/null; then sha256sum "$nes_artifact" > SHA256SUMS
  else shasum -a 256 "$nes_artifact" > SHA256SUMS
  fi
)
echo "Release APK: build/release/$nes_artifact"
echo 'Checksum: build/release/SHA256SUMS'
