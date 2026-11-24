plugins {
    alias(libs.plugins.android.library)
    // Code 120: built-in Kotlin (AGP 9) — no kotlin-android plugin.
}

android {
    namespace = "app.xreader.extensionapi"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Code 120: kotlinOptions removed (built-in Kotlin; jvmTarget
    // defaults to compileOptions.targetCompatibility).
}

dependencies {
    // Deliberately empty: the API artifact must stay tiny and stable so
    // extension packages can link against it (PLAN §1.2).
}
