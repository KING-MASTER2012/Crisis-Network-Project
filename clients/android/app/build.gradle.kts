import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// AGP 9 compiles Kotlin itself (built-in Kotlin): do not apply org.jetbrains.kotlin.android.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Same switch as in :core:ffi; the instrumented test then asserts that SQLCipher is really active.
val withSqlcipher: Boolean = providers.gradleProperty("crisisSqlcipher").orElse(providers.gradleProperty("crisis.sqlcipher")).map(String::toBoolean).getOrElse(false)

android {
    namespace = "org.crisisnetwork.android"
    // androidx.core 1.19 and newer libraries require compiling against API 37. targetSdk stays at
    // 36 on purpose: it decides runtime behaviour changes and is raised deliberately later.
    compileSdk = 37

    defaultConfig {
        applicationId = "org.crisisnetwork.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        testInstrumentationRunnerArguments["expectEncryption"] = withSqlcipher.toString()
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
