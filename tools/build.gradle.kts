import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Command-line tools run by the workflows (not part of the app).
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

val v = { name: String -> providers.gradleProperty(name).get() }

dependencies {
    implementation(project(":core"))
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:${v("kotlinVersion")}")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("app.shaqati.tools.PhotoCheckKt")
}

tasks.test {
    useJUnit()
}
