# 袁神的格式工坊 · Yuan Format Forge

一个纯本机、离线运行的安卓文件格式转换工具箱。**内置 FFmpeg 6.0**，图片 / PDF / PPTX / 文档 / 音频 / 视频 / 文本乱码复原全部在手机上完成——不联网、不上传文件、无广告、无账号。

- 包名 `com.fmtlab.toolbox`，版本 1.3.2（versionCode 6）
- minSdk 24（Android 7.0），targetSdk 34
- 体积 56.8 MB（56,845,706 字节），原生库覆盖 `arm64-v8a` 与 `armeabi-v7a`
- 不用 Gradle、不引 AndroidX，纯 framework + 命令行工具链出包
- **APK 未声明任何权限**（连 `INTERNET` 都没有）

> **1.3.2 修复闪退**：1.3.1 引入的标签滚动处理有一处类型错误——对本来就在 `HorizontalScrollView` 里的标签容器调用了 `setLayoutParams(new ViewGroup.LayoutParams(...))`。`HorizontalScrollView` 是 `FrameLayout`，子 View 的布局参数必须是 `FrameLayout.LayoutParams`；换成基类后，测量阶段把它强转成 `MarginLayoutParams` 就抛 `ClassCastException`，界面还没画出来就闪退。受影响的是文本乱码修复、PDF、视频、万能转码、文档这几个把选项行预先放进滚动容器的页面。现在该分支直接保留 XML 原有参数，不再改写。

> **1.3.1 修复**：选项标签（比如音频提取的三个输出方式）原本排在一条固定宽度的横向布局里，标签一多就把最后一个挤成极窄的一条、文字竖排成一列。现在所有选项行都会自动套进横向滚动容器，标签保持自然宽度，放不下时左右滑动，不再变形。同时把音频模块的标签缩成 `WAV / M4A / AAC 裸流`，一行放得下。

## 功能

首页九张卡片：

| # | 模块 | 能力 |
|---|---|---|
| 1 | 文本 · 乱码复原 | 自动识别 UTF-8 / GBK / Big5 / Shift_JIS / EUC-KR / UTF-16(BOM) / Windows-1252；还原 `锟斤拷`、`å¤`、`Ã©`、`浣犲ソ` 类乱码；按 6 种编码另存 |
| 2 | 图片 | PNG / JPG / WEBP / BMP 批量互转、质量滑杆、缩放档位、EXIF 横竖纠正 |
| 3 | PDF | PDF ⇄ 图片（2×/3×/4×，可指定页码）；图片合成 PDF；**PDF → PPTX**；**PPTX → PDF** |
| 4 | 文档 | docx / xlsx / pptx / html / md / txt / csv → Markdown / HTML / TXT / CSV，带预览 |
| 5 | 音频提取 | 视频 → WAV / M4A（AAC 直拷）/ AAC 裸流（手写 ADTS 头） |
| 6 | 视频工具 | MP4 / WebM 换壳、GIF 动图、抽帧 JPG；系统解不了的封装自动切 FFmpeg 内核 |
| 7 | 万能转码 | 内置 FFmpeg：14 种输出格式，三档画质，实时进度与倍速，可取消 |
| 8 | 编码工具箱 | Base64 / URL / HEX / Unicode 双向编解码 |
| 9 | 关于与许可 | 版本信息、GPLv3 声明、第三方组件清单、源码获取方式、隐私说明 |

### PDF ⇄ PPTX（v1.3 新增）

- **PDF → PPTX**：每页 PDF 用 `PdfRenderer` 渲染成高清 JPEG（2×/3×/4×），再按 ECMA-376 写成一个「满版图片」幻灯片。版式、字号、图表、扫描件全部保真，代价是 PPTX 里的文字不可编辑。纯 Java 手写 OOXML（`core/PptxWriter.java`），不依赖任何 office 库。
- **PPTX → PDF**：`core/PptxReader.java` 解析 `presentation.xml` 的 `sldIdLst` 与关系表，按真实页序读取每页文字与内嵌图片；有整页图片的幻灯片按原图铺满还原，纯文字幻灯片按段落重新排版成 PDF（`core/PdfExport.java`）。已用 python-pptx 生成的真实 PPTX 验证：页序、页尺寸（720×540 pt）、中英混排文字、图片提取全部正确。

### 视频模块（v1.3 改）

- **GIF 改走 FFmpeg 内核**：`fps + scale + palettegen(256, stats_mode=diff) + paletteuse(dither=sierra2_4a)`，全局调色板，不再逐帧局部色表，播放不闪色、实拍渐变不出网格噪点。
- **新增起始时间 / 时长**：可截取任意区间（最多 60 秒），不再固定从 0 秒起。
- **帧率 8/10/12/15/20/24，宽度 320/480/640/720**。
- **换壳、抽帧、GIF 三处都有系统解码器 → FFmpeg 的回退**，RMVB / VOB / 老 AVI / WMV 也能处理（换壳先试 `-c copy` 无损，失败再重编码）。
- 系统解码器路径保留为回退（FFmpeg 原生库加载失败时仍可用）。

