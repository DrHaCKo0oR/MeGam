import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

val v = { name: String -> providers.gradleProperty(name).get() }

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:${v("serializationVersion")}")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:${v("coroutinesVersion")}")
    api("com.squareup.okhttp3:okhttp:${v("okhttpVersion")}")
    api("com.anthropic:anthropic-java:${v("anthropicVersion")}")
    implementation("org.jsoup:jsoup:${v("jsoupVersion")}")

    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${v("kotlinVersion")}")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:${v("okhttpVersion")}")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:${v("coroutinesVersion")}")
}

tasks.test {
    useJUnit()
    // Live tests hit the real listing sites; CI enables them explicitly.
    systemProperty("live", System.getProperty("live") ?: System.getenv("SHAQATI_LIVE") ?: "false")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStandardStreams = System.getenv("CI") != null
    }
}
