import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

// :core:ffi builds the Rust core (crate `ffi`, library `crisis_core`) for Android and
// exposes the UniFFI-generated Kotlin API. Two generated artefacts feed this module:
//   * build/generated/jniLibs/<abi>/libcrisis_core.so   (cargo-ndk)
//   * build/generated/uniffi/kotlin/uniffi/crisis_core/  (scripts/gen-bindings)
// Neither is committed; `preBuild` produces them.
//
// AGP 9: Kotlin is built in, so only the Android library plugin is applied here.

plugins {
    alias(libs.plugins.android.library)
}

val repoRoot: File = rootProject.projectDir.resolve("../..").canonicalFile
val rustProfile: String = providers.gradleProperty("crisis.rustProfile").orElse(providers.gradleProperty("crisisRustProfile")).getOrElse("release")
val withSqlcipher: Boolean = providers.gradleProperty("crisis.sqlcipher").orElse(providers.gradleProperty("crisisSqlcipher")).map(String::toBoolean).getOrElse(false)
val skipRust: Boolean = providers.gradleProperty("crisis.skipRust").orElse(providers.gradleProperty("crisisSkipRust")).map(String::toBoolean).getOrElse(false)

// All three ABIs are built: arm64-v8a (phones), armeabi-v7a (old phones), x86_64 (emulator).
val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val rustTriples = listOf("aarch64-linux-android", "armv7-linux-androideabi", "x86_64-linux-android")
// ABIs subject to the Google Play 16 KB page-size requirement (64-bit only).
val abisNeeding16kAlignment = listOf("arm64-v8a", "x86_64")
val pinnedNdkVersion = "28.2.13676358" // r28c: AGP 9.4's default NDK, 16 KB aligned by default

val generatedKotlinDir: File = layout.buildDirectory.dir("generated/uniffi/kotlin").get().asFile
val generatedJniLibsDir: File = layout.buildDirectory.dir("generated/jniLibs").get().asFile

android {
    namespace = "org.crisisnetwork.android.core.ffi"
    compileSdk = 37
    ndkVersion = pinnedNdkVersion

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

dependencies {
    api("${libs.jna.get()}@aar")
    api(libs.kotlinx.coroutines.android)
}

// ---- Rust inputs ---------------------------------------------------------------------

val rustSources = fileTree(repoRoot) {
    include("Cargo.toml", "Cargo.lock", "core/crates/**/*.rs", "core/crates/**/Cargo.toml")
    exclude("**/target/**", "**/build/**", "**/.git/**", "**/.gradle/**", "**/node_modules/**")
}

val isWindows = System.getProperty("os.name").lowercase().contains("windows")

/** Runs a command and returns (exit code, output), or `null` when it cannot be started. */
fun probe(vararg command: String): Pair<Int, String>? =
    try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText().trim()
        process.waitFor() to output
    } catch (e: IOException) {
        null
    }

// ---- Toolchain check: clear messages instead of a cryptic Exec failure --------------------------

val checkRustToolchain = tasks.register("checkRustToolchain") {
    group = "crisis"
    description = "Verifies that cargo, cargo-ndk and the Android Rust targets are installed."
    onlyIf { !skipRust }
    doLast {
        val problems = mutableListOf<String>()
        if (probe("cargo", "--version") == null) {
            problems += "cargo was not found on the PATH of the Gradle process. Install Rust (rustup) and " +
                "restart Android Studio / the Gradle daemon so that ~/.cargo/bin is on the PATH."
        } else {
            val ndk = probe("cargo", "ndk", "--version")
            if (ndk == null || ndk.first != 0) {
                problems += "cargo-ndk is not installed. Run: cargo install cargo-ndk --locked"
            }
            val installed = probe("rustup", "target", "list", "--installed")
            if (installed != null && installed.first == 0) {
                val have = installed.second.lines().map { it.trim() }
                val missing = rustTriples.filter { it !in have }
                if (missing.isNotEmpty()) {
                    problems += "Rust targets missing. Run: rustup target add ${missing.joinToString(" ")}"
                }
            }
        }
        check(problems.isEmpty()) {
            "The Rust/Android toolchain is incomplete:\n - " + problems.joinToString("\n - ")
        }
    }
}

// ---- UniFFI Kotlin bindings ------------------------------------------------------------

val generateKotlinBindings = tasks.register<Exec>("generateKotlinBindings") {
    group = "crisis"
    description = "Generates the Kotlin bindings of the core into <repo>/target/bindings/kotlin."
    dependsOn(checkRustToolchain)
    workingDir = repoRoot
    if (isWindows) {
        commandLine(
            "powershell", "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-File", "scripts/gen-bindings.ps1", "-Language", "kotlin",
        )
    } else {
        commandLine("bash", "scripts/gen-bindings.sh", "kotlin")
    }
    inputs.files(rustSources)
    outputs.dir(repoRoot.resolve("target/bindings/kotlin"))
    onlyIf { !skipRust }
}

val syncKotlinBindings = tasks.register<Sync>("syncKotlinBindings") {
    group = "crisis"
    description = "Copies the generated Kotlin bindings into this module's generated sources."
    dependsOn(generateKotlinBindings)
    from(repoRoot.resolve("target/bindings/kotlin"))
    into(generatedKotlinDir)
}

