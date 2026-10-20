// Anvex Node.js bridge — starts the bundled nodejs-mobile runtime (libnode).
//
// Pattern follows the official nodejs-mobile native-gradle sample: libuv
// requires all arguments in contiguous memory, so the Java string array is
// packed into one buffer before calling node::Start(). Node can be started
// exactly ONCE per process; the caller (NodeExecService) therefore lives in
// a dedicated ":node" process that is killed after each command.
//
// Node.js (and nodejs-mobile) is licensed under the Node.js license (MIT-style);
// see https://github.com/nodejs-mobile/nodejs-mobile.

#include <jni.h>
#include <string>
#include <cstring>
#include <cstdlib>
#include "node.h"

extern "C" JNIEXPORT jint JNICALL
Java_com_anvex_core_android_runtime_node_NodeBridge_startNodeWithArguments(
        JNIEnv *env,
        jobject /* this */,
        jobjectArray arguments) {

    const jsize argument_count = env->GetArrayLength(arguments);
    if (argument_count <= 0) return -1;

    // Fetch all strings first; libuv needs them in contiguous memory.
    std::string joined[64];
    if (argument_count > 64) return -1;
    int c_arguments_size = 0;
    for (int i = 0; i < argument_count; i++) {
        auto jstr = (jstring) env->GetObjectArrayElement(arguments, i);
        const char *utf = env->GetStringUTFChars(jstr, nullptr);
        joined[i] = utf ? utf : "";
        env->ReleaseStringUTFChars(jstr, utf);
        c_arguments_size += (int) joined[i].size() + 1; // + '\0'
    }

    // Pack into one contiguous buffer and build argv pointing into it.
    char *args_buffer = (char *) calloc(c_arguments_size, sizeof(char));
    if (args_buffer == nullptr) return -1;
    char *argv[64];
    char *current = args_buffer;
    for (int i = 0; i < argument_count; i++) {
        memcpy(current, joined[i].c_str(), joined[i].size() + 1);
        argv[i] = current;
        current += joined[i].size() + 1;
    }

    // Blocks until the Node event loop drains (script exited / server closed).
    const int exit_code = node::Start(argument_count, argv);

    free(args_buffer);
    return jint(exit_code);
}
