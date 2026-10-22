import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.time.Duration

// :core:ble is the Bluetooth LE link layer of the transport (phase A2). It implements the
// `Transport` of :core:runtime on top of small ports (BleScanner, BleAdvertiser, GattLink, ...)
// so that the whole state machine runs on the JVM with fakes; the `android` package holds the
// thin adapters over the platform APIs. Discovery policy, topology and the foreground service
// are phase A3.
//
// AGP 9: Kotlin is built in, so only the Android library plugin is applied.

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.crisisnetwork.android.core.ble"
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
    // Transport / TransportEvent (and, through :core:runtime, the bindings and coroutines).
    api(project(":core:runtime"))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
