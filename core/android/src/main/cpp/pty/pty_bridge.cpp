// Anvex PTY bridge — P0 scaffolding probe.
//
// The full forkpty() implementation lands in P2 (desktop-grade upgrade plan,
// feature F3). This translation unit exists so the multi-target CMake layout,
// the Kotlin loader, and the CI native smoke gate (verifyNativeLibs in
// app/build.gradle.kts) all have a real second library to build, package and
// inspect from day one — scaffolding that is verified, not scaffolding that
// merely looks right.

#include <jni.h>

extern "C" JNIEXPORT jint JNICALL
Java_com_anvex_core_android_runtime_pty_PtyBridge_nativePtyVersion(
        JNIEnv * /* env */, jobject /* this */) {
    // Bridge ABI version. P2 bumps this when the real PTY surface lands.
    return 1;
}
