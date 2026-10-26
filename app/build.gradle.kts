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
        versionCode = 81
        versionName = "4.10.3"
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
