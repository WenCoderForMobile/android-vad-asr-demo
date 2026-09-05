# 语音输入 Demo

**中文** | [English](README.en.md)

该 Demo 是一个语音识别与输入的示例 DEMO。参照开源项目 [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) 的 VadAsr 搭建。可以运行在 Android arm64-v8a 的手机。

**关键词：** sherpa-onnx, VadAsr, VAD, ASR, Silero, Paraformer, speech-to-text, speech recognition, voice input, on-device, offline, Android, arm64-v8a, ONNX, JNI, 语音识别, 语音输入, 离线识别

> `assets/sherpa-onnx-paraformer-zh-2023-09-14` 中的 `model.int8.onnx` 未上传。可参照下文 [ASR（Paraformer 中文，type = 0）](#asr-paraformer-zh) 下载，放入目录：`app/src/main/assets/sherpa-onnx-paraformer-zh-2023-09-14/`。

## 1. 主要功能

- 点「开始语音输入」：申请麦克风、开始录音，按钮变为「停止」。
- 说话过程中由 VAD 按停顿自动切段，每段交给非流式 ASR 识别。
- 点「停止」：结束录音，冲掉尚未切出的最后一段，把识别结果打印在界面上。

不连网、不连服务端，识别全部在手机本地完成。

## 2. 主要原理

采用 sherpa-onnx 的 **VadAsr**（VAD + 非流式 ASR），官方示例路径：

<https://github.com/k2-fsa/sherpa-onnx/tree/master/android/SherpaOnnxVadAsr>

```text
麦克风 PCM 16kHz / 16bit / 单声道
        │
        ▼
Silero VAD（silero_vad.onnx）
  按停顿切开一段段语音
        │
        ▼
非流式 OfflineRecognizer（paraformer-zh）
  每段一次性解码
        │
        ▼
点击「停止」→ vad.flush() → 拼接文本，打印到界面
```

| 组件 | 开源配置 | 作用 |
|------|----------|------|
| VAD | `getVadModelConfig(type = 0)` Silero | 检测语音起止，停顿约 0.25s 切一段 |
| ASR | `getOfflineModelConfig(type = 0)` Paraformer 中文 | 对切出的波形做非流式识别 |

点「停止」时调用 `vad.flush()`，把最后一段尚未因静音切开的语音也送去识别。

开源地址：

- 仓库：<https://github.com/k2-fsa/sherpa-onnx>
- 文档：<https://k2-fsa.github.io/sherpa/onnx/index.html>
- Android 示例：<https://github.com/k2-fsa/sherpa-onnx/tree/master/android>
- 模型 Release：<https://github.com/k2-fsa/sherpa-onnx/releases>

## 3. 如何从开源搭建

产物只有三类，都从 sherpa-onnx 公开仓库 / Release 取得，不依赖本仓库里其它工程。

### 3.1 克隆源码，拷贝 Kotlin 绑定

Vad / OfflineRecognizer 的 JNI 封装在官方 VadAsr 工程里，直接拷到本 App：

```bash
git clone https://github.com/k2-fsa/sherpa-onnx.git
cd sherpa-onnx

cp android/SherpaOnnxVadAsr/app/src/main/java/com/k2fsa/sherpa/onnx/*.kt \
   <本工程>/app/src/main/java/com/k2fsa/sherpa/onnx/
```

本工程界面在 `app/src/main/java/com/autoprocedure/voice/MainActivity.kt`，只负责录音、起停按钮和把识别文本画到界面。

### 3.2 编译 `.so`（官方脚本）

JNI 需要两个动态库，放到 `app/src/main/jniLibs/arm64-v8a/`：

| 文件 | 作用 |
|------|------|
| `libsherpa-onnx-jni.so` | Kotlin ↔ C++ JNI |
| `libonnxruntime.so` | ONNX Runtime 推理 |

前置：已安装 Android NDK（官方脚本常用 27.x）。

```bash
export ANDROID_NDK=$ANDROID_HOME/ndk/27.3.13750724   # 按本机 NDK 路径改
export ANDROID_HOME=$ANDROID_HOME

cd sherpa-onnx
./build-android-arm64-v8a.sh
```

脚本会从开源地址拉取 ONNX Runtime，再用 CMake + NDK 交叉编译。产物：

```text
build-android-arm64-v8a/install/lib/
├── libsherpa-onnx-jni.so
└── libonnxruntime.so
```

复制进本工程：

```bash
mkdir -p app/src/main/jniLibs/arm64-v8a
cp sherpa-onnx/build-android-arm64-v8a/install/lib/libsherpa-onnx-jni.so \
   sherpa-onnx/build-android-arm64-v8a/install/lib/libonnxruntime.so \
   app/src/main/jniLibs/arm64-v8a/
```

Kotlin 侧由 `Vad.kt` / `OfflineRecognizer.kt` 的 `System.loadLibrary("sherpa-onnx-jni")` 加载。当前只编 `arm64-v8a`，用 arm64 真机即可。模拟器要 x86_64 时，改跑官方的 `build-android-x86-64.sh`。

### 3.3 下载模型（官方 Release）

模型放进 `app/src/main/assets/`，运行时经 `AssetManager` 读取。发布页：

<https://github.com/k2-fsa/sherpa-onnx/releases>

**VAD（Silero）**

```bash
mkdir -p app/src/main/assets
cd app/src/main/assets
curl -L -O https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx
```

对应 `getVadModelConfig(0)` 的 `model = "silero_vad.onnx"`。

<a id="asr-paraformer-zh" href="#asr-paraformer-zh">ASR（Paraformer 中文，type = 0）</a>

`model.int8.onnx` 约 232MB，超过 GitHub 100MB 限制，不纳入仓库，请按下面步骤自行下载。

```bash
cd app/src/main/assets
curl -L -O https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-paraformer-zh-2023-09-14.tar.bz2
tar xjf sherpa-onnx-paraformer-zh-2023-09-14.tar.bz2
rm sherpa-onnx-paraformer-zh-2023-09-14.tar.bz2
# 压缩包里还有 README、测试 wav 等，只保留 onnx / txt
find sherpa-onnx-paraformer-zh-2023-09-14 -type f ! \( -name '*.onnx' -o -name '*.txt' \) -delete
find sherpa-onnx-paraformer-zh-2023-09-14 -type d -empty -delete
```

最终目录：

```text
app/src/main/assets/
├── silero_vad.onnx
└── sherpa-onnx-paraformer-zh-2023-09-14/
    ├── model.int8.onnx
    └── tokens.txt
```

目录名、文件名必须与 `OfflineRecognizer.kt` 里 `getOfflineModelConfig(0)` 一致。

## 4. 运行

用 Android Studio 打开本目录，连 arm64 真机 Run。点开始说话，点停止后，识别文本出现在界面上。
