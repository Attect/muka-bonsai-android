@echo off
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvars64.bat" >nul
if errorlevel 1 exit /b 1
set SRC=C:\Users\Attect\AndroidStudioProjects\Bonsai\app\src\main\cpp\llama.cpp
set BUILD=C:\Users\Attect\AppData\Local\Temp\llama-msvc-build
cmake -S %SRC% -B %BUILD% -G Ninja -DCMAKE_BUILD_TYPE=Release -DGGML_OPENCL=OFF -DGGML_NATIVE=ON -DLLAMA_BUILD_TESTS=OFF -DLLAMA_BUILD_EXAMPLES=ON -DLLAMA_BUILD_TOOLS=OFF -DLLAMA_BUILD_SERVER=OFF || exit /b 1
cmake --build %BUILD% --target llama-simple-chat -j 16 || exit /b 1
echo BUILD_OK
