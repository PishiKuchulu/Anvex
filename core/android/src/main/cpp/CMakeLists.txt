# Anvex Node.js bridge (nodejs-mobile). Mirrors the official nodejs-mobile
# native-gradle sample: build our small JNI library, import the prebuilt
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

target_link_libraries(anvex-node
                      libnode
                      ${log-lib})
