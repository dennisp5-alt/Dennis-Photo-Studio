import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The Rust half of the app: this Gradle project is the android/ folder of the `lightcraft-android`
// crate (apps/lightcraft-android), which sits in the LightCraft cargo workspace two folders up.
val cargoWorkspaceDir: File = rootDir.parentFile.parentFile.parentFile
val rustAbis: List<String> =
    providers.gradleProperty("lightcraft.abis").getOrElse("arm64-v8a,x86_64").split(',').map { it.trim() }.filter { it.isNotEmpty() }
val minApi = 29

// The app's version: gradle.properties' lightcraft.version, or the version tag's when the release
// workflow builds a release. The version code follows from it (0.0.1-beta → 101, 0.0.1-beta.2 →
// 102, 0.0.1 → 199, 0.1.0-beta → 10001), so a pre-release comes before its release and every
// release before the next version's pre-releases; times ten plus the ABI of a one-ABI build
// (armeabi-v7a 1, arm64-v8a 2, x86_64 3; 0 for several), so each APK of a release has its own code
// (F-Droid needs that) and a phone that runs both takes the 64-bit one.
val appVersion: String = providers.gradleProperty("lightcraft.version").get()
val appVersionCode: Int = run {
    val m = requireNotNull(Regex("""(\d+)\.(\d+)\.(\d+)(-[0-9A-Za-z-]+?(\.(\d+))?)?""").matchEntire(appVersion)) {
        "lightcraft.version $appVersion is not a version like 1.2.3, 1.2.3-beta or 1.2.3-beta.4"
    }
    val (major, minor, patch) = m.groupValues.subList(1, 4).map { it.toInt() }
    val pre = if (m.groupValues[4].isEmpty()) null else m.groupValues[6].toIntOrNull() ?: 1
    require(minor < 100 && patch < 100 && (pre ?: 1) in 1..98) {
        "lightcraft.version $appVersion: minor and patch must be under 100, and a pre-release's number 1 to 98"
    }
    val base = ((major * 100 + minor) * 100 + patch) * 100 + (pre ?: 99)
    providers.gradleProperty("lightcraft.versionCode").orNull?.let { listed ->
        require(listed.trim().toIntOrNull() == base) {
            "gradle.properties: lightcraft.versionCode=$listed, but version $appVersion's is $base (set both before tagging)"
        }
    }
    val abi = when (rustAbis.singleOrNull()) {
        "armeabi-v7a" -> 1
        "arm64-v8a" -> 2
        "x86_64" -> 3
        else -> 0
    }
    base * 10 + abi
}

// The release key, when the build is given one (the release workflow passes it from the repository's
// secrets). Without one a release build is signed with the debug key: it installs, but it can't
// update a published release; or, with -Plightcraft.unsigned=true (F-Droid's build), not signed at
// all, so the published APK's signature can be checked against it.
val releaseKeystore: String? = providers.environmentVariable("LIGHTCRAFT_KEYSTORE").orNull
val unsignedRelease: Boolean = providers.gradleProperty("lightcraft.unsigned").orNull == "true"

/** rustup's `cargo` (Android Studio may not see rustup's PATH), else `cargo` from PATH; `CARGO` overrides. */
val cargoExecutable: String = run {
    val exe = if (System.getProperty("os.name").lowercase().startsWith("windows")) "cargo.exe" else "cargo"
    val rustup = File(System.getProperty("user.home"), ".cargo/bin/$exe")
    providers.environmentVariable("CARGO").orNull ?: if (rustup.isFile) rustup.absolutePath else exe
}

/** Builds `liblightcraft_android.so` (release profile) for each ABI with cargo-ndk. */
abstract class CargoNdkBuild : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Input abstract val cargo: Property<String>
    @get:Input abstract val workspaceDir: Property<String>
    @get:Input abstract val ndkDir: Property<String>
    @get:Input abstract val abis: ListProperty<String>
    @get:Input abstract val apiLevel: Property<Int>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun build() {
        val targets = abis.get().flatMap { listOf("-t", it) }
        val out = outputDir.get().asFile.absolutePath
        // Reproducible: the library mustn't hold the paths it was built in (panic locations name
        // source files), so the same commit builds byte-identical here, on CI and on F-Droid's
        // build server. Encoded flags (0x1f-separated) survive spaces in the paths.
        val cargoHome = System.getenv("CARGO_HOME")?.takeIf { it.isNotBlank() } ?: File(System.getProperty("user.home"), ".cargo").absolutePath
        val remap = listOf(
            "--remap-path-prefix=${File(workspaceDir.get()).absolutePath}=/lightcraft",
            "--remap-path-prefix=$cargoHome=/cargo",
        )
        execOperations.exec {
            workingDir(workspaceDir.get())
            environment("ANDROID_NDK_HOME", ndkDir.get())
            environment("CARGO_ENCODED_RUSTFLAGS", remap.joinToString("\u001f"))
            commandLine(
                listOf(cargo.get(), "ndk") + targets +
                    listOf("--platform", apiLevel.get().toString(), "-o", out, "build", "--release", "-p", "lightcraft-android", "--lib"),
            )
        }
    }
}

