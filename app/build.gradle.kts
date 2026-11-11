import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.xreader"
    compileSdk = 36
    // AGP 8.7.3's lint-vital crashes parsing this sandbox SDK's build-tools
    // 37.0.0 revision ("For input string: 37.0") — pin the 36.0.0 tools,
    // same as the sibling ReTranslate build (see BUILD-ENV.md).
    buildToolsVersion = "36.0.0"

    lint {
        // Same lint-vital crash as above; the phase gate is the unit suite,
        // not lint. Re-enable when AGP is next upgraded.
        checkReleaseBuilds = false
    }

    defaultConfig {
        applicationId = "app.xreader"
        minSdk = 26
        targetSdk = 36
        versionCode = 81
        versionName = "1.5.39"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        // v1.5.7 (size cap): ship ARM ABIs only. The Komikku SSIV
        // fork's transitive image-decoder native library added
        // ~21.5 MB across four ABIs and pushed the APK to
        // 106.4 MB, over the 100 MB cap. Both proof phones (and
        // effectively every real phone) are ARM; the x86/x86_64
        // libs only ever served emulators, and the emulator
        // programme is closed (2026-11-02). Dropping them takes
        // the APK back under the cap with zero change to what
        // runs on ARM devices — the SSIV native decoder included.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        create("release") {
            // New keystore for app.xreader (PLAN.md §11.3). Passwords arrive
            // via the environment at build time — the password itself lives
            // ONLY in release/KEYSTORE-README.txt, never in this file.
            storeFile = rootProject.file("release/xreader-release.jks")
            storePassword = System.getenv("XR_STORE_PASSWORD")
            keyAlias = System.getenv("XR_KEY_ALIAS") ?: "xreader"
            keyPassword = System.getenv("XR_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // v1.4.1 (user rule, 2026-11-02): NO shrinking, ever. The
            // v1.4.0 release crashed on launch because R8 stripped the
            // generic Signature that Injekt's TypeReference reads at
            // runtime ("TypeReference constructed without actual type
            // information" inside AppContainer.<init>) — and every
            // other reflection/serializer surface in the app (Room,
            // kotlinx.serialization models, QuickJS bindings, the
            // extension API) carries the same latent risk. The user
            // has ruled app size is not a constraint ("size doesn't
            // matter if it's 1gb") and the source is public, so
            // obfuscation bought nothing. Unminified by construction
            // is the permanent fix; proguard-rules.pro is retained
            // (and kept current) purely as documentation so that if
            // minification is ever re-enabled deliberately, the keeps
            // the runtime mechanisms need are already recorded.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        // v1.5.6 (§3): Readium requires core library desugaring.
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // v1.4.0 (G-K2-1 fallout): the OkHttp 5.3.2 jars
            // (okhttp-brotli, okhttp-dnsoverhttps) and jspecify
            // all ship the same multi-release OSGi manifest;
            // the merge can only keep one — drop the duplicates.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
    testOptions {
        unitTests {
            // v1.5.2 loop 2 (DELTA 3): the live-network proof
            // classes carry @Category(LiveSmoke::class) and are
            // EXCLUDED from the deterministic gate by default;
            // the smoke set runs them explicitly with
            // -PliveSmoke. Site downtime must neither fail nor
            // pass the wiring gate. (Class-pattern exclusion:
            // runner-independent, unlike tag filtering under
            // the JUnit4/vintage mix this suite runs on.)
            all {
                if (!project.hasProperty("liveSmoke")) {
                    it.exclude(
                        "**/BotiLiveProofTest.class",
                        "**/FenrirLiveFilingTest.class",
                        "**/LiveRealSetSearchSmokeTest.class",
                        "**/LnoriLiveSmokeTest.class",
                        "**/QnConvertsLiveSmokeTest.class",
                        "**/WattpadLearnerLiveProbeTest.class"
                    )
                }
            }
            // Android stubs (Log, Keystore) are inert on the JVM by default.
            isReturnDefaultValues = true
            // v1.4.2: Robolectric (ProductionDatabaseTest) locates the
            // merged manifest/resources through AGP's test config —
            // only wired up when unit-test resources are included.
            isIncludeAndroidResources = true
        }
    }
}

// Room schema export (PLAN.md §1.3): schemas are checked into the project so
// every future schema change ships a tested manual migration.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Phase 1 data foundation: Room DB v1 (PLAN §1.3) + DataStore owners.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    // Phase 2 document engine (PLAN §4): network (OPDS + downloads),
    // background work, covers, PDF text extraction, and RAR extraction
    // for CBR (Amendment 3 — the junrar path is extraction only; its
    // licence is recorded on the Licences screen).
    // kxml2 is TEST-ONLY: Android's framework already ships the
    // XmlPullParser implementation on device, and bundling the jar
    // makes R8 fail (its org.xmlpull.v1 API classes clash with the
    // framework's). JVM tests need the real implementation, hence here.
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.coil.compose)
    implementation(libs.haze)
    // v1.0.2 (sweep gate): Baseline Profile installer — ships the
    // hand-maintained src/main/baseline-prof.txt rules into the
    // DEX profile so first frames compile ahead of time.
    implementation(libs.androidx.profileinstaller)
    implementation(libs.pdfbox.android)
    implementation(libs.junrar)

    // v1.5.6 (§3): the render-the-file EPUB carrier — Readium Kotlin
    // 3.1.2 (BSD-3). Transitves (media3, kotlin-reflect, …) are taken
    // as they come; exclusions are NOT gambled on (carrier verdict).
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    // v1.5.6: real Guava over the empty listenablefuture stub that
    // media3's graph otherwise selects (ML Kit/CameraX break).
    implementation(libs.guava)

    // v1.5.7 (Komikku full): SSIV tiling page view for the manga
    // reader (donor ReaderPageImageView) — pages decode as tiles
    // at the visible region's resolution instead of one capped
    // whole-page Coil bitmap. Apache-2.0, © Dave Morrissey /
    // Tachiyomi contributors; see assets/THIRD_PARTY_NOTICES.md.
    implementation(libs.subsampling.scale.image.view)

    // Phase 3 extension sandbox (PLAN §5): the stable source API both
    // the host and Dex extensions link against; jsoup is the host-side
    // HTML engine behind the API's HtmlParser facade and the
    // declarative selector engine; Cash QuickJS runs JS sources in a
    // context with no ambient authority (Apache-2.0).
    implementation(project(":extension-api"))
    // Keiyoushi player API surface (eu.kanade.tachiyomi.*) — transplanted in
    // slice K1; nothing in the app consumes it yet, it only needs to be on the
    // classpath for the slices that follow.
    implementation(project(":mihon-api"))
    implementation(libs.jsoup)
    implementation(libs.quickjs.android)

    // Phase 4 reader canvas (PLAN §6): ML Kit face detection for the
    // Labs eye-tracking page turns (bundled model, fully on-device),
    // fed by CameraX image analysis. Camera permission is requested
    // only when the Labs toggle is enabled.
    implementation(libs.mlkit.face.detection)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)

    // Phase 5 AI & sync (PLAN §7): Google Sign-In for the Drive
    // app-folder sync. The Drive REST API itself is hand-rolled over
    // OkHttp like every other network client in the app — only the
    // account/token handshake uses Play services.
    implementation(libs.play.services.auth)

    // Phase 6 widget (PLAN §8): Glance app widget — current book,
    // progress, streak, level.
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kxml2)
    // v1.4.2: Robolectric — the harness for tests that need a real
    // Android runtime on the JVM (first use: ProductionDatabaseTest
    // builds the Room database through the production factory; plain
    // JVM cannot run Room's Android builder at all).
    testImplementation(libs.robolectric)

    // K6: the project's first instrumented tests (the Keiyoushi
    // fixture load test). Test-only: neither artifact reaches
    // the app APK.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// ---------------------------------------------------------------------------
