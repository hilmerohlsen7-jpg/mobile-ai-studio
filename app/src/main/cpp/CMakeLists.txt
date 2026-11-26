cmake_minimum_required(VERSION 3.22.1)
project(mobileai LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Always optimise, even when Gradle asks for a debug build: an unoptimised
# llama.cpp is far too slow to be usable on a phone.
if(NOT CMAKE_BUILD_TYPE OR CMAKE_BUILD_TYPE STREQUAL "Debug")
    set(CMAKE_BUILD_TYPE Release)
endif()

# llama.cpp is fetched by the build workflow into this folder (see .github/workflows/build.yml).
set(LLAMA_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp" CACHE PATH "Path to llama.cpp")

# The CPU maths code is built in several versions (basic phones ... newest phones) and the
# app loads the fastest one this phone can run, at start-up.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

add_subdirectory(${LLAMA_DIR} llama-build)

add_library(mobileai SHARED mobileai.cpp)
target_include_directories(mobileai PRIVATE ${LLAMA_DIR}/include ${LLAMA_DIR}/ggml/include ${LLAMA_DIR}/tools/mtmd)

if(ANDROID)
    target_link_libraries(mobileai PRIVATE llama mtmd log)
else()
    target_link_libraries(mobileai PRIVATE llama mtmd)
endif()

# RAR (including RAR5) and other archives, read with libarchive.
set(LIBARCHIVE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/libarchive" CACHE PATH "Path to libarchive")
if(EXISTS "${LIBARCHIVE_DIR}/CMakeLists.txt")
    foreach(_o ENABLE_ACL ENABLE_XATTR ENABLE_ICONV ENABLE_OPENSSL ENABLE_LIBB2 ENABLE_LZ4 ENABLE_LZO ENABLE_LZMA ENABLE_ZSTD
               ENABLE_ZLIB ENABLE_BZip2 ENABLE_EXPAT ENABLE_LIBXML2 ENABLE_PCREPOSIX ENABLE_PCRE2POSIX ENABLE_LIBGCC ENABLE_CNG
               ENABLE_TAR ENABLE_CPIO ENABLE_CAT ENABLE_UNZIP ENABLE_TEST ENABLE_INSTALL ENABLE_COVERAGE ENABLE_MBEDTLS ENABLE_NETTLE)
        set(${_o} OFF CACHE BOOL "" FORCE)
    endforeach()
    add_subdirectory(${LIBARCHIVE_DIR} libarchive-build EXCLUDE_FROM_ALL)
    add_library(arc SHARED arc.c)
    target_include_directories(arc PRIVATE ${LIBARCHIVE_DIR}/libarchive)
    target_link_libraries(arc PRIVATE archive log)
endif()
