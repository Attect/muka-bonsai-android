# Bonsai Android

[Muka.Cool](https://muka.cool) 出品的 AI 模型推理引擎与前端，针对 Android 平台进行了适配与优化。模型来自 [PrismML](https://prismml.com/)。

在手机上本地运行 Bonsai 1-bit / 三值（ternary）大模型，无需联网推理。

## 功能特性

- **本地推理**：基于 llama.cpp，支持 Bonsai 1-bit 27B（Q1_0）与 Ternary 1.58-bit 27B（Q2_0 g128）GGUF 模型，OpenCL GPU 加速
- **流式渲染**：Markdown（GFM 表格/删除线）、LaTeX 公式（MathJax）、Mermaid 流程图边生成边渲染
- **思考气泡**：模型 think 内容独立折叠区块，思考过程可展开查看
- **模型管理**：内置下载（HF 镜像 / HuggingFace / ModelScope 三源）、导入本地 GGUF
- **推理参数**：上下文长度（最高支持模型上限）、最大输出（64–32768）、线程数、温度、KV 缓存量化（F16/Q8_0/Q4_0）
- **OpenAI 兼容 API**：开启后外部应用可通过 OpenAI 接口调用本地模型
- **滚动体验**：消息列表倒置布局，长输出始终贴合最新内容，上滑暂停跟随、回底恢复

## 构建

环境要求：

- Android Studio（最新稳定版）
- Android SDK 36.1 / NDK / CMake
- JDK 22（工程已通过 `gradle/gradle-daemon-jvm.properties` 钉定，Gradle 会自动匹配；请勿使用 GraalVM——其 `jlink` 与 android-36.1 平台不兼容）

```bash
git clone git@github.com:Attect/muka-bonsai-android.git
cd muka-bonsai-android
git submodule update --init
./gradlew :app:assembleDebug
```

模型文件（`.gguf`）不入库，在应用内「模型」页下载或导入。

## 项目结构

```
app/             应用主体（Compose UI、模型管理、OpenAI API 服务）
  src/main/assets/vendor/    MathJax / Mermaid 渲染引擎（WebView 内运行）
  src/main/assets/licenses/  第三方组件许可文本（关于页展示）
  src/main/cpp/llama.cpp     llama.cpp 子模块（fork，见下）
llama-engine/    推理引擎模块（JNI 封装、协程流式 API、GGUF 元数据）
```

## llama.cpp 子模块

子模块指向 fork [Attect/llama.cpp-muka](https://github.com/Attect/llama.cpp-muka) 的 `q2_0-g128` 分支，在上游 ggml-org/llama.cpp 之上带有一个定制提交：

- `block_q2_0` 块大小 64 → 128（PrismML 三值格式，34 字节/块）
- CPU 点积按 `QK2_0/QK8_0` 泛化
- 新增 Q2_0 OpenCL kernel（mul_mv / mul_mm / gemv / gemm）

## 测试

```bash
./gradlew :app:testDebugUnitTest          # 单元测试
./gradlew :app:connectedDebugAndroidTest  # 仪器测试（渲染链路，需设备）
```

Debug 构建在「设置 → Mock 渲染测试」提供无需模型的流式渲染测试台：三种样本（长文本计数 / Markdown 混合含公式与流程图 / Think 流式），速度可调，用于验证流式渲染与滚动跟随。

## 开源许可

本项目以 [MIT License](LICENSE) 开源，Copyright (c) 2026 Attect。

开发者：Attect，使用 Kimi K3 完成开发。

第三方组件许可见应用内「关于 → 查看开源组件许可」，包括 llama.cpp（MIT）、commonmark-java（BSD-2-Clause）、AndroidSVG / MathJax / Kotlin / AndroidX / OpenCL（Apache-2.0）、Mermaid（MIT）。
