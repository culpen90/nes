cmake_minimum_required(VERSION 3.22.1)
project(NesCore LANGUAGES C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

get_filename_component(FCEUMM_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/fceumm" ABSOLUTE)
set(CORE_DIR "${FCEUMM_DIR}/src")
set(COMMON_DIR "${CORE_DIR}/drivers/libretro/libretro-common")

# Mirror the non-optional source list in the pinned upstream Makefile.common.
file(GLOB BOARD_SOURCES CONFIGURE_DEPENDS "${CORE_DIR}/boards/*.c")
file(GLOB INPUT_SOURCES CONFIGURE_DEPENDS "${CORE_DIR}/input/*.c")
set(CORE_SOURCES ${BOARD_SOURCES} ${INPUT_SOURCES})
foreach(name cart cheat crc32 fceu-endian fceu-memory fceu fds fds_apu file
        filter general input md5 nsf palette ppu sound state video vsuni ines
        unif x6502)
    list(APPEND CORE_SOURCES "${CORE_DIR}/${name}.c")
endforeach()
list(APPEND CORE_SOURCES
    "${CORE_DIR}/drivers/libretro/libretro.c"
    "${CORE_DIR}/drivers/libretro/libretro_dipswitch.c"
    "${COMMON_DIR}/streams/memory_stream.c"
    "${COMMON_DIR}/compat/compat_posix_string.c"
    "${COMMON_DIR}/compat/compat_snprintf.c"
    "${COMMON_DIR}/compat/compat_strcasestr.c"
    "${COMMON_DIR}/compat/compat_strl.c"
    "${COMMON_DIR}/compat/fopen_utf8.c"
    "${COMMON_DIR}/encodings/encoding_utf.c"
    "${COMMON_DIR}/file/file_path.c"
    "${COMMON_DIR}/file/file_path_io.c"
    "${COMMON_DIR}/streams/file_stream.c"
    "${COMMON_DIR}/streams/file_stream_transforms.c"
    "${COMMON_DIR}/string/stdstring.c"
    "${COMMON_DIR}/time/rtime.c"
    "${COMMON_DIR}/vfs/vfs_implementation.c")

add_library(fceumm STATIC ${CORE_SOURCES})
target_include_directories(fceumm PUBLIC "${COMMON_DIR}/include" "${CORE_DIR}"
    PRIVATE "${CORE_DIR}/input" "${CORE_DIR}/boards"
            "${CORE_DIR}/drivers/libretro")
target_compile_definitions(fceumm PRIVATE __LIBRETRO__ FCEU_VERSION_NUMERIC=9900
    FRONTEND_SUPPORTS_RGB565=1 PSS_STYLE=1 HAVE_NO_LANGEXTRA
    GIT_VERSION="7a542da")
target_compile_options(fceumm PRIVATE -O2 -fno-strict-aliasing)
target_link_libraries(fceumm PUBLIC m)

if(ANDROID)
    add_library(nescore SHARED nes_session.cpp nes_jni.cpp)
    target_link_libraries(nescore PRIVATE fceumm log)
    # Android devices with 16 KiB memory pages are supported by NDK 28.
    target_link_options(nescore PRIVATE "-Wl,-z,max-page-size=16384")
else()
    # Native smoke runner allows functional checks without Android/JNI.
    add_executable(nescore_smoke nes_session.cpp smoke_main.cpp)
    target_link_libraries(nescore_smoke PRIVATE fceumm)
    add_executable(nescore_regression nes_session.cpp regression_main.cpp)
    target_link_libraries(nescore_regression PRIVATE fceumm)
    enable_testing()
    add_test(NAME native_regression COMMAND nescore_regression)
endif()
