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
// NATIVE FETCH PATTERN (desktop-grade upgrade, P0): every third-party native
// artifact this project consumes (libnode today; libgit2 + mbedTLS in P3,
// Tree-sitter grammars in P4, sqlite3 + sqlite-vec in P7) is fetched by a
// Gradle task in this file following the exact shape of downloadNodeLibs:
//   1. a pinned release URL (never a moving branch or "latest" link),
//   2. a pinned SHA-256, verified BEFORE extraction; mismatch fails the build,
//   3. extraction into a gitignored directory under this module,
//   4. a marker file so the fetch runs once; preBuild depends on the task.
// Nothing third-party and binary is ever committed to git.
// ---------------------------------------------------------------------------
// Bundled Node.js 24 runtime (real Node.js on-device). Since 4.8.0 the
// libnode package is Anvex's own: Node 24.22.1 (LTS) built for bionic via
// Termux nodejs-lts with --shared, plus its DT_NEEDED dependency closure,
// published as the repo release "libnode-v24.22.1" by the libnode-release /
// libnode-reassemble workflows. The ~105 MB zip is NOT committed to git;
// this task downloads it once, verifies its SHA-256, and extracts bin/ +
// include/ into libnode/ (gitignored). preBuild depends on it, so local
// and CI builds both fetch it deterministically.
val nodeLibsDir = layout.projectDirectory.dir("libnode").asFile
val nodeLibsMarker = File(nodeLibsDir, ".anvex-node-24.22.1.ok")
val downloadNodeLibs = tasks.register("downloadNodeLibs") {
    description = "Downloads and hash-verifies the bundled libnode 24 runtime."
    outputs.file(nodeLibsMarker)
    onlyIf { !nodeLibsMarker.isFile }
    doLast {
        val url = "https://github.com/PishiKuchulu/Anvex/releases/download/libnode-v24.22.1/anvex-libnode-24.22.1.zip"
        val expectedSha256 = "d7c2e9ea3f23bb2bd0461dfeadf55b02b6f0a4964d2a2c0e7c90e5eda617d6a2"
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
        nodeLibsMarker.writeText("anvex-libnode v24.22.1 sha256=$expectedSha256\n")
    }
}
tasks.named("preBuild") { dependsOn(downloadNodeLibs) }

