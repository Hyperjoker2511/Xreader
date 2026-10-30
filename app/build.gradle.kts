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
        versionCode = 22
        versionName = "1.0.8.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
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
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
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
        }
    }
    testOptions {
        unitTests {
            // Android stubs (Log, Keystore) are inert on the JVM by default.
            isReturnDefaultValues = true
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

    // Phase 3 extension sandbox (PLAN §5): the stable source API both
    // the host and Dex extensions link against; jsoup is the host-side
    // HTML engine behind the API's HtmlParser facade and the
    // declarative selector engine; Cash QuickJS runs JS sources in a
    // context with no ambient authority (Apache-2.0).
    implementation(project(":extension-api"))
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
}
