cmake_minimum_required(VERSION 3.22.1)

project("ai-chat" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

set(CMAKE_C_FLAGS   "${CMAKE_C_FLAGS}"   CACHE STRING "" FORCE)
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS}" CACHE STRING "" FORCE)

# --------------------------------------------------------------------------
# AI Chat library
# --------------------------------------------------------------------------

if(DEFINED ANDROID_ABI)
    message(STATUS "Detected Android ABI: ${ANDROID_ABI}")
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(GGML_SYSTEM_ARCH "ARM")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP ON)

        # ------------------------------------------------------------------
        # OpenCL backend (Adreno GPU). Khronos headers + ICD loader are
        # vendored under llama-engine/thirdparty. The loader's libOpenCL.so is
        # used at link time only and must NOT be packaged into the APK: at
        # runtime Android resolves libOpenCL.so from /vendor/lib64, which is on
        # the vendor public library list (verified on Snapdragon 8 Elite).
        # ------------------------------------------------------------------
        set(GGML_OPENCL ON)
        set(THIRDPARTY_DIR ${CMAKE_CURRENT_LIST_DIR}/../../../thirdparty)
        set(OPENCL_ICD_LOADER_HEADERS_DIR ${THIRDPARTY_DIR}/OpenCL-Headers CACHE PATH "" FORCE)
        set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
        set(OPENCL_ICD_LOADER_BUILD_TESTING OFF CACHE BOOL "" FORCE)
        add_subdirectory(${THIRDPARTY_DIR}/OpenCL-ICD-Loader ${CMAKE_CURRENT_BINARY_DIR}/opencl-icd-loader)
        # Keep the loader's libOpenCL.so out of AGP's merged native libs dir so
        # it is never packaged into the AAR/APK (link-time use only).
        set_target_properties(OpenCL PROPERTIES
            LIBRARY_OUTPUT_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/opencl-icd-loader-output)
        # Satisfy find_package(OpenCL REQUIRED) inside ggml-opencl with the
        # vendored headers and the ICD loader target defined above.
        set(OpenCL_INCLUDE_DIR ${THIRDPARTY_DIR}/OpenCL-Headers CACHE PATH "" FORCE)
        set(OpenCL_LIBRARY OpenCL CACHE STRING "" FORCE)
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(GGML_SYSTEM_ARCH "x86")
        set(GGML_CPU_KLEIDIAI OFF)
        set(GGML_OPENMP OFF)
    else()
        message(FATAL_ERROR "Unsupported ABI: ${ANDROID_ABI}")
    endif()
endif()

set(LLAMA_SRC ${CMAKE_CURRENT_LIST_DIR}/../../../../app/src/main/cpp/llama.cpp)
add_subdirectory(${LLAMA_SRC} build-llama)

if(TARGET ggml-opencl)
    # ggml-opencl links against the OpenCL target by name; make sure the ICD
    # loader is built first.
    add_dependencies(ggml-opencl OpenCL)
endif()

add_library(${CMAKE_PROJECT_NAME} SHARED
        ai_chat.cpp)

target_compile_definitions(${CMAKE_PROJECT_NAME} PRIVATE
        GGML_SYSTEM_ARCH=${GGML_SYSTEM_ARCH}
        GGML_CPU_KLEIDIAI=$<BOOL:${GGML_CPU_KLEIDIAI}>
        GGML_OPENMP=$<BOOL:${GGML_OPENMP}>
)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE
        ${LLAMA_SRC}
        ${LLAMA_SRC}/common
        ${LLAMA_SRC}/include
        ${LLAMA_SRC}/ggml/include
        ${LLAMA_SRC}/ggml/src)

target_link_libraries(${CMAKE_PROJECT_NAME}
        llama
        llama-common
        mtmd
        android
        log)
