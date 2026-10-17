// Optional local Maven repository (a dependency bundle) for sandboxes without
// reliable access to Maven Central: -PlocalRepo=file:///path or SHAQATI_LOCAL_REPO.
val localRepo: String? = providers.gradleProperty("localRepo").orNull ?: System.getenv("SHAQATI_LOCAL_REPO")

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (localRepo != null) maven(url = uri(localRepo))
        google()
        mavenCentral()
    }
}

rootProject.name = "Shaqati"

include(":core")
include(":tools")

// The Android app needs an Android SDK; the core engine builds anywhere.
val hasAndroidSdk = !System.getenv("ANDROID_HOME").isNullOrBlank() ||
    !System.getenv("ANDROID_SDK_ROOT").isNullOrBlank() ||
    file("local.properties").exists()
if (hasAndroidSdk) include(":app")
