#!/bin/bash

set -eu

usage() {
    echo "Usage: $0 <keystore_path> <store_password> <key_password> <key_alias>"
    exit 1
}

if [ "$#" -ne 4 ]; then
    usage
fi

if [ -z "$1" ] || [ -z "$2" ] || [ -z "$3" ] || [ -z "$4" ]; then
    usage
fi

KEYSTORE_PATH="$1"
STORE_PASSWORD="$2"
KEY_PASSWORD="$3"
KEY_ALIAS="$4"

BUILD_TOOLS_VERSION="34.0.0"
UNSIGNED_APK="app/build/outputs/apk/release/app-release-unsigned.apk"
SIGNED_DIR="signed"
ALIGNED_APK="$SIGNED_DIR/app-release-aligned.apk"
SIGNED_APK="$SIGNED_DIR/ocr-release.apk"

if [ ! -f "$UNSIGNED_APK" ]; then
    echo "Unsigned release APK not found: $UNSIGNED_APK"
    echo "Run ./gradlew :app:assembleRelease -PtargetAbi=arm64-v8a first"
    exit 1
fi

if [ -z "${ANDROID_SDK_ROOT:-}" ]; then
    echo "ANDROID_SDK_ROOT is not set"
    exit 1
fi

ZIPALIGN="$ANDROID_SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION/zipalign"
APKSIGNER="$ANDROID_SDK_ROOT/build-tools/$BUILD_TOOLS_VERSION/apksigner"

if [ ! -x "$ZIPALIGN" ]; then
    echo "zipalign not found: $ZIPALIGN"
    exit 1
fi

if [ ! -x "$APKSIGNER" ]; then
    echo "apksigner not found: $APKSIGNER"
    exit 1
fi

mkdir -p "$SIGNED_DIR"
rm -f "$ALIGNED_APK" "$SIGNED_APK"

"$ZIPALIGN" 4 "$UNSIGNED_APK" "$ALIGNED_APK"

"$APKSIGNER" sign     --ks "$KEYSTORE_PATH"     --ks-pass pass:"$STORE_PASSWORD"     --ks-key-alias "$KEY_ALIAS"     --key-pass pass:"$KEY_PASSWORD"     --out "$SIGNED_APK"     "$ALIGNED_APK"

"$APKSIGNER" verify --print-certs "$SIGNED_APK"

echo "Signed APK: $SIGNED_APK"
