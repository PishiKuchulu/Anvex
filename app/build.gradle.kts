import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.anvex.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.anvex.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 98
        versionName = "4.14.9"
        vectorDrawables { useSupportLibrary = true }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // v4.10.1 (CI-02): a FIXED debug keystore committed to the repo
            // (keystore/debug.keystore — the standard public Android debug
            // credentials). CI runners used to sign the rolling `latest`
            // debug APK with a fresh per-runner key every time, so it could
            // never be installed over the previous download. When the file
            // is absent the default per-machine debug key is used as before.
            val fixedDebugKs = rootProject.file("keystore/debug.keystore")
            if (fixedDebugKs.exists()) {
                signingConfig = signingConfigs.create("ciDebug") {
                    storeFile = fixedDebugKs
                    storePassword = "android"
                    keyAlias = "androiddebugkey"
                    keyPassword = "android"
                }
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Optional release signing. ANVEX_* is preferred; BOLT_* remains as a
            // compatibility alias for existing local scripts. Never silently fall
            // back to an unsigned artifact when signing inputs were partially supplied.
            val ksPath = (project.findProperty("ANVEX_KEYSTORE") as String?)
                ?: (project.findProperty("BOLT_KEYSTORE") as String?)
            val ksPass = (project.findProperty("ANVEX_KEYSTORE_PASSWORD") as String?)
                ?: (project.findProperty("BOLT_KEYSTORE_PASSWORD") as String?)
            val ksAlias = (project.findProperty("ANVEX_KEY_ALIAS") as String?)
                ?: (project.findProperty("BOLT_KEY_ALIAS") as String?) ?: "anvex"
            val keyPass = (project.findProperty("ANVEX_KEY_PASSWORD") as String?)
                ?: (project.findProperty("BOLT_KEY_PASSWORD") as String?) ?: ksPass
            val signingRequested = ksPath != null || ksPass != null || keyPass != null
            if (signingRequested) {
                require(!ksPath.isNullOrBlank()) { "ANVEX_KEYSTORE must point to a release keystore." }
                require(!ksPass.isNullOrBlank()) { "ANVEX_KEYSTORE_PASSWORD is required when signing is requested." }
                require(!keyPass.isNullOrBlank()) { "ANVEX_KEY_PASSWORD is required when signing is requested." }
                require(file(ksPath).isFile) { "Release keystore does not exist: $ksPath" }
                signingConfig = signingConfigs.create("release") {
                    storeFile = file(ksPath)
                    storePassword = ksPass
                    keyAlias = ksAlias
                    keyPassword = keyPass
                }
            }
        }
    }
    // v4.6: per-architecture APKs alongside the universal one. The bundled
    // libnode.so is ~60MB per ABI, so the 4.5.0 universal APK reached 164MB;
    // each split carries exactly one architecture (~60MB) while the universal
    // APK keeps working for anyone unsure which one they need. AGP assigns
    // the splits distinct version codes automatically.
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
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
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // v4.4.6: BouncyCastle jars ship duplicate multi-release manifests.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(project(":core:android"))
    implementation(project(":core:engine"))
    implementation(project(":core:model"))
    implementation(project(":core:ai"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.core.ktx)
    // Directly used by app sources (were previously leaking transitively via :core:android api()).
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.datastore.preferences)
    // v4.0 audit fix (theme): the *platform* has no Theme.Material3 / DayNight
    // styles (the v3.3.9 themes referenced styles that never existed — the zip
    // could not have compiled). Material Components provides the real
    // Theme.Material3.DayNight.NoActionBar used by the manifest theme.
    implementation(libs.material.components)

    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
}

