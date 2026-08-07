import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Calendar
import javax.inject.Inject

interface InjectedExecOps {
    @get:Inject
    val execOperations: ExecOperations
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "io.github.rhythmcache.dioxamine"
    compileSdk = 37

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (keystorePath != null) {
                storeFile = rootProject.file(keystorePath)
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    defaultConfig {
        applicationId = "io.github.rhythmcache.dioxamine"
        minSdk = 23
        targetSdk = 36
        versionCode = 10001
        versionName = "0.0.2-alpha"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
        val currentYear = Calendar.getInstance().get(Calendar.YEAR).toString()
        buildConfigField("String", "APP_NAME", "\"Dioxamine\"")
        buildConfigField("String", "AUTHOR", "\"rhythmcache\"")
        buildConfigField("String", "COPYRIGHT_YEAR", "\"$currentYear\"")
        buildConfigField("String", "GITHUB_URL", "\"https://github.com/rhythmcache/\"")
        buildConfigField("String", "TELEGRAM_URL", "\"https://t.me/tr1ple_fault\"")
        buildConfigField("String", "SOURCE_CODE_URL", "\"https://github.com/rhythmcache/Dioxamine\"")
    }

    packaging {
        resources {
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE.md"
            excludes += "META-INF/NOTICE.txt"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/*.kotlin_module"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.adb.kt)
    implementation(libs.fastboot.kt)
    implementation(libs.qrose)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

//
// Native / external asset generation
//   - scrcpy-server.jar  <- built from the "scrcpy" git submodule at repo root
//   - pkg-dump.jar       <- PkgDump.java compiled+dexed against Robolectric's
//                           android-all stub jar (resolved via a detached
//                           configuration, kept off the app's real classpath)
//   - dxls-<abi>         <- dxls.c cross-compiled for 4 ABIs via the NDK
// All three are wired to run before preBuild so `./gradlew assembleDebug`
// (or assembleRelease) regenerates them automatically.
//

val androidComponents = extensions.getByType<ApplicationAndroidComponentsExtension>()
val scrcpyDir = rootProject.file("scrcpy")
val assetsDir = layout.projectDirectory.dir("src/main/assets")

// Detached configuration just to resolve the Robolectric jar's path.
// Not added to compileOnly/implementation, so it never touches the app's
// real compile/runtime classpath.
val robolectricJar = configurations.create("robolectricJar")

dependencies {
    robolectricJar(libs.robolectric.android.all)
}

// Build scrcpy-server.jar from the submodule.

val buildScrcpyServer =
    tasks.register<GradleBuild>("buildScrcpyServer") {
        onlyIf { scrcpyDir.exists() }

        dir = scrcpyDir
        tasks = listOf("server:assembleRelease")

        doLast {
            val built =
                scrcpyDir.resolve(
                    "server/build/outputs/apk/release/server-release-unsigned.apk",
                )
            if (!built.exists()) {
                error("Expected built APK not found at $built")
            }

            val dest = assetsDir.file("scrcpy-server.jar").asFile
            dest.parentFile.mkdirs()
            built.copyTo(dest, overwrite = true)
            logger.lifecycle("scrcpy-server.jar -> $dest")
        }
    }

// Compile PkgDump.java -> dex -> pkg-dump.jar
val pkgDumpClassesDir = layout.buildDirectory.dir("pkgdump-classes")
val pkgDumpDexDir = layout.buildDirectory.dir("pkgdump-dex")

val compilePkgDumpJava =
    tasks.register<JavaExec>("compilePkgDumpJava") {
        // We invoke javac directly instead of via JavaCompile task type to keep
        // full control over classpath (Robolectric jar) without it becoming
        // part of the module's normal source set compilation.
        mainClass.set("com.sun.tools.javac.Main")
        classpath = files(org.gradle.internal.jvm.Jvm.current().toolsJar ?: files())

        doFirst {
            val classesDir = pkgDumpClassesDir.get().asFile.apply { mkdirs() }
            val roboJar = robolectricJar.singleFile
            val src = rootProject.file("src_ext/PkgDump.java")

            args =
                listOf(
                    "--release", "17",
                    "-cp", roboJar.absolutePath,
                    "-d", classesDir.absolutePath,
                    src.absolutePath,
                )
        }
    }

val dexPkgDump =
    tasks.register<Exec>("dexPkgDump") {
        dependsOn(compilePkgDumpJava)

        environment("SKIP_JDK_VERSION_CHECK", "1")

        doFirst {
            val classesDir = pkgDumpClassesDir.get().asFile
            val dexOutDir = pkgDumpDexDir.get().asFile.apply { mkdirs() }
            val sdkDir = androidComponents.sdkComponents.sdkDirectory.get().asFile
            val d8Name = if (System.getProperty("os.name").lowercase().contains("win")) "d8.bat" else "d8"
            val d8 =
                sdkDir
                    .resolve("build-tools")
                    .listFiles()
                    ?.filter { it.isDirectory }
                    ?.sortedDescending()
                    ?.map { it.resolve(d8Name) }
                    ?.firstOrNull { it.exists() }
                    ?: error("d8 not found under $sdkDir/build-tools")

            val classFiles =
                classesDir.walkTopDown()
                    .filter { it.isFile && it.extension == "class" }
                    .map { it.absolutePath }
                    .toList()

            commandLine(
                listOf(d8.absolutePath, "--output", dexOutDir.absolutePath, "--min-api", "21") + classFiles,
            )
        }

        doLast {
            val dexOut = pkgDumpDexDir.get().file("classes.dex").asFile
            val dest = assetsDir.file("pkg-dump.jar").asFile
            dest.parentFile.mkdirs()
            dexOut.copyTo(dest, overwrite = true)
            logger.lifecycle("pkg-dump.jar -> $dest")
        }
    }

val buildPkgDumpJar =
    tasks.register("buildPkgDumpJar") {
        dependsOn(dexPkgDump)
    }

// Compile dxls.c for all 4 ABIs using the NDK toolchain
val buildDxlsNative =
    tasks.register("buildDxlsNative") {
        val execOps = project.objects.newInstance<InjectedExecOps>().execOperations

        doLast {
            val ndkPath = android.ndkPath
            val sdkDir = androidComponents.sdkComponents.sdkDirectory.get().asFile

            // OS prefix used both for locating env-provided NDKs (host tag dir name)
            // and as a fallback match if the prebuilt/ listing is ambiguous. We only
            // match on OS family here, never on arch, since custom/vendor NDK builds
            // can use a different arch token than the usual "x86_64".
            val osName = System.getProperty("os.name").lowercase()
            val osPrefix =
                when {
                    osName.contains("win") -> "windows"
                    osName.contains("mac") || osName.contains("darwin") -> "darwin"
                    else -> "linux"
                }

            fun ndkFromSdk(): File? {
                val ndkParent = sdkDir.resolve("ndk")
                return ndkParent.listFiles()?.filter { it.isDirectory }?.sortedDescending()?.firstOrNull()
                    ?: sdkDir.resolve("ndk-bundle").takeIf { it.exists() }
            }

            fun ndkFromEnv(): File? {
                val envVars = listOf("ANDROID_NDK_HOME", "ANDROID_NDK_ROOT", "ANDROID_NDK")
                for (name in envVars) {
                    val value = System.getenv(name)
                    if (!value.isNullOrBlank()) {
                        val dir = File(value)
                        if (dir.exists()) {
                            logger.lifecycle("Using NDK path from env var $name: $dir")
                            return dir
                        } else {
                            logger.warn("Env var $name is set to '$value' but that path does not exist")
                        }
                    }
                }
                return null
            }

            val ndkDir =
                ndkPath?.let { File(it) }
                    ?: ndkFromSdk()
                    ?: ndkFromEnv()
                    ?: error(
                        "Could not locate the Android NDK. Checked android.ndkPath, " +
                            "$sdkDir/ndk (and ndk-bundle), and env vars ANDROID_NDK_HOME/ANDROID_NDK_ROOT/ANDROID_NDK.",
                    )

            val prebuiltDir = ndkDir.resolve("toolchains/llvm/prebuilt")

            // Normal case pick the (usually single) host tag directory under prebuilt/
            // e.g. windows-x86_64, linux-x86_64, darwin-x86_64. If that listing is
            // missing/empty/ambiguous manually construct/match the path using only
            // the OS prefix (windows*/linux*/darwin*), ignoring the arch suffix, since
            // custom NDK builds may not use "x86_64" for the host arch token.
            val hostDirs = prebuiltDir.listFiles()?.filter { it.isDirectory }.orEmpty()
            val toolchainHost =
                hostDirs.firstOrNull { it.name.lowercase().startsWith(osPrefix) }
                    ?: hostDirs.firstOrNull()
                    ?: error(
                        "No LLVM toolchain directory found under $prebuiltDir for OS prefix '$osPrefix'. " +
                            "Verify NDK path: $ndkDir",
                    )
            val toolchainBin = toolchainHost.resolve("bin")

            val src = rootProject.file("src_ext/dxls.c")
            val outDir = assetsDir.dir("dxls").asFile.apply { mkdirs() }

            val targets =
                mapOf(
                    "arm64-v8a" to "aarch64-linux-android21-clang",
                    "armeabi-v7a" to "armv7a-linux-androideabi21-clang",
                    "x86" to "i686-linux-android21-clang",
                    "x86_64" to "x86_64-linux-android21-clang",
                )

            targets.forEach { (arch, clang) ->
                val compiler =
                    listOf(
                        toolchainBin.resolve("$clang.cmd"),
                        toolchainBin.resolve("$clang.exe"),
                        toolchainBin.resolve(clang),
                        toolchainHost.resolve("$clang.cmd"),
                        toolchainHost.resolve("$clang.exe"),
                        toolchainHost.resolve(clang),
                    ).firstOrNull { it.exists() }
                        ?: error("Compiler '$clang' not found under $toolchainHost or $toolchainBin")

                execOps.exec {
                    commandLine(
                        compiler.absolutePath,
                        "-O2",
                        "-fPIE",
                        "-pie",
                        "-o",
                        outDir.resolve("dxls-$arch").absolutePath,
                        src.absolutePath,
                    )
                }
                logger.lifecycle("dxls-$arch built")
            }
        }
    }

tasks.named("preBuild") {
    dependsOn(buildScrcpyServer, buildPkgDumpJar, buildDxlsNative)
}
