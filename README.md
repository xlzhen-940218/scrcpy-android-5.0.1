# scrcpy-android (v5.0.1)

<p align="center">
  <img src="docs/images/readme_main.jpg" width="270" alt="scrcpy-android 主界面" style="border-radius: 16px; margin: 6px;" />
  <img src="docs/images/readme_fullscreen.jpg" width="270" alt="scrcpy-android 全屏投屏" style="border-radius: 16px; margin: 6px;" />
  <img src="docs/images/readme_floating.jpg" width="270" alt="scrcpy-android 悬浮窗投屏" style="border-radius: 16px; margin: 6px;" />
</p>

<p align="center">
  <b>Android 之间免 PC、免 Root 的高性能、超低延迟屏幕镜像与音频传输客户端，深度同步官方 Genymobile Scrcpy v5.0.1 协议。</b><br/>
  <b>High-performance, low-latency Android-to-Android screen mirroring and audio forwarding client powered by official Genymobile Scrcpy v5.0.1.</b>
</p>

<p align="center">
  <a href="#-简体中文"><b>简体中文</b></a> &nbsp;|&nbsp; <a href="#-english"><b>English</b></a>
</p>

<p align="center">
  <a href="https://github.com/xlzhen-940218/scrcpy-android/releases"><img src="https://img.shields.io/badge/Release-Download%20APK-brightgreen?logo=android&logoColor=white" alt="Download APK" /></a>
  <img src="https://img.shields.io/badge/scrcpy-v5.0.1-blue.svg" alt="scrcpy v5.0.1" />
  <img src="https://img.shields.io/badge/Kotlin-100%25-purple.svg" alt="Kotlin 100%" />
  <img src="https://img.shields.io/badge/Platform-Android%205.0%2B-green.svg" alt="Platform" />
  <img src="https://img.shields.io/badge/Target%20SDK-37-brightgreen.svg" alt="Target SDK" />
  <img src="https://img.shields.io/badge/Design-Material%203-teal.svg" alt="Material 3" />
  <img src="https://img.shields.io/badge/License-GPLv3%20%2F%20Apache%202.0-orange.svg" alt="License" />
</p>

---

## 🇨🇳 简体中文

### 🌟 概述

**scrcpy-android** 是一款能够在两台 Android 设备之间（手机、平板、Android TV / 电视盒子）直接进行屏幕镜像、反向控制以及实时音频传输的开源客户端。无需电脑中转，两台设备均**无需 Root**。