// ---------------------------------------------------------------------------
// Native smoke gate (desktop-grade upgrade, P0): runs as a finalizer of
// assembleDebug in CI. For every ABI the universal debug APK must contain
// each Anvex native library; every Anvex-built library must carry 16 KB
// LOAD alignment (Android 15+ page size) and export its JNI entry points.
// libnode.so is prebuilt (presence-checked only — its alignment was
// verified when the package was produced). A missing or misaligned library
// fails the build here instead of crashing on a device.
// ---------------------------------------------------------------------------
val verifyNativeLibs = tasks.register("verifyNativeLibs") {
    description = "Smoke-checks bundled native libraries inside the universal debug APK."
    val apkFile = layout.buildDirectory.file("outputs/apk/debug/app-universal-debug.apk")
    inputs.file(apkFile)
    onlyIf { apkFile.get().asFile.isFile }
    doLast {
        val abis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        // Anvex-built libraries: presence + 16K alignment + JNI symbol.
        val ownLibs = mapOf(
            "libanvex-node.so" to "Java_com_anvex_core_android_runtime_node_NodeBridge_startNodeWithArguments",
            "libanvex-pty.so" to "Java_com_anvex_core_android_runtime_pty_PtyBridge_nativePtyVersion",
        )
        fun elfLoadAligns(bytes: ByteArray): List<Long> {
            check(bytes.size > 64 && bytes[0] == 0x7F.toByte() && bytes[1] == 'E'.code.toByte()) { "not an ELF file" }
            val is64 = bytes[4].toInt() == 2
            val littleEndian = bytes[5].toInt() == 1
            check(littleEndian) { "big-endian ELF not expected" }
            fun u16(off: Int) = (bytes[off].toInt() and 0xFF) or ((bytes[off + 1].toInt() and 0xFF) shl 8)
            fun u32(off: Int): Long {
                var v = 0L
                for (i in 0..3) v = v or ((bytes[off + i].toLong() and 0xFF) shl (8 * i))
                return v
            }
            fun u64(off: Int): Long {
                var v = 0L
                for (i in 0..7) v = v or ((bytes[off + i].toLong() and 0xFF) shl (8 * i))
                return v
            }
            val phOff: Long
            val phEntSize: Int
            val phNum: Int
            if (is64) {
                phOff = u64(32); phEntSize = u16(54); phNum = u16(56)
            } else {
                phOff = u32(28); phEntSize = u16(42); phNum = u16(44)
            }
            val aligns = mutableListOf<Long>()
            for (i in 0 until phNum) {
                val base = (phOff + i * phEntSize).toInt()
                if (u32(base) == 1L) { // PT_LOAD
                    // p_align offset: ELF64 phdr has it at +48; ELF32 at +28
                    // (type+0, offset+4, vaddr+8, paddr+12, filesz+16,
                    // memsz+20, flags+24, align+28). Reading +24 on ELF32
                    // yields p_flags (5/6) — the CI round-3 false failure.
                    aligns += if (is64) u64(base + 48) else u32(base + 28)
                }
            }
            return aligns
        }
        ZipFile(apkFile.get().asFile).use { zip ->
            for (abi in abis) {
                val nodeEntry = zip.getEntry("lib/$abi/libnode.so")
                check(nodeEntry != null) { "libnode.so missing for $abi in universal APK" }
                for ((lib, symbol) in ownLibs) {
                    val entry = zip.getEntry("lib/$abi/$lib")
                    check(entry != null) { "$lib missing for $abi in universal APK" }
                    val bytes = zip.getInputStream(entry).readBytes()
                    val aligns = elfLoadAligns(bytes)
                    check(aligns.isNotEmpty() && aligns.all { it >= 0x4000L }) {
                        "$lib ($abi) LOAD alignments $aligns are below 16 KB"
                    }
                    val text = String(bytes, Charsets.ISO_8859_1)
                    check(text.contains(symbol)) { "$lib ($abi) does not export $symbol" }
                }
            }
        }
        println("verifyNativeLibs: all native libraries present, 16K-aligned, JNI symbols exported.")
    }
}
// AGP registers the assemble tasks in its own afterEvaluate, so the
// finalizer wiring must be deferred the same way — a top-level
// tasks.named("assembleDebug") here fails script evaluation with
// "Task with name 'assembleDebug' not found" (CI round 2 of P0).
afterEvaluate {
    tasks.named("assembleDebug") { finalizedBy(verifyNativeLibs) }
}
