# BUILD-ENV — Xreader toolchain (copied from ReTranslate's, PLAN.md §11.1)

All tools live under `~/workspace/tools/` (survives VM restarts). Nothing was installed via apt.

## Versions
- JDK: Eclipse Temurin **17.0.21+9** at `~/workspace/tools/jdk17`
- Android SDK root: `~/workspace/tools/android-sdk` (platform **android-36**, build-tools **36.0.0** pinned in `app/build.gradle.kts` — AGP 8.7.3's lint-vital crashes on this SDK's 37.0 packages; release lint is disabled for the same reason)
- Gradle: project wrapper **8.11.1** (pre-seeded in GRADLE_USER_HOME)
- GRADLE_USER_HOME: `~/workspace/tools/gradle-home` — carries the egress-proxy systemProps and `-Xmx2g`. If proxy credentials rotate, update that file.
- Project: AGP 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.28, Compose BOM 2024.12.01, compileSdk/targetSdk 36, minSdk 26.

## Sandbox survival (verified on ReTranslate, re-verified for Xreader P1)
Java TCP (even loopback) is diverted to the egress proxy; IPv6 loopback is NOT. Force every JVM onto IPv6 and import the sandbox egress CA into the JDK truststore. ⚠️ The egress CA ROTATES when the VM restarts — re-run step 1 before building on a fresh VM.

1) Import current egress CA (idempotent refresh):
```bash
export JAVA_HOME=~/workspace/tools/jdk17
$JAVA_HOME/bin/keytool -delete -alias hatch-egress-ca -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit 2>/dev/null
$JAVA_HOME/bin/keytool -importcert -trustcacerts -noprompt -alias hatch-egress-ca -file /usr/local/share/ca-certificates/hatch-egress-ca.crt -keystore $JAVA_HOME/lib/security/cacerts -storepass changeit
```
2) Build:
```bash
cd ~/workspace/xreader/Xreader
export JAVA_HOME=~/workspace/tools/jdk17
export ANDROID_HOME=~/workspace/tools/android-sdk ANDROID_SDK_ROOT=~/workspace/tools/android-sdk
export GRADLE_USER_HOME=~/workspace/tools/gradle-home
export JAVA_TOOL_OPTIONS="-Djava.net.preferIPv6Addresses=true"
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:assembleDebug :app:assembleRelease   # release needs the signing env below
```

## Release signing (PLAN §11.3)
Keystore: `release/xreader-release.jks` (CN=Xreader). Password lives ONLY in `release/KEYSTORE-README.txt`. Feed it to the build via the environment — never print it:
```bash
export XR_STORE_PASSWORD=$(sed -n 's/^storePassword: //p' release/KEYSTORE-README.txt)
export XR_KEY_PASSWORD=$(sed -n 's/^keyPassword: //p' release/KEYSTORE-README.txt)
export XR_KEY_ALIAS=xreader
```

## Notes
- Do NOT use `/tmp` for downloads (512 MB tmpfs). Stage under `~/workspace/tools/staging`.
- RAM is ~8 GB total: always `--no-daemon`, 2 GB heap cap (gradle.properties).