本项目基于官方 [Genymobile/scrcpy v5.0.1](https://github.com/Genymobile/scrcpy) 协议与服务端内核升级，全项目代码已完成 **100% Kotlin 现代化重构**。不仅支持硬件加速解码（H.264 / H.265 / AV1）、超低延迟音频转发（OPUS / AAC / PCM）、USB OTG 极速有线直连、Android 11+ 原生无线配对、双向横竖屏自适应同步，更全新引入了**黄金螺旋悬浮工具菜单**、**scrcpy 原生通道跨端文件传输与自动归类**、**UHID 鼠标键盘控制模式与软键盘中文输入**，以及现代化的 Material Design 3 风格界面。

---

### 📥 下载安装

无需自行配置环境编译，直接前往本项目 GitHub Releases 页面下载预编译好的 APK 安装包即可：

👉 [**点击前往 GitHub Releases 下载最新 APK 安装包**](https://github.com/xlzhen-940218/scrcpy-android/releases)

> [!TIP]
> 建议下载标有 **`Latest`** 标签的最新发布版中的 `app-release.apk` 或 `app-debug.apk`。

---

### ✨ 核心特性

#### 🌀 全新黄金螺旋悬浮工具菜单（Radial Menu）
- **功能一体化聚合**：全面替代以往底部固定操作栏、左上角截图拉环与右上角录制拉环，将所有快捷操作深度集成进单颗浮动手势工具按钮中，投屏视野 100% 纯净无遮挡。
- **黄金螺旋（Golden Spiral）展开算法**：展开菜单按黄金比例与黄金螺旋轨迹延展分布，图标错落有致、间距通透舒适，告别拥挤与误触。
- **长按自由拖拽与边缘半隐藏吸附**：长按悬浮按钮即可自由拖拽至屏幕任意位置。手指松开时若处于边缘，自动吸附贴边并半隐藏收缩，最小化视觉干扰。
- **四周及角落智能避让（Corner Auto-Repulsion）**：贴靠屏幕四边缘或角落点击展开时，算法自动检测屏幕边界，向相反方向智能偏移展开方向，确保最外层的截图、录制、文件传输等按钮永远 100% 完整可见。
- **快速滑动选取手势（Slide-to-Select）**：按下按钮即可即时展开菜单，手指不离屏幕滑向目标功能高亮选中，松手即刻执行对应操作；亦可轻点展开后直接点击。
- **动态录制计时球**：开启录制后，悬浮工具按钮化身为动态秒表实时展示录制时长（分:秒）；点击弹出停止确认提示，长按仍可呼出完整螺旋工具菜单。

#### 📁 scrcpy 原生通道文件传输与自动归类
- **走现有协议通道直传**：无需依赖局域网 HTTP 或额外服务端，直接通过现有的 scrcpy / ADB 数据通道秒传大文件。
- **受控端智能归类存储**：文件推送至受控端 `/sdcard/Scrcpy/` 根目录，并根据扩展名自动分门别类：
  - `Pictures/`：JPG, PNG, GIF, WebP, SVG, HEIC, RAW 等
  - `Videos/`：MP4, MKV, AVI, MOV, FLV, TS 等
  - `Music/`：MP3, WAV, FLAC, AAC, OGG, M4A, Opus 等
  - `Documents/`：PDF, DOC/DOCX, XLS/XLSX, PPT/PPTX, TXT, MD, EPUB 等
  - `APKs/`：APK, APKS, XAPK, AAB 安装包
  - `Others/`：其他未知类型文件
- **媒体库自动刷新（MediaScanner）**：传输完成后通过 scrcpy 协议触发媒体扫描器，图片、音频与视频即刻出现在系统相册与媒体库中，无需重启设备。
- **全面支持中文与特殊字符**：底层融合安全临时文件传输与 POSIX Shell 安全转义，完美解决包含中文、空格、括号及第三方文件管理器 SAF 传输时的缓冲区溢出（BufferOverflowException）与权限问题。

#### ⌨️ 鼠标键盘（UHID）控制模式与中文输入
- **原生 UHID 键鼠支持**：首页高级选项支持开启“鼠标键盘模式（UHID 模式）”，对齐官方 `scrcpy --keyboard=uhid --mouse=uhid` 规范。
- **物理键鼠即插即用映射**：控制端插入外接键盘或鼠标时，硬件按键码、指针位移、左右键及滚轮通过原生 UHID HID 报告直接映射至受控端，游戏与办公体验媲美本地连接。
- **远端软键盘与中文输入**：悬浮工具菜单配备专用“键盘”按钮。点击即调出本地虚拟软键盘，支持拼音中文输入与文字内容精准提交至受控端。

#### 🚀 连接与调试
- **USB 数据线直连（OTG 模式）**：通过 Type-C 数据线或 OTG 转接头将两台手机连接，直接走 USB Bulk 传输。彻底告别 Wi-Fi 干扰，带宽充足、帧率饱满、延迟极低。
- **Android 11+ 原生无线调试**：内置 TLS 证书与配对流程。输入受控设备的 6 位配对码和端口，一次配对即可长效连接。
- **传统 Wi-Fi ADB 支持**：全面兼容 `adb tcpip 5555` 经典连接方式。
- **连接历史记录**：自动保存最近 5 条成功连接记录，点击 IP 输入框即可从下拉菜单一键回填，无需重复手动输入。
- **设备断开自动感知**：投屏过程中拔出数据线或 Wi-Fi 网络异常时，自动弹窗提示并安全退出至首页，防止界面冻结或崩溃。

#### 📺 高性能音视频与同步
- **硬解 60 FPS 超清画质**：基于 Android 原生 `MediaCodec` 硬件解码器，支持 **H.264 (AVC)**、**H.265 (HEVC)** 与 **AV1** 编码格式。
- **原始分辨率点对点渲染**：支持"原始分辨率（不限制）"，根据受控端分辨率动态适配画面黑边（Letterboxing / Pillarboxing），杜绝拉伸变形。
- **实时音频转发**：将被控设备的游戏声、音乐与系统音频近乎零延迟同步输出到本端扬声器或耳机，支持 **OPUS**、**AAC** 与 **RAW PCM** 解码。
- **双向旋转同步**：控制端手机横竖屏翻转时，受控端会自动同步旋转；同时悬浮工具菜单配备旋转按钮，随时随心切换。

#### 📸 屏幕截图与 🎥 高清录屏（含音频）
- **一键快捷截图**：悬浮菜单一键触发，直接提取当前渲染帧无损保存至系统相册 `Pictures/scrcpy` 目录。
- **音画同步高清录屏**：
  - **GPU Surface 硬件级编码**：采用 `MediaCodec.createInputSurface()` + OpenGL ES 2.0 硬件加速编码，画质细腻、流畅稳定、系统开销极小；
  - **横竖屏自适应居中**：受控设备横竖屏切换时，画面在视频中自适应保持真实比例并黑边居中展示（Letterbox / Pillarbox），绝不拉伸变形；
  - **投屏音频混流**：内置 AAC 硬件编码器，投屏声音（游戏原声、音乐等）与画面高精度时间戳对齐，混流输出至标准 MP4 文件，存放在系统 `Movies/scrcpy` 目录。

#### 🔋 省电与悬浮窗模式
- **熄屏镜像自动唤醒恢复**：投屏期间受控设备可关闭物理屏幕，大幅减少发热并延长被控手机电池寿命；退出投屏界面时**自动恢复受控设备屏幕状态**，无需手动按电源键。
- **保持唤醒**：防止被控设备在操作过程中息屏锁屏。
- **画中画悬浮窗**：支持多任务悬浮窗播放。悬浮窗自带右下角**等比例自由缩放手柄（⤡）**、拖拽移动、旋转按钮及最小化隐藏。

#### 🌙 深色模式 & 界面体验
- **一键夜间模式**：应用右上角切换按钮即可在浅色 / 深色主题间切换，护眼且省电，偏好自动记忆。
- **Material Design 3 全新 UI**：全局采用 Material You 动态配色、圆角卡片、流畅动效，视觉体验现代而精致。
- **平板 / 手机双端适配**：响应式双栏布局，在大屏平板上信息密度更高，操作效率更强。
- **100% Kotlin 现代化代码基架**：全面采用 Kotlin 重构原有 Java 代码，提升代码稳健性与执行效率。
- **完整多语言（i18n）**：应用界面与日志全面支持中文与英文无缝切换。

---

### 📖 使用指南

#### 方法一：USB 数据线连接（推荐，延迟最低）
1. 在**受控手机**上开启【开发者选项】→ 开启【USB 调试】。
2. 使用 Type-C 双头线或 OTG 转接头连接两台 Android 设备。
3. 打开 **scrcpy-android**，顶部切换至 **【USB 数据线连接】** 标签页。
4. 点击 **【检测并连接 USB 设备】**，在弹出的系统权限弹窗中勾选"始终允许"并确认。
5. 握手成功后，点击 **【全屏投屏】** 或 **【悬浮窗投屏】**。
6. 使用完毕直接拔下数据线即可，程序会自动退出投屏并返回主页。

#### 方法二：Wi-Fi 无线调试配对（Android 11+）
1. 确保两台设备处于同一个局域网（Wi-Fi 或手机热点）。
2. 在**受控手机**上进入【设置】→【开发者选项】→【无线调试】。
3. 点击【使用配对码配对设备】，屏幕将显示 6 位配对码以及 IP 和临时配对端口。
4. 在 **scrcpy-android** 首页点击 **【无线配对 (Android 11+)】**，输入显示的 IP、端口和 6 位配对码，点击【开始配对】。
5. 配对成功后，将受控手机无线调试主界面显示的**连接端口**输入到首页 IP 输入框中（例如 `192.168.1.108:39123`）。
6. 点击 **【全屏投屏】** 即可。

> [!TIP]
> 曾连接过的设备 IP 会出现在历史记录模块，点击 IP 输入框时也可从下拉菜单中直接选择。

#### 方法三：传统 Wi-Fi ADB（Android 5 – 10）
1. 将受控手机通过电脑运行一次：
   ```bash
   adb tcpip 5555
   ```
2. 在 **scrcpy-android** 输入受控设备局域网 IP（例如 `192.168.1.108:5555`）。
3. 点击 **【全屏投屏】** 开始使用。

---

### 🛠️ 编译与开发

#### 环境要求
- **Android Studio** Ladybug / Koala / Jellyfish 或更新版本
- **JDK 17** 或 **JDK 21**
- **Android SDK** (API Level 34+)

#### 命令行编译构建
```bash
# 克隆仓库
git clone https://github.com/xlzhen-940218/scrcpy-android.git
cd scrcpy-android

# 编译生成 Debug 安装包
./gradlew assembleDebug
```

编译生成的 APK 路径：
```
app/build/outputs/apk/debug/app-debug.apk
```

---

### 🙏 鸣谢与开源致谢

本项目离不开以下优秀的开源项目、库及社区贡献者的启发与支持：

- **[Genymobile/scrcpy](https://github.com/Genymobile/scrcpy)**：由 Romain Vimont 及 Genymobile 团队打造的官方屏幕镜像核心，本项目底层协议与服务端均基于官方 v5.0.1 构建。
- **[huage2580/scrcpy-android](https://github.com/huage2580/scrcpy-android)**：Android 端投屏客户端的早期探索者与上游基础项目。
- **[com.tananaev:adblib](https://github.com/tananaev/adblib)**：轻量级 Java ADB 协议通信库，用于经典 ADB Socket 建立与数据流转发。
- **[com.github.MuntashirAkon:libadb-android](https://github.com/MuntashirAkon/libadb-android)**：现代 Android ADB 客户端实现，支持 Android 11+ TLS 无线配对与免 Root 调试。
- **[Google Conscrypt](https://github.com/google/conscrypt)** & **[Bouncy Castle](https://www.bouncycastle.org/)**：为 Android 11+ TLS 密钥协商与安全连接提供底层加密保障。
- **[Google Material Components](https://github.com/material-components/material-components-android)**：提供现代化 Material Design 3 风格组件与交互支持。

---

## 🇬🇧 English

### 🌟 Overview

**scrcpy-android** is an open-source Android application that enables high-performance screen mirroring, low-latency audio forwarding, and remote touch control between two Android devices (smartphones, tablets, and Android TV / Google TV boxes). It operates directly device-to-device over **USB OTG** or **Wi-Fi**, requiring **no PC** and **no root access** on either device.

Built on top of the official [Genymobile/scrcpy v5.0.1](https://github.com/Genymobile/scrcpy) protocol and server backend, the entire codebase has been **100% modernized in Kotlin**. It delivers hardware-accelerated video decoding (H.264 / H.265 / AV1), real-time audio playback (OPUS / AAC / PCM), USB OTG bulk transfer, Android 11+ wireless TLS pairing, dynamic orientation synchronization, an innovative **Golden Spiral Floating Radial Menu**, **direct scrcpy-channel file transfer with auto-categorization**, **UHID mouse/keyboard control & remote soft keyboard input**, and a refined Material Design 3 interface.

---

### 📥 Download & Installation

Prebuilt APK packages are ready to install directly from the GitHub Releases page of this repository without compiling from source:

👉 [**Download Latest APK from GitHub Releases**](https://github.com/xlzhen-940218/scrcpy-android/releases)

> [!TIP]
> Download `app-release.apk` or `app-debug.apk` under the release tagged with **`Latest`** for the newest features and improvements.

---

### ✨ Key Features

#### 🌀 Golden Spiral Floating Radial Menu
- **All-in-One Floating Hub**: Replaces traditional fixed navigation bars, pull-tabs, and scattered overlays with a single draggable floating action button, giving you an entirely unblocked fullscreen mirroring experience.
- **Golden Spiral Fan-Out Layout**: Function buttons expand following the organic Golden Spiral curvature, offering ample spacing, zero clutter, and natural finger reachability.
- **Drag & Auto-Docking**: Long-press to drag the button anywhere. Releasing near any screen edge automatically docks and semi-hides the button to minimize distraction.
- **Corner & Edge Auto-Repulsion**: When expanded near edges or corners, the layout intelligently detects boundaries and offsets inward, ensuring outer buttons (Screenshot, Recording, File Transfer) remain 100% visible on screen.
- **Slide-to-Select Gesture**: Press and hold to expand, slide your finger toward the desired action to highlight it, and release to trigger instantly — or simply tap to open and tap to select.
- **Embedded Recording Stopwatch**: Once recording starts, the floating button transforms into a live digital stopwatch (MM:SS). Tapping prompts to confirm stopping, while long-pressing still reveals the full radial menu.

#### 📁 Scrcpy-Channel File Transfer with Auto-Categorization
- **Direct Protocol Streaming**: Transfers files directly through the established scrcpy / ADB data stream without requiring external HTTP servers or local network sharing.
- **Remote Auto-Categorization**: Incoming files are organized automatically on the controlled device under `/sdcard/Scrcpy/`:
  - `Pictures/`: JPG, PNG, GIF, WebP, SVG, HEIC, RAW, etc.
  - `Videos/`: MP4, MKV, AVI, MOV, FLV, TS, etc.
  - `Music/`: MP3, WAV, FLAC, AAC, OGG, M4A, Opus, etc.
  - `Documents/`: PDF, DOC/DOCX, XLS/XLSX, PPT/PPTX, TXT, MD, EPUB, etc.
  - `APKs/`: APK, APKS, XAPK, AAB package bundles
  - `Others/`: Uncategorized file formats
- **Automatic MediaScanner Trigger**: Calls scrcpy's media scanner command upon transfer completion, making photos, music, and videos immediately discoverable in system galleries.
- **Robust Unicode & Chinese Filename Support**: Employs atomic temp-file streaming, base64 shell wrapping, and POSIX single-quote escaping to eliminate buffer overflows and SAF file provider path issues.

#### ⌨️ Mouse & Keyboard (UHID) Mode with Soft Keyboard
- **Official UHID HID Protocol**: Supports Scrcpy v5.0 UHID keyboard and mouse mode (`--keyboard=uhid --mouse=uhid`).
- **Physical Peripheral Forwarding**: When a physical mouse or keyboard is attached to the controlling device, hardware HID scan codes, cursor moves, clicks, and scroll events are passed through directly.
- **Remote Soft Keyboard**: Dedicated keyboard icon in the radial menu toggles an on-screen soft keyboard, enabling full remote text input and Chinese Pinyin composition.

#### 🚀 Connectivity
- **USB OTG Direct Connect**: Connect two Android devices directly using a USB-C to USB-C cable or an OTG adapter. Leverages USB bulk transfer for maximum throughput, zero Wi-Fi interference, and minimal latency.
- **Android 11+ Wireless Debugging**: Built-in TLS keypair generation and pairing workflow. Enter the target device's 6-digit pairing code and port once to pair and establish wireless connections.
- **Legacy Wi-Fi ADB**: Full backward compatibility with `adb tcpip 5555` connections.
- **Connection History**: Automatically saves up to 5 recently connected addresses. Tap the IP input field to select from a dropdown — no retyping needed.
- **Disconnect Auto-Return**: Automatically senses USB cable unplugs or network dropouts, alerts the user via Toast, and gracefully returns to the home dashboard.

#### 📺 Display & High-Fidelity Audio
- **Hardware-Accelerated 60 FPS**: Native `MediaCodec` pipeline with **H.264 (AVC)**, **H.265 (HEVC)**, and **AV1** decoding support.
- **Native Resolution**: Render at the remote device's unscaled resolution with dynamic letterboxing and pillarboxing, eliminating stretched screens.
- **Real-Time Audio Forwarding**: Streams audio from the controlled device directly to your speakers or headphones using **OPUS**, **AAC**, or **RAW PCM**.
- **Bi-Directional Rotation Sync**: Senses screen rotation on the controlling device and instructs the remote server to adapt orientation instantly, plus a dedicated manual rotation button in the radial menu.

#### 📸 Screen Capture & 🎥 Screen Recording (with Audio)
- **One-Tap Screen Capture**: Triggered directly from the radial menu, saving lossless frames into `Pictures/scrcpy`.
- **Synchronized HD Screen Recording with Audio**:
  - **GPU Surface Hardware Encoding**: Powered by `MediaCodec.createInputSurface()` + OpenGL ES 2.0 hardware-accelerated pipeline for crisp video quality, smooth framerates, and minimal battery consumption;
  - **Adaptive Centered Aspect Ratio**: Automatically centers remote screen display with black letterboxing / pillarboxing when rotating between portrait and landscape — zero distortion or stretching;
  - **Audio Muxing**: Embedded AAC hardware encoder captures real-time audio (gameplay sound, media audio) and muxes synchronized audio tracks into standard MP4 files saved to `Movies/scrcpy`.

#### 🔋 Power Saving & Floating Window
- **Turn Screen Off & Auto-Restoration**: Mirror and interact while the remote physical screen remains turned off to conserve battery and avoid overheating; **automatically wakes the remote screen back up** when exiting mirroring.
- **Stay Awake**: Keep the controlled device awake during active sessions.
- **Floating Window Mode (PiP)**: Keep the remote screen in a movable, resizable overlay. Includes a bottom-right **drag-to-resize handle (⤡)** with locked aspect ratios, rotation toggle, and minimize bar.

#### 🌙 Dark Mode & UI
- **One-Tap Dark Mode**: Toggle between light and dark themes from the top-right icon. Preference is persisted across sessions.
- **Material Design 3**: Dynamic Material You color system, rounded card layouts, and fluid micro-animations throughout.
- **Tablet / Phone Adaptive Layout**: Responsive two-column layout on large screens for higher information density and better ergonomics.
- **100% Kotlin Codebase**: Pure Kotlin rewrite across all app modules for improved safety, conciseness, and performance.
- **Complete Internationalization (i18n)**: Fully localized for Simplified Chinese (简体中文) and English.

---

### 📖 Usage Instructions

#### Method 1: USB OTG Connection (Recommended for Lowest Latency)
1. On the **controlled phone**, enable **Developer Options** → **USB Debugging**.
2. Connect both devices with a USB-C cable or OTG adapter.
3. Open **scrcpy-android**, switch to the **USB Connection** tab.
4. Tap **Scan & Connect USB Device** and grant USB permission when prompted.
5. Tap **Full-Screen Mirror** or **Floating Window**.
6. When done, simply unplug the cable — the app handles cleanup and navigates back to the main screen.

#### Method 2: Wi-Fi Wireless Debugging (Android 11+)
1. Connect both devices to the same Wi-Fi network (or personal hotspot).
2. On the **controlled phone**, navigate to **Settings** → **Developer Options** → **Wireless Debugging**.
3. Tap **Pair device with pairing code**.
4. In **scrcpy-android**, tap **Wireless Pairing (Android 11+)**, input the displayed IP address, pairing port, and 6-digit code, then tap **Pair**.
5. After pairing succeeds, enter the **Wireless Debugging connection port** into the IP field (e.g., `192.168.1.108:39123`).
6. Tap **Full-Screen Mirror**.

> [!TIP]
> Previously connected devices appear in the History section. You can also tap the IP field and select an address from the dropdown.

#### Method 3: Legacy Wi-Fi ADB (Android 5 – 10)
1. Connect the controlled device once to a computer and execute:
   ```bash
   adb tcpip 5555
   ```
2. Enter the target phone's IP address (e.g., `192.168.1.108:5555`) in **scrcpy-android**.
3. Tap **Full-Screen Mirror**.

---

### 🛠️ Build & Development

#### Prerequisites
- **Android Studio** Ladybug / Koala / Jellyfish or newer
- **JDK 17** or **JDK 21**
- **Android SDK** (API level 34+)

#### Command-Line Build
```bash
git clone https://github.com/xlzhen-940218/scrcpy-android.git
cd scrcpy-android
./gradlew assembleDebug
```

Output APK will be located at:
```
app/build/outputs/apk/debug/app-debug.apk
```

---

### 🙏 Acknowledgements & Credits

This project is made possible thanks to the following outstanding open-source projects, libraries, and authors:

- **[Genymobile/scrcpy](https://github.com/Genymobile/scrcpy)**: The official high-performance screen mirroring solution created by Romain Vimont and Genymobile team. The core protocol and server backend of this project are powered by official scrcpy v5.0.1.
- **[huage2580/scrcpy-android](https://github.com/huage2580/scrcpy-android)**: Upstream project and early pioneer of scrcpy client on Android.
- **[com.tananaev:adblib](https://github.com/tananaev/adblib)**: Lightweight Java ADB protocol library for classic TCP/IP ADB connection and socket streams.
- **[com.github.MuntashirAkon:libadb-android](https://github.com/MuntashirAkon/libadb-android)**: Modern Android ADB library powering native Android 11+ TLS wireless debugging and pairing.
- **[Google Conscrypt](https://github.com/google/conscrypt)** & **[Bouncy Castle](https://www.bouncycastle.org/)**: Cryptographic security providers for modern TLS key exchange and encryption.
- **[Google Material Components](https://github.com/material-components/material-components-android)**: Modern UI widgets and Material Design 3 styling.

---

## 🏗️ Architecture & Modules

```
scrcpy-android/
├── app/                              # Android client application (100% Kotlin)
│   ├── src/main/java/org/las2mile/scrcpy/
│   │   ├── MainActivity.kt           # Material 3 dashboard, connection history & settings
│   │   ├── ScreenMirrorActivity.kt   # Full-screen mirroring, orientation sync & file handling
│   │   ├── RadialMenuLayout.kt       # Golden Spiral floating action button & radial fan-out menu
│   │   ├── RemoteInputView.kt        # Soft keyboard & remote text input view
│   │   ├── DisplayWindow.kt          # Resizable floating window & touch event translation
│   │   ├── FloatService.kt           # Foreground overlay service with SVG button controls
│   │   ├── ScreenCaptureUtil.kt      # PixelCopy screenshot capture to Pictures/scrcpy
│   │   ├── ScreenRecorder.kt         # Hardware Surface video & AAC audio recorder (Movies/scrcpy)
│   │   ├── EglSurfaceRenderer.kt     # OpenGL ES 2.0 EGL renderer with adaptive aspect centering
│   │   ├── Scrcpy.kt                 # Protocol stream demuxer & client coordinator
│   │   ├── SendCommands.kt           # ADB protocol engine & server deployment
│   │   ├── protocol/ControlMessage.kt# Scrcpy control protocol packets (UHID, Scan, Clipboard, Touch)
│   │   ├── adb/                      # TLS keypair negotiation & wireless pairing
│   │   ├── decoder/VideoDecoder.kt   # Hardware-accelerated MediaCodec video decoding
│   │   ├── audio/AudioPlayer.kt      # AudioTrack playback (RAW PCM / OPUS / AAC)
│   │   └── usb/                      # USB Host ADB bridge & bulk packet forwarder
│   └── src/main/res/                 # Layouts, SVG drawables, and bilingual strings (zh / en)
│
└── server/                           # Scrcpy v5.0.1 Java server
    └── src/main/java/com/genymobile/scrcpy/
        ├── Server.java               # Scrcpy server entry point
        ├── Device.java               # Display capture, touch injection, orientation lock
        ├── Controller.java           # Control message processor (UHID, touch, keys)
        ├── video/                    # Screen encoder & virtual display pipeline
        └── audio/                    # Audio capture & encoding pipeline
```

---

## 📄 License

- **Client application (`app`)**: Licensed under the [GNU General Public License v3.0 (GPLv3)](LICENSE).
- **Server component (`server`)**: Licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).
- Based on the desktop project [Genymobile/scrcpy](https://github.com/Genymobile/scrcpy).
