@echo off
set NDK=C:\Users\Attect\AppData\Local\Android\Sdk\ndk\29.0.13113456
set ROOT=C:\Users\Attect\AndroidStudioProjects\Bonsai
set WRAP=C:\Users\Attect\AppData\Local\Temp\testops-wrap
set BUILD=C:\Users\Attect\AppData\Local\Temp\llama-android-testops

mkdir %WRAP% 2>nul
> %WRAP%\CMakeLists.txt (
echo cmake_minimum_required(VERSION 3.22.1^)
echo project(testops C CXX^)
echo set(GGML_SYSTEM_ARCH "ARM"^)
echo set(GGML_OPENMP OFF^)
echo set(THIRDPARTY_DIR %ROOT:\=/%/llama-engine/thirdparty^)
echo set(OPENCL_ICD_LOADER_HEADERS_DIR ${THIRDPARTY_DIR}/OpenCL-Headers CACHE PATH "" FORCE^)
echo set(BUILD_TESTING OFF CACHE BOOL "" FORCE^)
echo set(OPENCL_ICD_LOADER_BUILD_TESTING OFF CACHE BOOL "" FORCE^)
echo add_subdirectory(${THIRDPARTY_DIR}/OpenCL-ICD-Loader ${CMAKE_BINARY_DIR}/opencl-icd-loader^)
echo set(OpenCL_INCLUDE_DIR ${THIRDPARTY_DIR}/OpenCL-Headers CACHE PATH "" FORCE^)
echo set(OpenCL_LIBRARY OpenCL CACHE STRING "" FORCE^)
echo add_subdirectory(%ROOT:\=/%/app/src/main/cpp/llama.cpp build-llama^)
)

cmake -S %WRAP% -B %BUILD% -G Ninja ^
  -DCMAKE_TOOLCHAIN_FILE=%NDK%/build/cmake/android.toolchain.cmake ^
  -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-28 ^
  -DCMAKE_BUILD_TYPE=Release ^
  -DGGML_OPENCL=ON ^
  -DBUILD_SHARED_LIBS=OFF -DLLAMA_BUILD_TESTS=ON -DLLAMA_BUILD_EXAMPLES=OFF -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_SERVER=OFF -DLLAMA_CURL=OFF ^
  || exit /b 1
cmake --build %BUILD% --target test-backend-ops -j 16 || exit /b 1
echo BUILD_OK