### 选文件的逻辑（v1.3 改）

v1.2 及以前，视频模块用 `setType("video/*")` 开选择器。系统只按 provider 报的 MIME 过滤，而 MIME 是各 provider 用自己的扩展名表猜的：`rmvb / m2ts / vob / f4v / rs` 这类扩展名猜不出来，一律报 `application/octet-stream`，于是被过滤掉，列表里"少了几个文件"。

v1.3 全部模块（文本 / 图片 / PDF / 文档 / 音频 / 视频 / 万能转码）统一改为 `setType("*/*")` 不做 MIME 过滤，选中后再用三种手段判断真实类型（`Picker.java`）：

1. 扩展名白名单（视频 33 种、音频 32 种、图片 16 种）；
2. 文件头魔数（`%PDF`、`FFD8`、`\x89PNG`、`RIFF…WAVE/WEBP/AVI`、`ftyp`、EBML、`FLV`、`OggS`、`fLaC`、`ID3`）；
3. `MediaExtractor` 轨道探测 + 系统 MIME 兜底。

类型对不上不再"静默消失"，而是给出明确提示（"这个文件看着是音频（扩展名 .m4a），不是视频。仍可点开始，处理失败会给出具体原因。"）。

### 万能转码的 14 种输出

| 类别 | 格式 | 编码 |
|---|---|---|
| 视频 | MP4 · H.264 | libx264（兼容性最好） |
| | MP4 · H.265 | libx265 + `hvc1` tag |
| | MKV / MOV | H.264 + AAC |
| | AVI | mpeg4 + MP3 |
| | WMV | wmv2 + wmav2 |
| | FLV | H.264 + AAC（44.1kHz 立体声） |
| | WebM | libvpx-vp9 + Opus |
| 音频 | MP3 / M4A / AAC / WAV / FLAC / OGG | libmp3lame / aac / aac-adts / pcm_s16le / flac / libvorbis |

输入由 FFmpeg full 版解码器覆盖：AVI、WMV、**RMVB**、VOB、MPG、TS、FLV、MOV、MP4、3GP、WebM、WMA、APE 等。

## 下载与安装

安装包在 **Releases**，两个版本任选其一（功能完全相同，只是内置原生库的 CPU 覆盖不同）：

| 附件 | 体积 | 适用 |
|---|---|---|
| `yuan-format-forge_1.3.2_arm64.apk` | 26.8 MB（28,078,679 字节） | 近几年的手机基本都是 arm64，装这个更省空间 |
| `yuan-format-forge_1.3.2_full.apk` | 56.8 MB（56,845,706 字节） | 两种 CPU 都带，老机型 / 不确定时用这个 |

判断方法：设置里「关于手机」若写着 `arm64-v8a` 或有「64 位」字样，就用精简版；不确定就用完整版。精简版装到 32 位机型会提示"应用未安装"。

仓库只放源码与文档，二进制走 Release 附件。

1. 打开 [Releases](../../releases) 页面，下载对应版本的 APK。
2. 传到手机后点击安装；系统提示"未知来源"时允许该来源（自签名 APK 的正常提示）。
3. 打开即用，无需注册。

自签名证书 SHA-256：

```
646ee67e50f20131e315efb5d4654733da4f608173c103ae65f694597238f8b4
```

签名不变，v1.0 / v1.1 / v1.2 可直接覆盖升级到 v1.3。

## 目录结构

```
app/                  安卓工程（AndroidManifest.xml + res/ + src/）
├── src/com/fmtlab/toolbox/
│   ├── HomeActivity.java      首页 9 张卡片
│   ├── TextActivity.java      乱码复原
│   ├── ImageActivity.java
│   ├── PdfActivity.java       PDF ⇄ 图片 / PDF ⇄ PPTX
│   ├── DocActivity.java
│   ├── AudioActivity.java
│   ├── VideoActivity.java     换壳 / GIF / 抽帧（含 FFmpeg 回退）
│   ├── ConvertActivity.java   万能转码（FFmpeg）
│   ├── CodecActivity.java
│   ├── AboutActivity.java     关于 / GPL 许可 / 第三方组件
│   ├── Picker.java           统一文件选择器 + 类型探测
│   ├── LicenseText.java      法律声明文本
│   ├── Ui.java  ExportProvider.java
│   └── core/                  纯 Java：编码检测、乱码修复、文档转换、BMP、GIF 编码、
│                              OOXML 打包（Ooxml）、PPTX 读写（PptxWriter/PptxReader）、PDF 排版（PdfExport）
tools/                构建脚本（build.ps1 / pkg.py / apkcheck.py）
docs/                 使用说明与构建说明
THIRD_PARTY_NOTICES.md  第三方组件与许可声明
```

## 构建

不需要 Gradle：

```
aapt2 compile → aapt2 link → javac → jar → d8
→ 追加 classes*.dex 与 lib/<abi>/*.so（so 用 STORED）
→ zipalign -p -f 4 → apksigner sign → apksigner verify
```

