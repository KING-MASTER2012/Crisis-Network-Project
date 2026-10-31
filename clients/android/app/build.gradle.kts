import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP 9 compiles Kotlin itself (built-in Kotlin): do not apply org.jetbrains.kotlin.android.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Same switch as in :core:ffi; the instrumented test then asserts that SQLCipher is really active.
val withSqlcipher: Boolean = providers.gradleProperty("crisisSqlcipher").orElse(providers.gradleProperty("crisis.sqlcipher")).map(String::toBoolean).getOrElse(false)

// ---- Release (phase A7) ---------------------------------------------------------------------
// Version and signing come from Gradle properties or the environment, never from the repository
// (see clients/android/RELEASING.md). Without all four signing values the release build is unsigned.
fun optionalValue(property: String, environment: String): String? =
    providers.gradleProperty(property).orElse(providers.environmentVariable(environment)).orNull

val appVersionCode: Int = providers.gradleProperty("crisis.versionCode").map(String::toInt).getOrElse(1)
val appVersionName: String = providers.gradleProperty("crisis.versionName").getOrElse("0.1.0")

val signingStoreFile: String? = optionalValue("crisis.signing.storeFile", "CRISIS_SIGNING_STORE_FILE")
val signingStorePassword: String? = optionalValue("crisis.signing.storePassword", "CRISIS_SIGNING_STORE_PASSWORD")
val signingKeyAlias: String? = optionalValue("crisis.signing.keyAlias", "CRISIS_SIGNING_KEY_ALIAS")
val signingKeyPassword: String? = optionalValue("crisis.signing.keyPassword", "CRISIS_SIGNING_KEY_PASSWORD")
val releaseSigningComplete: Boolean = listOf(signingStoreFile, signingStorePassword, signingKeyAlias, signingKeyPassword).all { !it.isNullOrBlank() }

// CI builds the release variant only to check R8 and the release lint (with the fast `dev` native core and no
// SQLCipher). Such a build must never be shipped, so a real release refuses to start without the settings below.
val releaseCheckOnly: Boolean = providers.gradleProperty("crisis.releaseCheckOnly").map(String::toBoolean).getOrElse(false)
val nativeProfile: String = providers.gradleProperty("crisisRustProfile").orElse(providers.gradleProperty("crisis.rustProfile")).getOrElse("release")

gradle.taskGraph.whenReady {
    val shipping = hasTask("${project.path}:assembleRelease") || hasTask("${project.path}:bundleRelease")
    if (shipping && !releaseCheckOnly) {
        // The app opens its database with EncryptionPolicy.Required in release builds: a core without SQLCipher cannot start.
        check(withSqlcipher) { "A release needs the encrypted database: build with -Pcrisis.sqlcipher=true (or -Pcrisis.releaseCheckOnly=true for a build that is only checked)." }
        check(nativeProfile == "release") { "A release needs the optimised native core: build with -Pcrisis.rustProfile=release (or -Pcrisis.releaseCheckOnly=true)." }
        check(releaseSigningComplete) { "A release needs the signing values CRISIS_SIGNING_STORE_FILE, _STORE_PASSWORD, _KEY_ALIAS and _KEY_PASSWORD (see RELEASING.md)." }
    }
}

android {
    namespace = "org.crisisnetwork.android"
    // androidx.core 1.19 and newer libraries require compiling against API 37. targetSdk stays at
    // 36 on purpose: it decides runtime behaviour changes and is raised deliberately later.
    compileSdk = 37

    defaultConfig {
        applicationId = "org.crisisnetwork.android"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["expectEncryption"] = withSqlcipher.toString()
    }

    signingConfigs {
        if (releaseSigningComplete) {
            create("release") {
                storeFile = file(signingStoreFile!!)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (releaseSigningComplete) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Lets testers switch the phone to the pseudo-locales en-XA (long, accented text) and ar-XB (right to left).
            isPseudoLocalesEnabled = true
        }
    }

    // Only the languages that have a translation: the libraries bring dozens of others.
    androidResources {
        localeFilters += listOf("en", "tr")
    }

    packaging {
        jniLibs {
            // Native libraries stay uncompressed and page-aligned in the APK (16 KB devices; CI checks it with zipalign).
            useLegacyPackaging = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core:ffi"))
    implementation(project(":core:runtime"))
    implementation(project(":core:ble"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)

    // Phase A5: reading and drawing the QR code of a contact card.
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // Phase A7: installs the baseline profile (this app's and the libraries') also when the APK is not installed from Play.
    implementation(libs.androidx.profileinstaller)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
