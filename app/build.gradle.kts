import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Named to avoid shadowing the `android { ndkVersion = ... }` extension property, which
// would otherwise win name resolution inside the android block.
val fxsoundNdkVersion = "27.2.12479018"

android {
    namespace = "com.fxsound.audio"
    compileSdk = 35
    ndkVersion = fxsoundNdkVersion

    defaultConfig {
        applicationId = "com.fxsound.audio"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
        }

        externalNativeBuild {
            cmake {
                cppFlags.addAll(listOf("-std=c++17", "-O3", "-ffast-math"))
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation("junit:junit:4.13.2")
    debugImplementation(libs.androidx.compose.ui.tooling)
}

tasks.withType<Test>().configureEach {
    // The unit tests read the measured DSP response from the repository's docs folder, so
    // they run with the repository root as the working directory.
    workingDir = rootProject.projectDir
}


/**
 * Builds a native DSP executable with the NDK and runs it on the connected device.
 *
 * The native executables are Android binaries: no host C++ toolchain is available in this
 * environment, so they are cross-compiled and executed over adb. When no device is
 * connected this throws rather than reporting a result it did not observe
 * (AGENTS.md rule 14).
 *
 * @param toolLabel how the tool is named in messages
 * @param cmakeTarget the CMake target to build
 * @param binaryName the executable produced by that target
 * @param outputFile when set, the tool's stdout is written here as well
 * @return the tool's exit code
 */
fun runNativeDspTool(
    project: Project,
    toolLabel: String,
    cmakeTarget: String,
    binaryName: String,
    outputFile: File?
): Int {
    fun requireFile(path: File, what: String) {
        if (!path.exists()) {
            throw GradleException("$toolLabel: $what not found at ${path.absolutePath}")
        }
    }

    fun run(vararg command: String): Pair<Int, String> {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val result = project.exec {
            commandLine(*command)
            isIgnoreExitValue = true
            standardOutput = stdout
            errorOutput = stderr
        }
        return result.exitValue to (stdout.toString() + stderr.toString())
    }

    fun runOrFail(vararg command: String) {
        val (exitCode, output) = run(*command)
        if (exitCode != 0) {
            throw GradleException(
                "$toolLabel: command failed (exit $exitCode): ${command.joinToString(" ")}\n$output"
            )
        }
        if (output.isNotBlank()) {
            project.logger.lifecycle(output.trim())
        }
    }

    val cmakeProjectDir = project.layout.projectDirectory.dir("src/test-native").asFile
    val nativeBuildDir = project.layout.buildDirectory.dir("native-test").get().asFile

    // sdk.dir in local.properties is escaped the way a Java properties file escapes
    // a literal ':' and '\', so it has to be decoded before it can be used as a path.
    val localPropertiesFile = project.rootProject.file("local.properties")
    requireFile(localPropertiesFile, "local.properties")
    val localProperties = Properties().apply {
        localPropertiesFile.inputStream().use { load(it) }
    }
    val sdkPath: String = localProperties.getProperty("sdk.dir")
        ?.replace("\\\\", "\\")
        ?.replace("\\:", ":")
        ?: throw GradleException("$toolLabel: local.properties does not define sdk.dir")

    requireFile(File(sdkPath), "Android SDK directory (sdk.dir from local.properties)")

    fun tool(relativePath: String): File {
        val plain = File(sdkPath, relativePath)
        return if (plain.exists()) plain else File(sdkPath, "$relativePath.exe")
    }

    val adb = tool("platform-tools/adb")
    val cmake = tool("cmake/3.22.1/bin/cmake")
    val ninja = tool("cmake/3.22.1/bin/ninja")
    requireFile(adb, "adb")
    requireFile(cmake, "cmake")
    requireFile(ninja, "ninja")

    val toolchain = File(sdkPath, "ndk/${fxsoundNdkVersion}/build/cmake/android.toolchain.cmake")
    requireFile(toolchain, "NDK ${fxsoundNdkVersion} CMake toolchain file")

    val (adbExit, devicesOutput) = run(adb.absolutePath, "devices")
    if (adbExit != 0) {
        throw GradleException("$toolLabel: adb devices failed:\n$devicesOutput")
    }

    val deviceSerials = devicesOutput
        .lines()
        .drop(1)
        .map { it.trim() }
        .filter { it.isNotEmpty() && it.endsWith("\tdevice") }
        .map { it.substringBefore('\t') }

    if (deviceSerials.isEmpty()) {
        throw GradleException(
            "$toolLabel: no device is connected. The native DSP tools are Android executables " +
                "and cannot run on the host. Connect the target device with USB debugging " +
                "enabled, then re-run. Device list was:\n$devicesOutput"
        )
    }
    val serial = deviceSerials.first()
    project.logger.lifecycle("$toolLabel: using device $serial")

    val (_, abiOutput) = run(adb.absolutePath, "-s", serial, "shell", "getprop", "ro.product.cpu.abi")
    val abi = abiOutput.trim()
    if (abi.isEmpty()) {
        throw GradleException("$toolLabel: could not determine the device ABI")
    }
    project.logger.lifecycle("$toolLabel: device ABI is $abi")

    runOrFail(
        cmake.absolutePath,
        "-S", cmakeProjectDir.absolutePath,
        "-B", nativeBuildDir.absolutePath,
        "-G", "Ninja",
        "-DCMAKE_TOOLCHAIN_FILE=${toolchain.absolutePath}",
        "-DCMAKE_MAKE_PROGRAM=${ninja.absolutePath}",
        "-DANDROID_ABI=$abi",
        "-DANDROID_PLATFORM=android-29",
        "-DCMAKE_BUILD_TYPE=Release"
    )

    runOrFail(
        cmake.absolutePath,
        "--build", nativeBuildDir.absolutePath,
        "--target", cmakeTarget
    )

    val binary = File(nativeBuildDir, "dsp/$binaryName")
    requireFile(binary, "$binaryName binary")

    val remotePath = "/data/local/tmp/$binaryName"
    runOrFail(adb.absolutePath, "-s", serial, "push", binary.absolutePath, remotePath)
    run(adb.absolutePath, "-s", serial, "shell", "chmod", "755", remotePath)

    project.logger.lifecycle("$toolLabel: running on device...")
    val (toolExit, toolOutput) = run(adb.absolutePath, "-s", serial, "shell", remotePath)

    if (outputFile != null) {
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(toolOutput)
        project.logger.lifecycle("$toolLabel: wrote ${outputFile.absolutePath}")
    }

    if (toolOutput.isNotBlank()) {
        project.logger.lifecycle(toolOutput.trim())
    }
    return toolExit
}

/**
 * Builds and runs the native FxSound DSP numerical test suite on a connected device.
 *
 * The native suite is an Android executable: no host C++ toolchain is available in this
 * environment, so it is built with the NDK and executed on a device over adb. The task
 * fails loudly when no device is connected rather than reporting a pass it did not
 * observe (AGENTS.md rule 14).
 *
 * See app/src/test-native/ for the CMake project.
 */
tasks.register("nativeTest") {
    group = "verification"
    description = "Builds and runs the native FxSound DSP numerical test suite on a connected device."

    doLast {
        val exitCode = runNativeDspTool(
            project = project,
            toolLabel = "nativeTest",
            cmakeTarget = "fxsound_dsp_test",
            binaryName = "fxsound_dsp_test",
            outputFile = null
        )
        if (exitCode != 0) {
            throw GradleException("nativeTest: native DSP test suite failed (exit code $exitCode)")
        }
        logger.lifecycle("nativeTest: all native DSP tests passed")
    }
}

/**
 * Measures the upstream FxSound DSP's magnitude and level response on a connected device
 * and writes the result to docs/fx_response/.
 *
 * The companion engine that enhances other apps (Spotify and other music players) drives
 * Android's DynamicsProcessing effect. Until now the mapping from the FxSound controls to
 * that effect was hand-authored guesswork. This task produces the measured curves that
 * the mapping is derived from, so the companion engine can be a measured emulation of the
 * upstream DSP rather than an approximation of it.
 */
tasks.register("fxResponseMeasure") {
    group = "verification"
    description = "Measures the native FxSound DSP response and writes it to docs/fx_response/."

    doLast {
        val outputFile = rootProject.file("docs/fx_response/fx_response_measurements.csv")
        val exitCode = runNativeDspTool(
            project = project,
            toolLabel = "fxResponseMeasure",
            cmakeTarget = "fxsound_response_measure",
            binaryName = "fxsound_response_measure",
            outputFile = outputFile
        )
        if (exitCode != 0) {
            throw GradleException("fxResponseMeasure: the measurement tool failed (exit code $exitCode)")
        }
        logger.lifecycle("fxResponseMeasure: measurement complete")
    }
}