需要 JDK 17、Android SDK（`platforms;android-34` + `build-tools;34.0.0`），以及：

| 物料 | 地址 |
|---|---|
| ffmpeg-kit-full-gpl 6.0-2 | `https://mirrors.huaweicloud.com/repository/maven/com/arthenica/ffmpeg-kit-full-gpl/6.0-2/ffmpeg-kit-full-gpl-6.0-2.aar` |
| smart-exception-java 0.2.1 | `https://mirrors.huaweicloud.com/repository/maven/com/arthenica/smart-exception-java/0.2.1/smart-exception-java-0.2.1.jar` |

> Maven Central 上的 ffmpeg-kit 已全部下架（404），华为云 / 阿里云镜像仍有缓存。

细节与踩过的坑（so 必须 STORED + `extractNativeLibs=true`、`armeabi-v7a` 的库带 `_neon` 后缀、FFmpeg 回调抽象方法名是 `apply`、`Statistics.getTime()` 与 `getSpeed()` 的类型）见 [`docs/构建说明.md`](docs/构建说明.md)。

## 已知限制

- PDF 只渲染不 OCR，扫描件能出图但不识别文字。
- PDF → PPTX 是**图片版**：文字不可再编辑（要可编辑文字需要 PDF 文本层解析，本项目未做）。
- PPTX → PDF 只取每页文字与第一张图片，多图层排版、SmartArt、动画、母版装饰会简化。
- 文档转换覆盖段落、标题、表格、文本框文字；多栏、SmartArt、公式等复杂排版会丢失。
- GIF 单次最多 60 秒；`fps>20` 时浏览器对 GIF 延时精度有限，可能有轻微变速。
- 开发环境无真机/模拟器：硬件相关路径经代码审查与桌面 JVM 单测（PPTX 读写、GIF 编码、乱码 21/21），未做真机全覆盖验证。
- 视频转码是重编码：10 分钟 1080p 约 1–4 分钟，H.265 / VP9 更慢。

## 版本历史

六个版本都已正式发布（非草稿、非预发布），最新版为 **v1.3.2**：

| 版本 | versionCode | 完整包 | 精简包 | 要点 |
|---|---|---|---|---|
| [v1.3.2](../../releases/tag/v1.3.2) | 6 | 56.8 MB | 26.8 MB | 修复文本 / PDF / 视频 / 万能转码 / 文档页闪退 |
| [v1.3.1](../../releases/tag/v1.3.1) | 5 | 56.8 MB | 26.8 MB | 选项标签不再被挤成竖排细条（该版本身会闪退，仅作历史留存） |
| [v1.3](../../releases/tag/v1.3) | 4 | 56.8 MB | 26.8 MB | 统一文件选择器、GIF 走 FFmpeg、PDF ⇄ PPTX、关于与许可页 |
| [v1.2](../../releases/tag/v1.2) | 3 | 54.2 MB | 26.7 MB | 首次内置 FFmpeg 6.0，新增万能转码 14 种输出 |
| [v1.1](../../releases/tag/v1.1) | 2 | 204 KB | — | 纯系统能力迭代 |
| [v1.0](../../releases/tag/v1.0) | 1 | 175 KB | — | 首个可用版本 |

> 出问题的版本不必删，历史留在 Releases 里便于回溯；要装就用最新的 v1.3.2。1.1 / 1.0 不含原生库，所以没有 ABI 之分。

## 许可与源码获取

本项目按 **GPL-3.0-or-later** 发布（见 [LICENSE](LICENSE)），原因是内置的 `ffmpeg-kit-full-gpl` 含 GPL 组件（x264、x265）。

**GPLv3 §6 要求的对应源码（Corresponding Source）就是本仓库：**

- 应用自身的全部源码、资源、构建脚本 → `app/`、`tools/`、`docs/`
- FFmpeg / ffmpeg-kit / x264 / x265 的上游源码地址、以及重新产出同一份 `.so` 的构建方式 → [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)
- 应用内「关于与许可」页（首页第 9 张卡片）展示了同样的声明，符合 GPLv3 §5(d) 的 Appropriate Legal Notices 要求

第三方组件一览（详见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)）：

| 组件 | 版本 | 许可 |
|---|---|---|
| FFmpegKit | 6.0-2（full-gpl） | LGPL-3.0 |
| FFmpeg（libav*） | 6.0 | GPL-3.0（启用 GPL 组件） |
| x264 / x265 | — | GPL-2.0 |
| smart-exception-java | 0.2.1 | Apache-2.0 |

**专利与商标提示：** H.264 / H.265 可能涉及 MPEG-LA / Via LA 等专利池授权，本项目作者未取得相关许可，请勿用于商业分发；本应用与「格式工厂 / FormatFactory」（上海格致网络科技有限公司）无任何关联，非其官方产品、未获授权。

**免责声明：** 本软件按"现状"提供，不附带任何担保。作者不对使用本软件造成的数据丢失、转换失败或间接损失负责，转换重要文件前请自行备份。
