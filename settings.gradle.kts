pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack: Injekt (com.github.mihonapp:injekt), required by :mihon-api —
        // Keiyoushi extensions resolve uy.kohesive.injekt from the host classpath.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "Xreader"
include(":app")
include(":extension-api")
include(":mihon-api")
