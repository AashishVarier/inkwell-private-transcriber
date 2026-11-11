# Native build for Inkwell: whisper.cpp + ggml + a small JNI bridge.
# Modelled on whisper.cpp/examples/whisper.android/lib/src/main/jni/whisper/CMakeLists.txt.

cmake_minimum_required(VERSION 3.22.1)

# Always build optimised native code, even for debug APKs: unoptimised whisper.cpp is ~10x slower.
# (Gradle also passes -DCMAKE_BUILD_TYPE=Release; this makes it impossible to get wrong.)
set(CMAKE_BUILD_TYPE Release CACHE STRING "Build type" FORCE)

project(inkwell C CXX)

# Limit parallel compiles to roughly one per 1.5 GB of RAM: ggml's -O3 CPU variants are heavy,
# and an unlimited ninja run can exhaust memory on 8 GB machines.
cmake_host_system_information(RESULT INKWELL_TOTAL_MEM_MB QUERY TOTAL_PHYSICAL_MEMORY)
cmake_host_system_information(RESULT INKWELL_CORES QUERY NUMBER_OF_LOGICAL_CORES)
math(EXPR INKWELL_JOBS "${INKWELL_TOTAL_MEM_MB} / 1536")
if (INKWELL_JOBS LESS 1)
    set(INKWELL_JOBS 1)
endif()
if (INKWELL_JOBS GREATER INKWELL_CORES)
    set(INKWELL_JOBS ${INKWELL_CORES})
endif()
set_property(GLOBAL APPEND PROPERTY JOB_POOLS inkwell_compile=${INKWELL_JOBS})
set(CMAKE_JOB_POOL_COMPILE inkwell_compile)
message(STATUS "Native compile jobs: ${INKWELL_JOBS}")

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/whisper.cpp)
if (NOT EXISTS ${WHISPER_DIR}/include/whisper.h)
    message(FATAL_ERROR "whisper.cpp submodule missing. Run: git submodule update --init --recursive")
endif()

# whisper.cpp version string (same approach as the upstream Android example)
file(READ "${WHISPER_DIR}/CMakeLists.txt" WHISPER_MAIN_CMAKE)
string(REGEX MATCH "project\\(\"whisper\\.cpp\" VERSION ([0-9]+\\.[0-9]+\\.[0-9]+)\\)" _ "${WHISPER_MAIN_CMAKE}")
if (CMAKE_MATCH_1)
    set(WHISPER_VERSION ${CMAKE_MATCH_1})
else()
    set(WHISPER_VERSION "unknown")
endif()
message(STATUS "whisper.cpp version: ${WHISPER_VERSION}")

# Release flags for every target (ggml included).
add_compile_options(-O3)
add_compile_definitions(NDEBUG)

# ggml backends are built as loadable modules and written next to the other .so files,
# so they are packaged into the APK.
if (NOT CMAKE_RUNTIME_OUTPUT_DIRECTORY)
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_LIBRARY_OUTPUT_DIRECTORY})
endif()

# ggml configuration: CPU only, one build containing every Android ARM CPU variant
# (armv8.0 … armv9.2). At runtime ggml scores each against the phone's CPU features and loads
# the fastest one that is supported, so old and new phones both work from one APK.
set(BUILD_SHARED_LIBS     ON  CACHE BOOL "" FORCE)
set(GGML_NATIVE           OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL       ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON  CACHE BOOL "" FORCE)
set(GGML_OPENMP           OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(GGML_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR}/ggml ggml)

add_library(inkwell SHARED
    inkwell_jni.cpp
    ${WHISPER_DIR}/src/whisper.cpp
    ${WHISPER_DIR}/src/aneforge/whisper-aneforge.cpp
)

target_include_directories(inkwell PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/src
    ${WHISPER_DIR}/ggml/include
)

target_compile_definitions(inkwell PRIVATE WHISPER_VERSION="${WHISPER_VERSION}")
target_compile_options(inkwell PRIVATE
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
)
target_link_options(inkwell PRIVATE -Wl,--gc-sections)

find_library(LOG_LIB log)
target_link_libraries(inkwell PRIVATE ggml ${LOG_LIB} android)