/** Generates the Kotlin bindings from the library cargo built (UniFFI library mode). */
abstract class UniffiBindgen : DefaultTask() {
    @get:Inject abstract val execOperations: ExecOperations
    @get:Input abstract val cargo: Property<String>
    @get:Input abstract val workspaceDir: Property<String>
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val library: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        execOperations.exec {
            workingDir(workspaceDir.get())
            commandLine(
                cargo.get(), "run", "--quiet", "-p", "lightcraft-android-bindgen", "--",
                "generate", library.get().asFile.absolutePath, "--language", "kotlin", "--out-dir", out.absolutePath, "--no-format",
            )
        }
    }
}

/**
 * Files from LightCraft's `assets/` the app ships, copied at build time rather than duplicated:
 * its app icon, its UI font Inter (OFL) and the font's licence. [targets] maps each file name to
 * its path in the generated resource (or asset) folder.
 */
abstract class LightcraftAssets : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NAME_ONLY) abstract val files: ConfigurableFileCollection
    @get:Input abstract val targets: MapProperty<String, String>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        for (file in files) {
            val target = targets.get()[file.name] ?: continue
            file.copyTo(File(out, target), overwrite = true)
        }
    }
}

android {
    namespace = "ai.storyteller.lightcraft"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.dennis.photostudio.preview.v066"
        minSdk = minApi
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
        ndk { abiFilters.addAll(rustAbis) }
    }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = providers.environmentVariable("LIGHTCRAFT_KEYSTORE_PASSWORD").orNull
                keyAlias = providers.environmentVariable("LIGHTCRAFT_KEY_ALIAS").orNull
                keyPassword = providers.environmentVariable("LIGHTCRAFT_KEY_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (unsignedRelease) null else signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            // reproducible builds: no record of the checkout in the APK
            vcsInfo { include = false }
        }
    }
    // reproducible builds: no dependency metadata signing block (F-Droid's check rejects it)
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

androidComponents {
    onVariants { variant ->
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val cargoBuild = tasks.register<CargoNdkBuild>("cargoBuild$name") {
            group = "rust"
            description = "Builds liblightcraft_android.so for $rustAbis"
            cargo.set(cargoExecutable)
            workspaceDir.set(cargoWorkspaceDir.absolutePath)
            ndkDir.set(androidComponents.sdkComponents.ndkDirectory.map { it.asFile.absolutePath })
            abis.set(rustAbis)
            apiLevel.set(minApi)
            // cargo knows what changed
            outputs.upToDateWhen { false }
        }
        val bindgen = tasks.register<UniffiBindgen>("uniffiBindgen$name") {
            group = "rust"
            description = "Generates the Kotlin bindings of liblightcraft_android.so"
            cargo.set(cargoExecutable)
            workspaceDir.set(cargoWorkspaceDir.absolutePath)
            library.set(cargoBuild.flatMap { it.outputDir.file("${rustAbis.first()}/liblightcraft_android.so") })
        }
        val assetsDir = File(cargoWorkspaceDir, "assets")
        val res = tasks.register<LightcraftAssets>("lightcraftResources$name") {
            val map = mapOf(
                "fonts/Inter-Regular.ttf" to "font/inter_regular.ttf",
                "fonts/Inter-Medium.ttf" to "font/inter_medium.ttf",
                "fonts/Inter-SemiBold.ttf" to "font/inter_semibold.ttf",
            )
            files.from(map.keys.map { File(assetsDir, it) })
            targets.set(map.mapKeys { (k, _) -> k.substringAfterLast('/') })
        }
        val licences = tasks.register<LightcraftAssets>("lightcraftLicences$name") {
            files.from(File(assetsDir, "fonts/OFL-Inter.txt"))
            targets.set(mapOf("OFL-Inter.txt" to "licences/OFL-Inter.txt"))
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(cargoBuild, CargoNdkBuild::outputDir)
        variant.sources.java?.addGeneratedSourceDirectory(bindgen, UniffiBindgen::outputDir)
        variant.sources.res?.addGeneratedSourceDirectory(res, LightcraftAssets::outputDir)
        variant.sources.assets?.addGeneratedSourceDirectory(licences, LightcraftAssets::outputDir)
    }
}

// reproducible builds: no baseline profile in the APK (its bytes vary from one build to the next)
tasks.configureEach {
    if (name.contains("ArtProfile")) enabled = false
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.jna) { artifact { type = "aar" } }
    testImplementation(libs.junit)
}
