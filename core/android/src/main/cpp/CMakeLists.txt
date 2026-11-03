# Anvex Node.js bridge. Mirrors the official nodejs-mobile
# native-gradle sample pattern: build our small JNI library, import the prebuilt
# libnode.so for the target ABI, link them.
#
# The libnode/ tree (bin/<abi>/libnode.so + include/node/) is NOT in git; the
# Gradle task `downloadNodeLibs` in this module's build.gradle.kts fetches and
# hash-verifies it before the build (preBuild depends on it).

cmake_minimum_required(VERSION 3.22.1)
project(anvex_node)

add_library(anvex-node SHARED
            node_bridge.cpp)

# Node's headers. (CMakeLists lives in src/main/cpp — three levels below the
# module root, where the downloadNodeLibs task extracts libnode/.)
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/../../../libnode/include/node)

add_library(libnode SHARED IMPORTED)
set_target_properties(libnode PROPERTIES IMPORTED_LOCATION
                      ${CMAKE_CURRENT_SOURCE_DIR}/../../../libnode/bin/${ANDROID_ABI}/libnode.so)

find_library(log-lib log)

# v4.10.1 (ARCH-04 insurance): link our own JNI library with 16 KB ELF
# page alignment so it stays loadable on Android 15+ 16 KB-page devices.
# (The prebuilt libnode.so was verified 16 KB-aligned already — LOAD
# segments at 0x4000 — contrary to the review's claim.)
target_link_options(anvex-node PRIVATE "LINKER:-z,max-page-size=16384")

target_link_libraries(anvex-node
                      libnode
                      ${log-lib})

# ---------------------------------------------------------------------------
# Desktop-grade upgrade (P0): this file is now the multi-target native root.
# Each feature library is a small, separately loadable .so so a missing or
# broken optional library (git, tree-sitter, vectors) can never take down
# the Node bridge or the app; Kotlin loaders treat every one of them as
# failure-tolerant. Every Anvex-built library carries the 16 KB page
# alignment linker flag, and the CI smoke gate (verifyNativeLibs in
# app/build.gradle.kts) checks presence per ABI + LOAD alignment + JNI
# symbols inside the built APK.
#
#   anvex-pty  — F3 PTY bridge (P0: probe only; forkpty lands in P2)
#   anvex-git  — F1 libgit2 bridge (P3)
#   anvex-ts   — F2 Tree-sitter bridge (P4)
#   anvex-vec  — F6 sqlite-vec bridge (P7)
# ---------------------------------------------------------------------------

add_library(anvex-pty SHARED
            pty/pty_bridge.cpp)
target_link_options(anvex-pty PRIVATE "LINKER:-z,max-page-size=16384")
target_link_libraries(anvex-pty
                      ${log-lib})
