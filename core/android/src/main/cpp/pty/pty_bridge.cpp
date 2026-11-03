// Anvex PTY bridge (F3, P2) — real forkpty() sessions for the terminal.
//
// Design notes:
//  - forkpty() is the Termux-proven route to a true PTY on Android; bionic
//    provides it via <pty.h> (API 23+, our minSdk is 26). node-pty is not
//    viable inside this app (desktop Node-API prebuilds, and the Node
//    process model here is one-shot), so the PTY lives in this small
//    dedicated library instead.
//  - The Kotlin layer owns the session lifecycle: this bridge only creates
//    the pty, moves bytes, resizes, reports the termios ECHO flag (the
//    reliable password-prompt signal) and signals the child. All policy
//    (which commands may run, approval gates) stays in Kotlin — the bridge
//    is a transport, never a policy bypass.
//  - The child environment is built ONLY from the pairs the caller passes
//    (clearenv first): nothing leaks from the app process environment, and
//    LD_PRELOAD is explicitly removed even if a caller pair smuggles it.

#include <jni.h>
#include <pty.h>
#include <unistd.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <termios.h>
#include <cstring>
#include <string>
#include <vector>

namespace {

std::string ToStdString(JNIEnv *env, jstring s) {
    if (s == nullptr) return "";
    const char *utf = env->GetStringUTFChars(s, nullptr);
    if (utf == nullptr) return "";
    std::string out(utf);
    env->ReleaseStringUTFChars(s, utf);
    return out;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_anvex_core_android_runtime_pty_PtyBridge_nativePtyVersion(
        JNIEnv * /* env */, jobject /* this */) {
    // Bridge ABI version: 2 = full PTY surface (P2).
    return 2;
}

// Creates a pty and forks [shellPath] ("-i") with cwd + env applied.
// Returns int array [masterFd, childPid], or an empty array on failure.
extern "C" JNIEXPORT jintArray JNICALL
Java_com_anvex_core_android_runtime_pty_PtyBridge_nativeCreate(
        JNIEnv *env, jobject /* this */,
        jstring shellPath, jstring cwd, jobjectArray envPairs,
        jint rows, jint cols) {
    jintArray fail = env->NewIntArray(0);

    std::string shell = ToStdString(env, shellPath);
    std::string workDir = ToStdString(env, cwd);
    if (shell.empty()) return fail;

    std::vector<std::string> envStore;
    if (envPairs != nullptr) {
        jsize n = env->GetArrayLength(envPairs);
        for (jsize i = 0; i < n; i++) {
            auto item = (jstring) env->GetObjectArrayElement(envPairs, i);
            envStore.push_back(ToStdString(env, item));
        }
    }

    struct winsize ws{};
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);

    int master = -1;
    pid_t pid = forkpty(&master, nullptr, nullptr, &ws);
    if (pid < 0) return fail;

    if (pid == 0) {
        // ---- child ----
        if (!workDir.empty()) chdir(workDir.c_str());
        clearenv();
        for (const auto &kv : envStore) {
            auto eq = kv.find('=');
            if (eq == std::string::npos) continue;
            if (kv.compare(0, eq, "LD_PRELOAD") == 0) continue;  // never honour it
            setenv(kv.substr(0, eq).c_str(), kv.substr(eq + 1).c_str(), 1);
        }
        unsetenv("LD_PRELOAD");
        execlp(shell.c_str(), shell.c_str(), "-i", (char *) nullptr);
        _exit(127);
    }

    // ---- parent ----
    jintArray out = env->NewIntArray(2);
    jint vals[2] = {master, (jint) pid};
    env->SetIntArrayRegion(out, 0, 2, vals);
    return out;
}

// Blocking read into [buffer]; returns bytes read, or -1 on EOF/error
// (EIO once the child has exited and the slave side is gone reads as -1).
extern "C" JNIEXPORT jint JNICALL
Java_com_anvex_core_android_runtime_pty_PtyBridge_nativeRead(
        JNIEnv *env, jobject /* this */, jint fd, jbyteArray buffer) {
    jsize cap = env->GetArrayLength(buffer);
    std::vector<jbyte> tmp((size_t) cap);
    ssize_t n = read(fd, tmp.data(), (size_t) cap);
    if (n > 0) env->SetByteArrayRegion(buffer, 0, (jsize) n, tmp.data());
    return (jint) n;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_anvex_core_android_runtime_pty_PtyBridge_nativeWrite(
        JNIEnv *env, jobject /* this */, jint fd, jbyteArray data) {
    jsize len = env->GetArrayLength(data);
    std::vector<jbyte> tmp((size_t) len);
    env->GetByteArrayRegion(data, 0, len, tmp.data());
    size_t done = 0;
    while (done < (size_t) len) {
        ssize_t n = write(fd, tmp.data() + done, (size_t) len - done);
        if (n <= 0) break;
        done += (size_t) n;
    }
    return (jint) done;
}

extern "C" JNIEXPORT void JNICALL
Java_com_anvex_core_android_runtime_pty_PtyBridge_nativeResize(
        JNIEnv * /* env */, jobject /* this */, jint fd, jint rows, jint cols) {
    struct winsize ws{};
    ws.ws_row = (unsigned short) (rows > 0 ? rows : 24);
    ws.ws_col = (unsigned short) (cols > 0 ? cols : 80);
    ioctl(fd, TIOCSWINSZ, &ws);
}

// True when the slave's termios has ECHO off — the reliable signal that the
// foreground program is reading a password/secret.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_anvex_core_android_runtime_pty_PtyBridge_nativeEchoOff(
        JNIEnv * /* env */, jobject /* this */, jint fd) {
    struct termios tio{};
    if (tcgetattr(fd, &tio) != 0) return JNI_FALSE;
    return (tio.c_lflag & ECHO) == 0 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_anvex_core_android_runtime_pty_PtyBridge_nativeSignal(
        JNIEnv * /* env */, jobject /* this */, jint pid, jint sig) {
    if (pid > 0) kill((pid_t) pid, sig);
}

extern "C" JNIEXPORT void JNICALL
Java_com_anvex_core_android_runtime_pty_PtyBridge_nativeClose(
        JNIEnv * /* env */, jobject /* this */, jint fd) {
    if (fd >= 0) close(fd);
}
