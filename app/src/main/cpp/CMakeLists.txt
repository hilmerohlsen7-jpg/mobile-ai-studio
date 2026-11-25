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

# Optional graphics-chip (Vulkan) support, loaded only when the user switches it on.
if(ANDROID)
    file(GLOB _glslc "${CMAKE_ANDROID_NDK}/shader-tools/*/glslc")
    file(GLOB _vklib "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/*/sysroot/usr/lib/aarch64-linux-android/33/libvulkan.so")
    file(GLOB _vkinc "${CMAKE_CURRENT_SOURCE_DIR}/vkh/include")
    file(GLOB _spv "${CMAKE_CURRENT_SOURCE_DIR}/spirv/share/cmake/SPIRV-Headers")
    if(_glslc AND _vklib AND _spv AND _vkinc)
        list(GET _glslc 0 _glslc0)
        list(GET _vklib 0 _vklib0)
        list(GET _vkinc 0 _vkinc0)
        set(SPIRV-Headers_DIR "${_spv}" CACHE PATH "" FORCE)
        set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -I${CMAKE_CURRENT_SOURCE_DIR}/spirv/include")
        set(GGML_VULKAN ON CACHE BOOL "" FORCE)
        set(Vulkan_GLSLC_EXECUTABLE "${_glslc0}" CACHE FILEPATH "" FORCE)
        set(Vulkan_LIBRARY "${_vklib0}" CACHE FILEPATH "" FORCE)
        set(Vulkan_INCLUDE_DIR "${_vkinc0}" CACHE PATH "" FORCE)
        message(STATUS "Vulkan GPU backend enabled")
    else()
        message(STATUS "Vulkan GPU backend skipped (NDK pieces not found)")
    endif()
endif()

add_subdirectory(${LLAMA_DIR} llama-build)

add_library(mobileai SHARED mobileai.cpp)
target_include_directories(mobileai PRIVATE ${LLAMA_DIR}/include ${LLAMA_DIR}/ggml/include ${LLAMA_DIR}/tools/mtmd)

if(ANDROID)
    target_link_libraries(mobileai PRIVATE llama mtmd log)
else()
    target_link_libraries(mobileai PRIVATE llama mtmd)
endif()
