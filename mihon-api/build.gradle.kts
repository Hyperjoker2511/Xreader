plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.xreader.mihonapi"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    testOptions {
        // Unit tests touch model types whose signatures mention android.net.Uri
        // and friends; return defaults instead of "not mocked" errors.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Everything here is `api`: Keiyoushi extension APKs link against the
    // eu.kanade.tachiyomi.* classes AND the libraries their signatures expose
    // (OkHttp, coroutines, kotlinx-serialization, Injekt, androidx.preference)
    // from the host app's classpath at runtime.
    api(libs.okhttp)
    api(libs.okhttp.brotli)
    api(libs.okhttp.zstd)
    api(libs.okhttp.dnsoverhttps)
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    api(libs.androidx.preference.ktx)
    api(libs.injekt)
    api(libs.logcat)
    // JavaScriptEngine's real implementation (Komikku transplant, K6):
    // extension signatures never expose QuickJS, but the engine class
    // itself links against it from this module — `api` so the merged
    // runtime classpath carries it exactly once (the app module also
    // depends on it for the facade track's QuickJsRuntime).
    api(libs.quickjs.android)
    // Internal to the transplanted preference framework (SharedPreferences
    // KTX edit); not part of the extension-facing surface.
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
