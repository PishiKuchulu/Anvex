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

# ---------------------------------------------------------------------------
# anvex-git — F1 (P3): libgit2 1.9.7, statically linked. The tarball is
# fetched + SHA-256-verified by the downloadLibgit2 Gradle task into
# libgit2-dist/ (module root = three levels up from this file, same as
# libnode/); it is extracted here at configure time. Local operations only:
# HTTPS/SSH transports are OFF by design (no TLS backend bundled), so this
# library can never open a network socket. If the tarball is absent the
# target is skipped and the Kotlin GitBridge reports itself unavailable.
# ---------------------------------------------------------------------------
set(ANVEX_LIBGIT2_TARBALL ${CMAKE_CURRENT_SOURCE_DIR}/../../../libgit2-dist/libgit2-1.9.7.tar.gz)
set(ANVEX_LIBGIT2_SRC ${CMAKE_BINARY_DIR}/libgit2-extract/libgit2-1.9.7)
if(EXISTS ${ANVEX_LIBGIT2_TARBALL})
    if(NOT EXISTS ${ANVEX_LIBGIT2_SRC}/CMakeLists.txt)
        file(ARCHIVE_EXTRACT INPUT ${ANVEX_LIBGIT2_TARBALL}
             DESTINATION ${CMAKE_BINARY_DIR}/libgit2-extract)
    endif()
    set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    set(BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(BUILD_CLI OFF CACHE BOOL "" FORCE)
    set(BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(USE_HTTPS OFF CACHE BOOL "" FORCE)
    set(USE_SSH OFF CACHE BOOL "" FORCE)
    set(USE_GSSAPI OFF CACHE BOOL "" FORCE)
    set(SONAME OFF CACHE BOOL "" FORCE)
    set(REGEX_BACKEND "builtin" CACHE STRING "" FORCE)
    add_subdirectory(${ANVEX_LIBGIT2_SRC} libgit2-build EXCLUDE_FROM_ALL)

    add_library(anvex-git SHARED
                git/git_bridge.cpp)
    target_include_directories(anvex-git PRIVATE ${ANVEX_LIBGIT2_SRC}/include)
    target_link_options(anvex-git PRIVATE "LINKER:-z,max-page-size=16384")
    target_link_libraries(anvex-git
                          libgit2package
                          ${log-lib})
else()
    message(WARNING "libgit2 tarball not found — skipping anvex-git (Git features unavailable)")
endif()

# ---------------------------------------------------------------------------
# anvex-ts — F2a (P4): tree-sitter 0.25.8 core (lib/src/lib.c amalgamation)
# + five grammars compiled in (Kotlin 0.3.8, Java 0.23.5, TypeScript +
# TSX 0.23.2, Python 0.23.6; all LANGUAGE_VERSION 14). Tarballs are
# fetched + SHA-256-verified by the downloadTreeSitter Gradle task into
# ts-dist/ and extracted here at configure time. If any tarball is absent
# the target is skipped and the Kotlin TsBridge reports itself
# unavailable (the editor keeps its regex highlighter).
# ---------------------------------------------------------------------------
set(ANVEX_TS_DIST ${CMAKE_CURRENT_SOURCE_DIR}/../../../ts-dist)
set(ANVEX_TS_EXTRACT ${CMAKE_BINARY_DIR}/ts-extract)
set(ANVEX_TS_CORE ${ANVEX_TS_EXTRACT}/tree-sitter-0.25.8)
set(ANVEX_TS_KOTLIN ${ANVEX_TS_EXTRACT}/tree-sitter-kotlin-0.3.8)
set(ANVEX_TS_JAVA ${ANVEX_TS_EXTRACT}/tree-sitter-java-0.23.5)
set(ANVEX_TS_TSX ${ANVEX_TS_EXTRACT}/tree-sitter-typescript-0.23.2)
set(ANVEX_TS_PYTHON ${ANVEX_TS_EXTRACT}/tree-sitter-python-0.23.6)
if(EXISTS ${ANVEX_TS_DIST}/tree-sitter-0.25.8.tar.gz AND
   EXISTS ${ANVEX_TS_DIST}/ts-kotlin-0.3.8.tar.gz AND
   EXISTS ${ANVEX_TS_DIST}/ts-java-0.23.5.tar.gz AND
   EXISTS ${ANVEX_TS_DIST}/ts-typescript-0.23.2.tar.gz AND
   EXISTS ${ANVEX_TS_DIST}/ts-python-0.23.6.tar.gz)
    foreach(ANVEX_TS_TARBALL tree-sitter-0.25.8.tar.gz ts-kotlin-0.3.8.tar.gz
            ts-java-0.23.5.tar.gz ts-typescript-0.23.2.tar.gz ts-python-0.23.6.tar.gz)
        if(NOT EXISTS ${ANVEX_TS_EXTRACT}/.extracted-${ANVEX_TS_TARBALL})
            file(ARCHIVE_EXTRACT INPUT ${ANVEX_TS_DIST}/${ANVEX_TS_TARBALL}
                 DESTINATION ${ANVEX_TS_EXTRACT})
            file(WRITE ${ANVEX_TS_EXTRACT}/.extracted-${ANVEX_TS_TARBALL} "ok\n")
        endif()
    endforeach()
    add_library(anvex-ts SHARED
                ts/ts_bridge.cpp
                ${ANVEX_TS_CORE}/lib/src/lib.c
                ${ANVEX_TS_KOTLIN}/src/parser.c
                ${ANVEX_TS_KOTLIN}/src/scanner.c
                ${ANVEX_TS_JAVA}/src/parser.c
                ${ANVEX_TS_TSX}/typescript/src/parser.c
                ${ANVEX_TS_TSX}/typescript/src/scanner.c
                ${ANVEX_TS_TSX}/tsx/src/parser.c
                ${ANVEX_TS_TSX}/tsx/src/scanner.c
                ${ANVEX_TS_PYTHON}/src/parser.c
                ${ANVEX_TS_PYTHON}/src/scanner.c)
    target_include_directories(anvex-ts PRIVATE
                ${ANVEX_TS_CORE}/lib/include
                ${ANVEX_TS_CORE}/lib/src
                # The TypeScript/TSX scanners include ../../common/scanner.h,
                # whose "tree_sitter/parser.h" does not resolve relative to
                # common/ — it lives in typescript/src/tree_sitter/ (the
                # tsx copy is byte-identical), so expose that dir.
                ${ANVEX_TS_TSX}/typescript/src)
    target_link_options(anvex-ts PRIVATE "LINKER:-z,max-page-size=16384")
    target_link_libraries(anvex-ts
                          ${log-lib})
else()
    message(WARNING "tree-sitter tarballs not found — skipping anvex-ts (Tree-sitter highlighting unavailable)")
endif()
