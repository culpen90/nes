#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -z "${JAVA_HOME:-}" && -d /opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ]]; then
  export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
fi
if [[ -z "${ANDROID_HOME:-}" && -d "$HOME/Library/Android/sdk" ]]; then
  export ANDROID_HOME="$HOME/Library/Android/sdk"
fi
if [[ -n "${ANDROID_HOME:-}" ]]; then export PATH="$ANDROID_HOME/platform-tools:$PATH"; fi
if ! command -v adb >/dev/null; then echo 'Install Android SDK Platform Tools (adb) first.' >&2; exit 1; fi

if [[ $# -gt 0 ]]; then
  nes_device="$1"
else
  nes_devices=()
  while IFS= read -r nes_serial; do nes_devices+=("$nes_serial"); done < <(adb devices | awk 'NR > 1 && $2 == "device" {print $1}')
  if [[ ${#nes_devices[@]} -ne 1 ]]; then
    echo 'Connect one authorized phone, or supply its serial: tools/run-on-phone.sh SERIAL' >&2
    adb devices -l
    exit 1
  fi
  nes_device="${nes_devices[0]}"
fi
./gradlew assembleDebug --console=plain
adb -s "$nes_device" install -r app/build/outputs/apk/debug/app-debug.apk
adb -s "$nes_device" shell am start -n com.culpen.nes/.MainActivity
