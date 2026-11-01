#!/usr/bin/env bash
# K6 smoke fixtures: build the two canned Keiyoushi-format
# extension APKs used by the instrumented player test
# (app/src/androidTest/.../KeiyoushiFixtureLoadTest.kt).
#
# Deliberately NOT a Gradle module: a broken build file here
# must never be able to break the app's gate. Plain toolchain
# only — kotlinc (embeddable compiler), d8, aapt2, zipalign,
# apksigner, keytool.
#
# Inputs (env, with workspace defaults):
#   ANDROID_SDK   default ~/workspace/tools/android-sdk
#   JDK_HOME      default ~/workspace/tools/jdk17
#   KOTLIN_JAR    kotlin-compiler-embeddable jar (required)
#   KOTLINC_LAUNCH_CP  full java classpath for the compiler
#                 process itself (embeddable jar + stdlib +
#                 coroutines + annotations + trove4j);
#                 defaults to KOTLIN_JAR:STDLIB:COROUTINES.
#   MIHON_API_JAR a :mihon-api classes jar to compile against
#                 (required — e.g. from a Gradle build of the
#                 module, or the K5/K6 harness compile)
#   EXTRA_CP      extra classpath jars for the fixture compile
#                 (must carry androidx.preference — its real
#                 AAR classes.jar once Gradle has resolved it;
#                 the sandbox harness used a signature-faithful
#                 stub overlay, see README.md)
#   STDLIB_JAR / COROUTINES_JAR / OKHTTP_JAR / OKIO_JAR /
#   SERIALIZATION_JAR — the fixture's compile classpath peers.
#
# Outputs: app/src/androidTest/assets/fixture-panels.apk and
# fixture-factory.apk, signed with a LOCAL TEST key
# (fixture-test.jks beside this script, generated on first
# run). The test key is the point: the fixtures must NOT carry
# the Keiyoushi signing key, and the instrumented test asserts
# the trust check refuses them for exactly that reason.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
SDK="${ANDROID_SDK:-$HOME/workspace/tools/android-sdk}"
JDK="${JDK_HOME:-$HOME/workspace/tools/jdk17}"
BT="$SDK/build-tools/36.0.0"
AJ="$SDK/platforms/android-36/android.jar"
# d8/apksigner are shell wrappers that exec a bare `java`.
export PATH="$JDK/bin:$PATH"
WORK="$HERE/build"
OUT_DIR="$ROOT/app/src/androidTest/assets"
KEYSTORE="$HERE/fixture-test.jks"

: "${KOTLIN_JAR:?set KOTLIN_JAR to the kotlin-compiler-embeddable jar}"
: "${MIHON_API_JAR:?set MIHON_API_JAR to a :mihon-api classes jar}"
: "${STDLIB_JAR:?set STDLIB_JAR}"
: "${COROUTINES_JAR:?set COROUTINES_JAR}"
: "${OKHTTP_JAR:?set OKHTTP_JAR}"
: "${OKIO_JAR:?set OKIO_JAR}"
: "${SERIALIZATION_JAR:?set SERIALIZATION_JAR}"
EXTRA_CP="${EXTRA_CP:-}"

rm -rf "$WORK"
mkdir -p "$WORK/classes" "$WORK/dex" "$OUT_DIR"

CP="$AJ:$MIHON_API_JAR:$STDLIB_JAR:$COROUTINES_JAR:$OKHTTP_JAR:$OKIO_JAR:$SERIALIZATION_JAR"
[ -n "$EXTRA_CP" ] && CP="$CP:$EXTRA_CP"

echo "== kotlinc fixture sources"
"$JDK/bin/java" -cp "${KOTLINC_LAUNCH_CP:-$KOTLIN_JAR:$STDLIB_JAR:$COROUTINES_JAR}" \
  org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -jvm-target 17 -cp "$CP" -d "$WORK/classes" \
  "$HERE/src/FixtureSources.kt"

echo "== d8"
"$JDK/bin/jar" cf "$WORK/classes.jar" -C "$WORK/classes" .
"$BT/d8" --min-api 26 --lib "$AJ" \
  --classpath "$MIHON_API_JAR" \
  --output "$WORK/dex" "$WORK/classes.jar"

if [ ! -f "$KEYSTORE" ]; then
  echo "== generating the fixture test key"
  "$JDK/bin/keytool" -genkeypair -keystore "$KEYSTORE" \
    -alias fixture -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass fixture -keypass fixture \
    -dname "CN=Xreader K6 Fixture, O=Xreader Tests" >/dev/null 2>&1
fi

build_apk() {
  local manifest="$1" name="$2"
  echo "== aapt2 + sign $name"
  "$BT/aapt2" link -o "$WORK/$name-unsigned.apk" \
    -I "$AJ" --manifest "$manifest" \
    --min-sdk-version 26 --target-sdk-version 36
  (cd "$WORK/dex" && zip -q -X "$WORK/$name-unsigned.apk" classes.dex)
  "$BT/zipalign" -f 4 "$WORK/$name-unsigned.apk" "$WORK/$name-aligned.apk"
  "$BT/apksigner" sign --ks "$KEYSTORE" --ks-pass pass:fixture \
    --key-pass pass:fixture --ks-key-alias fixture \
    --out "$OUT_DIR/$name.apk" "$WORK/$name-aligned.apk"
  rm -f "$OUT_DIR/$name.apk.idsig"
  "$BT/apksigner" verify "$OUT_DIR/$name.apk"
}

build_apk "$HERE/manifest-a.xml" "fixture-panels"
build_apk "$HERE/manifest-b.xml" "fixture-factory"
echo "== done: $OUT_DIR/fixture-panels.apk, $OUT_DIR/fixture-factory.apk"
