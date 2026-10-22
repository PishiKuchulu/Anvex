import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// ---------------------------------------------------------------------------
// Bundled Node.js 24 runtime (real Node.js on-device). Since 4.8.0 the
// libnode package is Anvex's own: Node 24.18.0 (LTS) built for bionic via
// Termux nodejs-lts with --shared, plus its DT_NEEDED dependency closure,
// published as the repo release "libnode-v24.18.0" by the libnode-release /
// libnode-reassemble workflows. The ~105 MB zip is NOT committed to git;
// this task downloads it once, verifies its SHA-256, and extracts bin/ +
// include/ into libnode/ (gitignored). preBuild depends on it, so local
// and CI builds both fetch it deterministically.
val nodeLibsDir = layout.projectDirectory.dir("libnode").asFile
val nodeLibsMarker = File(nodeLibsDir, ".anvex-node-24.18.0.ok")
val downloadNodeLibs = tasks.register("downloadNodeLibs") {
    description = "Downloads and hash-verifies the bundled libnode 24 runtime."
    outputs.file(nodeLibsMarker)
    onlyIf { !nodeLibsMarker.isFile }
    doLast {
        val url = "https://github.com/PishiKuchulu/Anvex/releases/download/libnode-v24.18.0/anvex-libnode-24.18.0.zip"
        val expectedSha256 = "b4bdb7cae617cfd67b42567599e4cee046b2d086470b7c064f6dd20d6961fbd5"
        val zipFile = File(layout.buildDirectory.get().asFile, "nodejs-mobile.zip")
        zipFile.parentFile.mkdirs()
        URL(url).openStream().use { input -> zipFile.outputStream().use { input.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256")
        zipFile.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expectedSha256) { "libnode zip SHA-256 mismatch: $actual" }
        ZipInputStream(zipFile.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory && (entry.name.startsWith("bin/") || entry.name.startsWith("include/"))) {
                    val out = File(nodeLibsDir, entry.name)
                    out.parentFile.mkdirs()
                    out.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
        nodeLibsMarker.writeText("anvex-libnode v24.18.0 sha256=$expectedSha256\n")
    }
}
tasks.named("preBuild") { dependsOn(downloadNodeLibs) }

android {
    namespace = "com.anvex.core.android"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26
        // The bundled libnode package ships arm64-v8a, armeabi-v7a and x86_64 only.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    sourceSets {
        getByName("main") {
            // Package the prebuilt libnode.so per ABI (the CMake build only
            // produces our small bridge library).
            jniLibs.srcDirs("libnode/bin")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

ksp {
    // Export Room schemas so migrations are reviewable and testable (P0-3).
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:engine"))
    implementation(project(":core:ai"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    // v4.4.6: on-device APK signing-key generation (RSA + self-signed cert) and
    // GitHub Actions secret push (libsodium sealed box).
    implementation(libs.bouncycastle.bcpkix)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real-SQLite JVM migration tests (P0-3): xerial sqlite-jdbc, Apache-2.0,
    // test-only (never packaged into the APK).
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.room.runtime)
}
