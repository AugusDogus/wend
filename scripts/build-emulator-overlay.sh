#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:?Set ANDROID_HOME to the Android SDK}"
mkdir -p build/emulator-overlay
"$ANDROID_HOME/build-tools/36.0.0/aapt2" compile --dir emulator-overlay/res -o build/emulator-overlay/resources.zip
"$ANDROID_HOME/build-tools/36.0.0/aapt2" link \
  -I "$ANDROID_HOME/platforms/android-35/android.jar" \
  --manifest emulator-overlay/AndroidManifest.xml \
  -o build/emulator-overlay/unsigned.apk build/emulator-overlay/resources.zip
python scripts/sign-px6.py --target aosp29 build/emulator-overlay/unsigned.apk build/emulator-overlay/focus.apk
