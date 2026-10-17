import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val v = { name: String -> providers.gradleProperty(name).get() }
val ciBuild = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "app.shaqati"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.shaqati"
        minSdk = 26
        targetSdk = 36
        versionCode = 100 + ciBuild
        versionName = "1.0.$ciBuild"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // One stable key so every new APK installs over the previous one.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("personal") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "/META-INF/INDEX.LIST",
                "/META-INF/*.version",
                "/META-INF/versions/**",
            )
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
        animationsDisabled = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    // On-device tests reuse the recorded site responses from the core tests.
    sourceSets["androidTest"].assets.srcDir("../core/src/test/resources")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))

    val composeBom = platform("androidx.compose:compose-bom:${v("composeBomVersion")}")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:${v("coreKtxVersion")}")
    implementation("androidx.appcompat:appcompat:${v("appcompatVersion")}")
    implementation("androidx.activity:activity-compose:${v("activityVersion")}")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:${v("lifecycleVersion")}")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:${v("lifecycleVersion")}")
    implementation("androidx.lifecycle:lifecycle-process:${v("lifecycleVersion")}")
    implementation("androidx.navigation:navigation-compose:${v("navigationVersion")}")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.work:work-runtime-ktx:${v("workVersion")}")
    implementation("androidx.datastore:datastore-preferences:${v("datastoreVersion")}")
    implementation("androidx.webkit:webkit:${v("webkitVersion")}")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("io.coil-kt.coil3:coil-compose:${v("coilVersion")}")
    implementation("io.coil-kt.coil3:coil-network-okhttp:${v("coilVersion")}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${v("coroutinesVersion")}")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:${v("desugarVersion")}")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:${v("robolectricVersion")}")
    testImplementation("androidx.test:core:${v("androidxTestCoreVersion")}")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${v("coroutinesVersion")}")
    testImplementation("com.squareup.okhttp3:mockwebserver:${v("okhttpVersion")}")

    androidTestImplementation("androidx.test.ext:junit:${v("androidxJunitVersion")}")
    androidTestImplementation("androidx.test:runner:${v("androidxRunnerVersion")}")
    androidTestImplementation("androidx.test:rules:${v("androidxRulesVersion")}")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:${v("uiautomatorVersion")}")
}

// Resolves every classpath so CI can publish a complete offline dependency bundle.
tasks.register("resolveAll") {
    notCompatibleWithConfigurationCache("resolves configurations at execution time")
    doLast {
        configurations.filter { it.isCanBeResolved }.forEach { c ->
            runCatching { c.resolve() }.onFailure { logger.info("skip ${c.name}: ${it.message}") }
        }
    }
}
