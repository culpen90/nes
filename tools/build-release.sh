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

if [[ -n "${POCKET_NES_TARGET_SHA:-}" ]]; then
  if [[ "$(git rev-parse HEAD)" != "$POCKET_NES_TARGET_SHA" ]] || ! git diff --quiet HEAD --; then
    echo 'Official release builds require the planned commit without tracked source changes.' >&2
    exit 1
  fi
fi

./gradlew assembleRelease lintRelease --console=plain
nes_apk='app/build/outputs/apk/release/app-release.apk'
nes_signature="$("$nes_tools/apksigner" verify --verbose --print-certs "$nes_apk")"
printf '%s\n' "$nes_signature"
if [[ "$nes_signature" == *'CN=Android Debug'* ]]; then echo 'Refusing the Android debug signing certificate.' >&2; exit 1; fi
nes_signer="$(printf '%s\n' "$nes_signature" | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
if [[ ! "$nes_signer" =~ ^[a-fA-F0-9]{64}$ ]]; then echo 'Cannot determine the release signing certificate.' >&2; exit 1; fi
nes_signer="$(printf '%s' "$nes_signer" | tr 'A-F' 'a-f')"
nes_expected_signer="$(printf '%s' "${POCKET_NES_SIGNING_CERT_SHA256:-}" | tr 'A-F' 'a-f')"
if [[ -n "$nes_expected_signer" && "$nes_signer" != "$nes_expected_signer" ]]; then
  echo 'Release signing certificate does not match the official certificate.' >&2
  exit 1
fi
"$nes_tools/zipalign" -c -P 16 4 "$nes_apk"
nes_manifest="$("$nes_tools/aapt2" dump badging "$nes_apk")"
if [[ "$nes_manifest" == *'application-debuggable'* ]]; then echo 'Refusing a debuggable APK.' >&2; exit 1; fi
if [[ "$nes_manifest" != *"name='com.culpen.nes'"* ]]; then echo 'Unexpected release application ID.' >&2; exit 1; fi
nes_version="$(printf '%s\n' "$nes_manifest" | sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -1)"
nes_version_code="$(printf '%s\n' "$nes_manifest" | sed -n "s/.*versionCode='\([^']*\)'.*/\1/p" | head -1)"
if [[ ! "$nes_version" =~ ^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-beta\.[1-9][0-9]*)?$ ]]; then echo 'Unexpected release version.' >&2; exit 1; fi
if [[ ! "$nes_version_code" =~ ^[1-9][0-9]*$ ]]; then echo 'Unexpected Android version code.' >&2; exit 1; fi
if [[ -n "${POCKET_NES_VERSION_NAME:-}" && "$nes_version" != "$POCKET_NES_VERSION_NAME" ]]; then
  echo 'APK version name does not match the planned release.' >&2; exit 1
fi
if [[ -n "${POCKET_NES_VERSION_CODE:-}" && "$nes_version_code" != "$POCKET_NES_VERSION_CODE" ]]; then
  echo 'APK version code does not match the planned release.' >&2; exit 1
fi
nes_commit="$(git rev-parse HEAD)"
if [[ -n "${POCKET_NES_TARGET_SHA:-}" && "$nes_commit" != "$POCKET_NES_TARGET_SHA" ]]; then
  echo 'Checkout does not match the planned source commit.' >&2; exit 1
fi
nes_prerelease=false
if [[ "$nes_version" == *-beta.* ]]; then nes_prerelease=true; fi
if [[ -n "${POCKET_NES_PRERELEASE:-}" && "$nes_prerelease" != "$POCKET_NES_PRERELEASE" ]]; then
  echo 'APK version does not match the planned release channel.' >&2; exit 1
fi
nes_artifact="pocket-nes-$nes_version.apk"
mkdir -p build/release
cp "$nes_apk" "build/release/$nes_artifact"
(
  cd build/release
  if command -v sha256sum >/dev/null; then sha256sum "$nes_artifact" > SHA256SUMS
  else shasum -a 256 "$nes_artifact" > SHA256SUMS
  fi
)
python3 - "$nes_version" "$nes_version_code" "$nes_commit" "$nes_prerelease" "$nes_artifact" "$nes_signer" <<'PY'
import hashlib
import json
import pathlib
import sys

version, code, commit, prerelease, apk, signer = sys.argv[1:]
output = pathlib.Path('build/release')
metadata = {
    'version_name': version,
    'version_code': int(code),
    'target_sha': commit,
    'prerelease': prerelease == 'true',
    'apk': apk,
    'sha256': hashlib.sha256((output / apk).read_bytes()).hexdigest(),
    'signer_sha256': signer.lower(),
}
(output / 'release.json').write_text(json.dumps(metadata, indent=2) + '\n')
PY
echo "Release APK: build/release/$nes_artifact"
echo 'Checksum: build/release/SHA256SUMS'
echo 'Version and source metadata: build/release/release.json'
