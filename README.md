# Bonsai Android

[Muka.Cool](https://muka.cool) 出品的 AI 模型推理引擎与前端，针对 Android 平台进行了适配与优化。模型来自 [PrismML](https://prismml.com/)。

在手机上本地运行 Bonsai 1-bit / 三值（ternary）大模型，无需联网推理。

## 功能特性

- **本地推理**：基于 llama.cpp，支持 Bonsai 1-bit 27B（Q1_0）、Ternary 1.58-bit 27B（Q2_0 g128），以及 **Bonsai 2 27B**：PQ2_0（2.13 bpw，OpenCL GPU 加速）与 PTQ1_0（1.75 bpw，OpenCL tiled GEMM 下预填充比 CPU 快 4.2 倍，但解码仍慢于 CPU，整体明显落后 PQ2_0）
- **权重折叠旋转**：Bonsai 2 权重以旋转基存储（`prism.hadamard.*`），运行时对激活做 Hadamard 变换还原，GPU 侧用 butterfly 内核完成，不读取显式旋转矩阵
- **流式渲染**：Markdown（GFM 表格/删除线）、LaTeX 公式（MathJax）、Mermaid 流程图边生成边渲染
- **思考气泡**：模型 think 内容独立折叠区块，思考过程可展开查看
- **模型管理**：内置下载（HF 镜像 / HuggingFace / ModelScope 三源）、导入本地 GGUF、多模态投影（mmproj）配对
- **推理参数**：按模型独立保存配置档案，新模型首次加载时以该模型 GGUF 内的推荐采样参数为初值；上下文长度（下限 32768，上限为模型自身窗口）、最大输出（64–32768）、线程数、温度、top-k/top-p、KV 缓存量化（F16/Q8_0/Q4_0）、图片细节（单张图的视觉 token 上限 256/1024/不限，越低视觉编码越快）
- **词表分析**：用当前模型的分词器统计输入文本的分词频次，并校验这些 token 能否还原成原文，支持按子串或正则过滤
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

子模块指向 fork [Attect/llama.cpp-muka](https://github.com/Attect/llama.cpp-muka) 的 `q2_0-g128` 分支，在上游 ggml-org/llama.cpp 之上带有定制提交：

- `block_q2_0` 块大小 64 → 128（PrismML 三值格式，34 字节/块），CPU 点积按 `QK2_0/QK8_0` 泛化
- 新增 Q2_0 OpenCL kernel（mul_mv / mul_mm / gemv / gemm）
- Bonsai 2 量化类型：`PQ2_0`（与 `block_q2_0` 逐字节同构，OpenCL 复用同一批内核）与 `PTQ1_0`（28 字节/块的三值 trit 编码）。PTQ1_0 无法原地拆成 q1_0/q2_0 那种 SoA（128 个 trit 按 2 bit 存需要 32 字节，块内去掉缩放只剩 26 字节），所以两个内核都直接读存储的块：`mul_mv_ptq1_0_f32`（gemv，按 trit run 整段处理）与 `mul_mm_ptq1_0_f32_l4_lm`（tiled GEMM，BM=BN=64、BK=32，把一段 K 反量化进局部内存供 64 列共享）。判据从 32 列起认领 batch，这一步同时才让权重真正落到设备上——llama 选择权重 buffer 时用的 mock 就是 512 列。实测（Adreno 830）：预填充 6.12 t/s 对 CPU 的 1.45（4.2 倍），同一提示下 GPU 与 CPU 贪心输出逐字一致；解码仍走 gemv，0.97 tok/s 对 CPU 的 1.29 —— 但 gemv 只以约 5.1 GiB/s 流权重，而 q2_0 的 gemv 能到 33 GiB/s。二分下来：把 trit 算术换成掩码只值 16%，把 y 全部钉在同一块（L1 命中）只值 6.6%，而 AoS 与 SoA 在这个内核里的读取形态本来就等价（每 lane 都是 1 次 4 字节装载覆盖 4 个权重），所以拆 SoA 并不能补上这 5 倍，剩下的是什么还没查清
- `prism.hadamard.*` 权重折叠运行时：解析元数据、materialize 旋转/符号表、在激活侧施加 `x' = H(s*x)`（查表侧施加逆变换），并在调度前校验计算图——若某个折叠权重缺少配套的激活变换则直接报错，而不是静默输出乱码
- OpenCL 侧以 butterfly FWHT 内核完成该变换（`rot` 即归一化 Sylvester-Walsh 矩阵），避免每 token 读取 1024×1024 稠密矩阵

## 测试

```bash
./gradlew :app:testDebugUnitTest          # 单元测试
./gradlew :app:connectedDebugAndroidTest  # 仪器测试（渲染链路，需设备）
```

量化类型与激活变换的数值对齐用交叉编译的 `test-backend-ops` 在真机 CPU 上跑（仓库根目录脚本，注意需把 Android SDK 的 `cmake\bin` 前置到 PATH，否则 Git Bash 下的 msys `ninja` 会让 configure 失败）：

```bash
./build_android_testops.bat        # 产出 arm64 的 test-backend-ops
adb push <输出目录>/test-backend-ops /data/local/tmp/
adb shell "cd /data/local/tmp && ./test-backend-ops test -b CPU -o MUL_MAT"
```

GPU 侧仍通过应用本身验证（独立二进制在 Android 上无法初始化 OpenCL：`libOpenCL.so` 由应用的链接器命名空间从 `/vendor/lib64` 解析）。

Debug 构建在「设置 → Mock 渲染测试」提供无需模型的流式渲染测试台：三种样本（长文本计数 / Markdown 混合含公式与流程图 / Think 流式），速度可调，用于验证流式渲染与滚动跟随。

## 开源许可

本项目以 [MIT License](LICENSE) 开源，Copyright (c) 2026 Attect。

开发者：Attect，使用 Kimi K3 完成开发。

第三方组件许可见应用内「关于 → 查看开源组件许可」，包括 llama.cpp（MIT）、commonmark-java（BSD-2-Clause）、AndroidSVG / MathJax / Kotlin / AndroidX / OpenCL（Apache-2.0）、Mermaid（MIT）。