// libgit2 1.9.7 (F1, P3): source tarball for the anvex-git bridge. Same
// fetch-pattern shape as downloadNodeLibs (pinned URL + SHA-256 + marker);
// extraction happens at CMake configure time via file(ARCHIVE_EXTRACT) so
// no tar tooling is needed in Gradle. The extracted tree is compiled as a
// static library into libanvex-git.so by src/main/cpp/CMakeLists.txt.
val gitLibsDir = layout.projectDirectory.dir("libgit2-dist").asFile
val gitTarball = File(gitLibsDir, "libgit2-1.9.7.tar.gz")
val gitLibsMarker = File(gitLibsDir, ".anvex-libgit2-1.9.7.ok")
val downloadLibgit2 = tasks.register("downloadLibgit2") {
    description = "Downloads and hash-verifies the libgit2 1.9.7 source tarball."
    outputs.file(gitLibsMarker)
    onlyIf { !gitLibsMarker.isFile }
    doLast {
        val url = "https://github.com/libgit2/libgit2/archive/refs/tags/v1.9.7.tar.gz"
        val expectedSha256 = "1a4fbe7589e814777ae76b64734ad80f4ecad22cd33a22682a2aaea4ae5375e7"
        gitLibsDir.mkdirs()
        URL(url).openStream().use { input -> gitTarball.outputStream().use { input.copyTo(it) } }
        val digest = MessageDigest.getInstance("SHA-256")
        gitTarball.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                digest.update(buf, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        check(actual == expectedSha256) { "libgit2 tarball SHA-256 mismatch: $actual" }
        gitLibsMarker.writeText("libgit2 v1.9.7 sha256=$expectedSha256\n")
    }
}
tasks.named("preBuild") { dependsOn(downloadLibgit2) }

// Tree-sitter (F2a, P4): core runtime 0.25.8 + five grammar source
// tarballs (Kotlin, Java, TypeScript, TSX, Python — all LANGUAGE_VERSION
// 14, inside the core's supported 13..15). Same pinned fetch pattern;
// extraction happens at CMake configure time via file(ARCHIVE_EXTRACT).
// Everything is compiled into libanvex-ts.so by src/main/cpp/CMakeLists.txt.
val tsLibsDir = layout.projectDirectory.dir("ts-dist").asFile
val tsLibsMarker = File(tsLibsDir, ".anvex-tree-sitter-0.25.8.ok")
val downloadTreeSitter = tasks.register("downloadTreeSitter") {
    description = "Downloads and hash-verifies the tree-sitter core + grammar tarballs."
    outputs.file(tsLibsMarker)
    onlyIf { !tsLibsMarker.isFile }
    doLast {
        val artifacts = listOf(
            Triple("tree-sitter-0.25.8.tar.gz", "https://github.com/tree-sitter/tree-sitter/archive/refs/tags/v0.25.8.tar.gz", "178b575244d967f4920a4642408dc4edf6de96948d37d7f06e5b78acee9c0b4e"),
            Triple("ts-kotlin-0.3.8.tar.gz", "https://github.com/fwcd/tree-sitter-kotlin/archive/refs/tags/0.3.8.tar.gz", "7dd60975786bf9cb4be6a5176f5ccb5fed505f9929a012da30762505b1015669"),
            Triple("ts-java-0.23.5.tar.gz", "https://github.com/tree-sitter/tree-sitter-java/archive/refs/tags/v0.23.5.tar.gz", "cb199e0faae4b2c08425f88cbb51c1a9319612e7b96315a174a624db9bf3d9f0"),
            Triple("ts-typescript-0.23.2.tar.gz", "https://github.com/tree-sitter/tree-sitter-typescript/archive/refs/tags/v0.23.2.tar.gz", "2c4ce711ae8d1218a3b2f899189298159d672870b5b34dff5d937bed2f3e8983"),
            Triple("ts-python-0.23.6.tar.gz", "https://github.com/tree-sitter/tree-sitter-python/archive/refs/tags/v0.23.6.tar.gz", "630a0f45eccd9b69a66a07bf47d1568e96a9c855a2f30e0921c8af7121e8af96"),
        )
        tsLibsDir.mkdirs()
        for ((name, url, expectedSha256) in artifacts) {
            val file = File(tsLibsDir, name)
            URL(url).openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    digest.update(buf, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            check(actual == expectedSha256) { "tree-sitter artifact $name SHA-256 mismatch: $actual" }
        }
        tsLibsMarker.writeText("tree-sitter 0.25.8 + grammars kotlin 0.3.8 / java 0.23.5 / typescript 0.23.2 / python 0.23.6\n")
    }
}
tasks.named("preBuild") { dependsOn(downloadTreeSitter) }

android {
    namespace = "com.anvex.core.android"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The bundled libnode package ships arm64-v8a, armeabi-v7a and x86_64 only.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                // Node 24's bundled V8 headers require C++20 (v8config.h
                // hard-errors below it); Node 18 accepted C++17.
                cppFlags += "-std=c++20"
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
    packaging {
        resources {
            // androidx.test runner/ext jars carry identical multi-release
            // OSGi manifests; the androidTest merge rejects the duplicate.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
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
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    // Real-SQLite JVM migration tests (P0-3): xerial sqlite-jdbc, Apache-2.0,
    // test-only (never packaged into the APK).
    testImplementation(libs.sqlite.jdbc)
    testImplementation(libs.room.runtime)
    // Real org.json for JVM unit tests (android.jar's is a stub); test-only.
    testImplementation(libs.org.json)
}
