#!/bin/bash
# ---------------------------------------------------------------------------
# Jumper - dependency-free APK build (aapt2 + javac + d8 + apksigner).
# Works locally and on GitHub Actions runners (ubuntu-latest has the SDK).
#
# Requirements:
#   ANDROID_HOME  - Android SDK (default: local sandbox path)
#   JAVA_HOME     - JDK 17+ with javac (default: local sandbox path)
#   JUMPER_KEYSTORE - optional signing keystore path (defaults to the
#                     committed signing.keystore so every build - local or
#                     CI - produces an APK with the SAME signature)
# ---------------------------------------------------------------------------
set -euo pipefail

PROJECT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT"

SDK="${ANDROID_HOME:-/home/z/my-project/android-tools/android-sdk}"
if [ ! -d "$SDK" ]; then SDK="/usr/local/lib/android/sdk"; fi

if [ -z "${JAVA_HOME:-}" ]; then
  export JAVA_HOME="/home/z/my-project/android-tools/jdk"
fi
export PATH="$JAVA_HOME/bin:$PATH"

# newest installed build-tools + platform
BT="$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1)"
PLATFORM="$(ls "$SDK"/platforms/android-*/android.jar 2>/dev/null | sort -V | tail -1)"
echo "SDK:       $SDK"
echo "build-tools: $BT"
echo "platform:    $PLATFORM"
if [ -z "$BT" ] || [ -z "$PLATFORM" ]; then
  echo "ERROR: Android SDK build-tools or platform not found." >&2
  exit 1
fi

VERSION_CODE="${VERSION_CODE:-1}"
VERSION_NAME="${VERSION_NAME:-1.0.0}"
OUT="$PROJECT/build"
rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/gen" "$OUT/dex"

echo "[1/6] compiling resources (aapt2)"
"$BT/aapt2" compile --dir app/src/main/res -o "$OUT/res.zip"

echo "[2/6] linking resources (aapt2)"
"$BT/aapt2" link -o "$OUT/base.apk" \
  --manifest app/src/main/AndroidManifest.xml \
  -I "$PLATFORM" \
  --java "$OUT/gen" \
  --min-sdk-version 21 \
  -A app/src/main/assets \
  --target-sdk-version 34 \
  --version-code "$VERSION_CODE" \
  --version-name "$VERSION_NAME" \
  "$OUT/res.zip"

echo "[3/6] compiling java (javac, target 8)"
find app/src/main/java "$OUT/gen" -name "*.java" | sort > "$OUT/sources.txt"
# android.jar on the classpath (not bootclasspath) so javac can see
# java.lang.invoke.LambdaMetafactory; d8 desugars the lambdas for min-api 21.
"$JAVA_HOME/bin/javac" -source 8 -target 8 \
  -classpath "$PLATFORM" \
  -Xlint:-options -encoding UTF-8 \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "[4/6] dexing (d8)"
(cd "$OUT/classes" && zip -qr "$OUT/classes.zip" .)
"$BT/d8" --release --min-api 21 --lib "$PLATFORM" \
  --output "$OUT/dex" "$OUT/classes.zip"

echo("[5/6] packaging + aligning")
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -qj unsigned.apk dex/classes.dex)
"$BT/zipalign" -f 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo("[6/6] signing")
KEYSTORE="${JUMPER_KEYSTORE:-$PROJECT/signing.keystore}"
KEYSTORE_DIR="$(dirname "$KEYSTORE")"
mkdir -p "$KEYSTORE_DIR"
if [ ! -f "$KEYSTORE" ]; then
  echo "  generating signing keystore at $KEYSTORE"
  "$JAVA_HOME/bin/keytool" -genkeypair -keystore "$KEYSTORE" \
    -storepass jumper -keypass jumper \
    -alias jumper -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Jumper, OU=Game, O=Jumper, L=Kathmandu, C=NP"
fi
"$BT/apksigner" sign --ks "$KEYSTORE" \
  --ks-pass pass:jumper --key-pass pass:jumper \
  --out "$PROJECT/Jumper.apk" "$OUT/aligned.apk"
"$BT/apksigner" verify "$PROJECT/Jumper.apk"

SIZE=$(du -h "$PROJECT/Jumper.apk" | cut -f1)
echo "SUCCESS: Jumper.apk ($SIZE)"
