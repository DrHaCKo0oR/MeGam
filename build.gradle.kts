// Plugins go on the root buildscript classpath so the Kotlin and Android
// plugins share one classloader, and the Android plugin is only resolved when
// an Android SDK is present.
buildscript {
    val localRepo: String? = providers.gradleProperty("localRepo").orNull ?: System.getenv("SHAQATI_LOCAL_REPO")
    val hasAndroidSdk = !System.getenv("ANDROID_HOME").isNullOrBlank() ||
        !System.getenv("ANDROID_SDK_ROOT").isNullOrBlank() ||
        file("local.properties").exists()
    val kotlinVersion = providers.gradleProperty("kotlinVersion").get()
    val agpVersion = providers.gradleProperty("agpVersion").get()

    repositories {
        if (localRepo != null) maven(url = uri(localRepo))
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")
        classpath("org.jetbrains.kotlin:kotlin-serialization:$kotlinVersion")
        if (hasAndroidSdk) {
            classpath("com.android.tools.build:gradle:$agpVersion")
            classpath("org.jetbrains.kotlin:compose-compiler-gradle-plugin:$kotlinVersion")
        }
    }
}