// ---- Native libraries (cargo-ndk) ---------------------------------------------------------

val androidComponents = extensions.getByType<LibraryAndroidComponentsExtension>()

val buildRustLibs = tasks.register<Exec>("buildRustLibs") {
    group = "crisis"
    description = "Builds libcrisis_core.so for ${abis.joinToString()} with cargo-ndk."
    dependsOn(checkRustToolchain)
    // Both cargo invocations share the repository's `target` directory: never run them at once.
    mustRunAfter(generateKotlinBindings)
    workingDir = repoRoot

    val cargoArgs = mutableListOf("ndk")
    abis.forEach { cargoArgs += listOf("-t", it) }
    cargoArgs += listOf("-o", generatedJniLibsDir.absolutePath, "build", "-p", "ffi")
    if (rustProfile == "release") cargoArgs += "--release"
    if (withSqlcipher) cargoArgs += listOf("--features", "sqlcipher")
    commandLine(listOf("cargo") + cargoArgs)

    inputs.files(rustSources)
    inputs.property("rustProfile", rustProfile)
    inputs.property("sqlcipher", withSqlcipher)
    inputs.property("ndkVersion", pinnedNdkVersion)
    outputs.dir(generatedJniLibsDir)
    onlyIf { !skipRust }

    doFirst {
        // cargo-ndk finds the toolchain through ANDROID_NDK_HOME; honour an existing value,
        // otherwise use the NDK that Gradle resolved for `ndkVersion`.
        if (System.getenv("ANDROID_NDK_HOME").isNullOrBlank()) {
            environment("ANDROID_NDK_HOME", androidComponents.sdkComponents.ndkDirectory.get().asFile.absolutePath)
        }
        // Google Play (Android 15+ targets) requires 16 KB page alignment for 64-bit libraries.
        // NDK r28 does this by default; the flag keeps it true for other NDK versions.
        val existing = System.getenv("RUSTFLAGS").orEmpty()
        environment("RUSTFLAGS", "$existing -C link-arg=-Wl,-z,max-page-size=16384".trim())
    }
}

// ---- Guard: 16 KB ELF alignment ----------------------------------------------------------------

/** Smallest `p_align` among the `PT_LOAD` segments of a little-endian ELF file. */
fun minLoadAlignment(file: File): Long {
    RandomAccessFile(file, "r").use { raf ->
        val header = ByteArray(64)
        raf.readFully(header)
        require(
            header[0] == 0x7f.toByte() && header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() && header[3] == 'F'.code.toByte(),
        ) { "not an ELF file: $file" }
        val is64 = header[4].toInt() == 2
        val head = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val phOffset = if (is64) head.getLong(0x20) else head.getInt(0x1C).toLong() and 0xffffffffL
        val phEntrySize = head.getShort(if (is64) 0x36 else 0x2A).toInt() and 0xffff
        val phCount = head.getShort(if (is64) 0x38 else 0x2C).toInt() and 0xffff
        var smallest = Long.MAX_VALUE
        for (index in 0 until phCount) {
            val entry = ByteArray(phEntrySize)
            raf.seek(phOffset + index.toLong() * phEntrySize)
            raf.readFully(entry)
            val buffer = ByteBuffer.wrap(entry).order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.getInt(0) != 1) continue // not PT_LOAD
            val align = if (is64) buffer.getLong(0x30) else buffer.getInt(0x1C).toLong() and 0xffffffffL
            smallest = minOf(smallest, align)
        }
        return if (smallest == Long.MAX_VALUE) 0 else smallest
    }
}

val verifyElfAlignment = tasks.register("verifyElfAlignment") {
    group = "crisis"
    description = "Fails the build when a 64-bit libcrisis_core.so is not 16 KB page aligned."
    dependsOn(buildRustLibs)
    onlyIf { !skipRust }
    doLast {
        val problems = mutableListOf<String>()
        for (abi in abisNeeding16kAlignment) {
            val library = generatedJniLibsDir.resolve("$abi/libcrisis_core.so")
            if (!library.isFile) {
                problems += "$abi: $library was not produced"
                continue
            }
            val alignment = minLoadAlignment(library)
            if (alignment < 16384) problems += "$abi: LOAD segments aligned to $alignment bytes, need 16384"
        }
        check(problems.isEmpty()) { "16 KB alignment check failed:\n" + problems.joinToString("\n") }
    }
}

// ---- Wire the generated files into the module -----------------------------------------------------
// AGP 9 uses the new DSL: `android.sourceSets` cannot be used from build scripts any more, the
// Variant API adds the generated directories instead. They are plain static directories, so
// the tasks that read them get an explicit dependency on the tasks that fill them.

androidComponents.onVariants { variant ->
    variant.sources.kotlin?.addStaticSourceDirectory(generatedKotlinDir.absolutePath)
    variant.sources.jniLibs?.addStaticSourceDirectory(generatedJniLibsDir.absolutePath)
}

tasks.matching { it.name.startsWith("compile") && it.name.endsWith("Kotlin") }.configureEach {
    dependsOn(syncKotlinBindings)
}

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(verifyElfAlignment)
}

tasks.named("preBuild") {
    dependsOn(syncKotlinBindings, verifyElfAlignment)
}