// TEMP-CAPTURE harness (v1.5.2 re-scan, BigTxtStreamingTest one-off failure;
// adopted in build-logs/V152-SECOND-OPINION-10-ASSESSMENT.md Q3/Q5):
// failing unit-test reports are AUTO-ARCHIVED before any re-run can
// overwrite them — the original BigTxt failure was lost exactly that way.
// The archive task runs as a finalizer of every Test task and copies the
// JUnit XMLs (reports.junitXml.outputLocation = build/test-results/<task>)
// plus the HTML reports into a timestamped dir under
// <repo>/build-logs/test-archives/, but ONLY when some result XML records
// failures/errors > 0, so green runs leave no clutter. Test-side stdout/
// stderr reaches the console log when -PshowTestStreams is passed (the
// XMLs carry it either way). To be reviewed at BigTxt closure.
// ---------------------------------------------------------------------------
val archiveFailedTestReports = tasks.register("archiveFailedTestReports") {
    description = "TEMP-CAPTURE: archive failing unit-test reports under build-logs/test-archives/."
    doLast {
        val resultsRoot = layout.buildDirectory.dir("test-results").get().asFile
        if (!resultsRoot.isDirectory) return@doLast
        val failingXml = resultsRoot.walkTopDown()
            .filter { it.isFile && it.name.startsWith("TEST-") && it.extension == "xml" }
            .firstOrNull { Regex("""(failures|errors)="[1-9]""").containsMatchIn(it.readText()) }
        if (failingXml == null) return@doLast
        val stamp = LocalDateTime.now()
            .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
        val dest = File(rootProject.projectDir, "../build-logs/test-archives/$stamp")
            .canonicalFile
        dest.mkdirs()
        resultsRoot.copyRecursively(File(dest, "test-results"), overwrite = true)
        val reportsRoot = layout.buildDirectory.dir("reports/tests").get().asFile
        if (reportsRoot.isDirectory) {
            reportsRoot.copyRecursively(File(dest, "reports-tests"), overwrite = true)
        }
        println("TEMP-CAPTURE: failing test reports archived to $dest")
    }
}

tasks.withType<Test>().configureEach {
    // v1.5.3 (poison-hunt, build-logs/V153-POISON-HUNT-REPORT.md):
    // one worker JVM per test class. The default forkEvery = 0
    // runs all ~350 classes in a single JVM, where cross-class
    // residue has bitten three times this release — most severely
    // a wedged write path on the sandbox-shared settings
    // DataStore singleton (the preferencesDataStore delegate is
    // one instance per classloader, shared by every AppContainer
    // a JVM accumulates): gate run 1 parked ~105 min on it, and
    // both DataStore-writing victims (V153LibraryStatusTest,
    // V152HistoryJourneyTest) failed non-deterministically —
    // identical predecessor sets flip green/red between runs, so
    // no forkEvery > 1 bound closes the window. Per-class forks
    // restore the isolation every class was written and validated
    // under; no assertion, timeout or production code changes.
    forkEvery = 1
    finalizedBy(archiveFailedTestReports)
    if (project.hasProperty("showTestStreams")) {
        testLogging {
            showStandardStreams = true
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }
}
