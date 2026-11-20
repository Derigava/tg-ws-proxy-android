cmake_minimum_required(VERSION 3.22.1)

project(dpi_runtime)

# The native engine sources are pinned (immutable) under upstream/byebyedpi/.
# This module compiles them in place: it never copies or modifies the snapshot.
set(UPSTREAM_CPP "${CMAKE_CURRENT_SOURCE_DIR}/../../../../upstream/byebyedpi/app/src/main/cpp")

file(GLOB BYE_DPI_SRC "${UPSTREAM_CPP}/byedpi/*.c")
list(REMOVE_ITEM BYE_DPI_SRC "${UPSTREAM_CPP}/byedpi/win_service.c")

add_library(byedpi SHARED
    ${BYE_DPI_SRC}
    "${UPSTREAM_CPP}/byedpi-jni.c"
    "${UPSTREAM_CPP}/hev-jni.c")

target_include_directories(byedpi PRIVATE
    "${UPSTREAM_CPP}"
    "${UPSTREAM_CPP}/byedpi")

target_compile_options(byedpi PRIVATE
    -std=c99 -O2 -Wall -Wno-unused -Wextra -Wno-unused-parameter -pedantic)
target_compile_definitions(byedpi PRIVATE ANDROID_APP)

target_link_libraries(byedpi PRIVATE android log dl)
target_link_options(byedpi PRIVATE "-Wl,--wrap=daemon")
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")
