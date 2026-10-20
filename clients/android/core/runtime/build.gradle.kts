import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.time.Duration

// :core:runtime is the Android-side runtime of the Rust core (phase A1): the single-threaded
// node actor, the clock policy, the Keystore-wrapped secrets and the Transport abstraction with
// an in-memory loopback. It has no UI and no Bluetooth code.
//
// AGP 9: Kotlin is built in, so only the Android library plugin is applied.

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.crisisnetwork.android.core.runtime"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

// A stuck test must fail the build (and show which test it was), not run until the CI limit.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    timeout.set(Duration.ofMinutes(10))
    testLogging {
        events("started", "passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

dependencies {
    // The UniFFI bindings and the native library (and, through it, JNA and coroutines).
    api(project(":core:ffi"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
