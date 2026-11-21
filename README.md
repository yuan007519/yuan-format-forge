# 袁神的格式工坊 · Yuan Format Forge

一个纯本机、离线运行的安卓文件格式转换工具箱。**内置 FFmpeg 6.0**，图片 / PDF / PPTX / 文档 / 字幕 / 音频 / 视频 / 文本乱码复原全部在手机上完成——不联网、不上传文件、无广告、无账号。

- 包名 `com.fmtlab.toolbox`，版本 **1.4**（versionCode 7）
- minSdk 24（Android 7.0），targetSdk 34
- 完整包 56,903,197 字节（54.3 MB），原生库覆盖 `arm64-v8a` + `armeabi-v7a`；精简包 28,136,170 字节（26.8 MB）
- 不用 Gradle、不引 AndroidX，纯 framework + 命令行工具链出包
- **APK 未声明任何权限**（连 `INTERNET` 都没有）

## 功能（十张卡片）

| # | 模块 | 能力 |
|---|---|---|
| 1 | 文本 · 乱码复原 | 自动识别 UTF-8 / GBK / Big5 / Shift_JIS / EUC-KR / UTF-16(BOM) / Windows-1252；还原 `锟斤拷`、`å¤`、`Ã©`、`浣犲ソ` 类乱码；按 6 种编码另存 |
| 2 | 图片 | png / jpg / jpeg / jpe / jfif / webp / bmp / gif / ico / tif / tiff / tga / qoi / ppm / psd 输入 → **PNG / JPG / WEBP / BMP / TIFF / TGA / QOI / ICO / PPM** 输出，质量滑杆、缩放档位、EXIF 纠正、批量 |
| 3 | PDF | PDF ⇄ 图片（2×/3×/4×，可指定页码）、图片合成 PDF、**PDF ⇄ PPTX**、**PDF 抽文字 → TXT / MD / HTML / DOCX** |
| 4 | 文档矩阵 | docx / xlsx / pptx / odt / ods / odp / epub / html / md / csv / tsv / json / xml / yaml / txt → **TXT / MD / HTML / DOCX / ODT / RTF / PDF / EPUB / CSV / TSV / JSON / XLSX** |
| 5 | 字幕（1.4 新增） | **SRT / VTT / ASS / SSA 互转**，可导出纯文本；条数 + 总时长统计、前 12 条预览 |
| 6 | 音频提取 | 视频抽音轨 → **WAV / M4A / AAC 裸流 / MP3 / FLAC / OGG / OPUS / WMA**（前三种硬解，其余走 FFmpeg） |
| 7 | 视频工具 | **MP4 / WebM / MKV / MOV 换壳、GIF 动图、抽帧 JPG、8 种音轨导出**（MP3 / M4A / WAV / FLAC / OGG / AAC / OPUS / WMA） |
| 8 | 万能转码 | 内置 FFmpeg：14 种输出格式，三档画质，实时进度与倍速，可取消 |
| 9 | 编码工具箱 | Base64 / URL / HEX / Unicode 双向编解码 |
| 10 | 关于与许可 | 版本信息、GPLv3 声明、第三方组件清单、源码获取方式、隐私说明 |

### 1.4 的「格式矩阵」做了什么

按用户给的「当前支持」清单，把各模块的输入 / 输出尽量铺开：

- **图片**：输出从 4 种扩到 9 种（新增 TIFF / TGA / QOI / ICO / PPM）。系统解码器解不开的输入（tiff / tga / qoi / psd / jp2…）自动回退到 FFmpeg 直转；新格式输出统一走「系统解码 → 无损 PNG → FFmpeg 编码」，避免二次压缩。
- **字幕**：新模块 `core/Subtitle.java` + `SubtitleActivity`，手写 SRT / VTT / ASS / SSA 解析与生成（ASS 剥 `{...}` 样式标签、`\N` 转换行，读 `Format:` 字段行而不是硬编码列序）。
- **文档**：`core/DocMatrix.java` 统一入口，`core/Epub.java` 读写 EPUB、`core/Odf.java` 读写 ODT、`core/DocxWriter.java` 写 DOCX、`core/XlsxWriter.java` 写 XLSX、RTF 用 `\uN?` 转义。全部纯手写 OOXML / ODF / EPUB 包，不引第三方 office 库。
- **PDF**：`core/PdfText.java` 解析内容流里的 `TJ` / `Tj`，处理 PDF 转义（`\ddd` 八进制、`\(` `\)`）、Flate 解压、UTF-8 智能还原中文，输出交给 DocMatrix 生成 TXT / MD / HTML / DOCX。
- **音频 / 视频**：音频 8 种输出、视频 4 种容器 + GIF + 抽帧 + 8 种音轨，全部由 `core/Ffmts.java` 统一编排 FFmpeg 参数（`libmp3lame` / `flac` / `libvorbis` / `libopus` / `wmav2` / `aac` / `pcm_s16le`；视频 `libx264 veryfast`、`libvpx-vp9`、GIF 全局调色板）。

### PDF ⇄ PPTX（v1.3 起）

- **PDF → PPTX**：每页用 `PdfRenderer` 渲染成高清 JPEG，再按 ECMA-376 写成「满版图片」幻灯片。
- **PPTX → PDF**：`core/PptxReader.java` 解析 `presentation.xml` 的 `sldIdLst` 与关系表，按真实页序取文字与图片；有整页图片的按原图铺满，纯文字按段落重排。
- 已用 python-pptx 验证：生成 3 页 595×842 pt 的图片版 PPTX 可正常打开；真实 PPTX 的页序、页尺寸、中英混排、内嵌图片均正确读取。

### 选文件的逻辑（v1.3 起）

所有模块统一 `setType("*/*")` 不做 MIME 过滤，选中后用三种手段判断真实类型（`Picker.java`）：扩展名白名单、文件头魔数（`%PDF` / `FFD8` / `\x89PNG` / `RIFF…WAVE` / `ftyp` / EBML / `FLV` / `OggS` / `fLaC` / `ID3`）、`MediaExtractor` 轨道探测。类型对不上会给明确提示，不会"静默消失"。

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

## 下载与安装

安装包在 **Releases**，两个版本任选其一（功能完全相同，只是内置原生库的 CPU 覆盖不同）：

| 附件 | 体积 | 适用 |
|---|---|---|
| `yuan-format-forge_1.4.0_arm64.apk` | 26.8 MB（28,136,170 字节） | 近几年的手机基本都是 arm64，装这个更省空间 |
| `yuan-format-forge_1.4.0_full.apk` | 54.3 MB（56,903,197 字节） | 两种 CPU 都带，老机型 / 不确定时用这个 |

判断方法：设置里「关于手机」若写着 `arm64-v8a` 或有「64 位」字样，就用精简版；不确定就用完整版。精简版装到 32 位机型会提示"应用未安装"。

仓库只放源码与文档，二进制走 Release 附件。

1. 打开 [Releases](../../releases) 页面，下载对应版本的 APK。
2. 传到手机后点击安装；系统提示"未知来源"时允许该来源（自签名 APK 的正常提示）。
3. 打开即用，无需注册。

自签名证书 SHA-256：

```
646ee67e50f20131e315efb5d4654733da4f608173c103ae65f694597238f8b4
```

签名自 v1.0 起未变，可直接覆盖升级。

## 目录结构

```
app/                  安卓工程（AndroidManifest.xml + res/ + src/）
├── src/com/fmtlab/toolbox/
│   ├── HomeActivity.java      首页 10 张卡片
│   ├── TextActivity.java      乱码复原
│   ├── ImageActivity.java     9 种输出 + FFmpeg 回退
│   ├── PdfActivity.java       5 种模式（图片 / 合 PDF / ⇄ PPTX / 抽文本）
│   ├── DocActivity.java       文档矩阵
│   ├── SubtitleActivity.java  字幕互转
│   ├── AudioActivity.java     8 种音频输出
│   ├── VideoActivity.java     4 容器 + GIF + 抽帧 + 8 音轨
│   ├── ConvertActivity.java   万能转码（FFmpeg）
│   ├── CodecActivity.java
│   ├── AboutActivity.java     关于 / GPL 许可 / 第三方组件
│   ├── Picker.java           统一文件选择器 + 类型探测
│   ├── LicenseText.java      法律声明文本
│   ├── Ui.java  ExportProvider.java
│   └── core/                  纯 Java：编码检测与乱码修复、DocMatrix / DocxWriter / XlsxWriter /
│                              Odf / Epub / PdfText / PdfExport、PptxWriter / PptxReader、Subtitle、
│                              Ffmts（FFmpeg 参数编排）、Bmp、GifWriter、Ooxml（Zip 打包）
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

细节与踩过的坑（so 必须 STORED + `extractNativeLibs=true`、`armeabi-v7a` 的库带 `_neon` 后缀、FFmpeg 回调抽象方法名是 `apply`）见 [`docs/构建说明.md`](docs/构建说明.md)。

## 已知限制

- **不做 OCR**：离线没有 Tesseract / ML Kit 模型，图片与扫描版 PDF 转 TXT 只在有文字层时有效。
- **不做相机 RAW**：3fr / arw / cr2 / cr3 / crw / dng / nef / orf / raf / rw2 / srw / x3f / heic / heif 等依赖厂商解码库，APK 内没有。
- **不做 SVG 渲染、JXL 编解码、AVIF 编码**。
- **不做老二进制 .doc / .wps / .wpt / .et / .ett / .dps / .dpt / .xls / .ofd / .mobi**：私有或复合二进制格式，请先另存为 docx / xlsx / pptx / epub。
- **PDF → XLSX / PPT → ODP / PPT → 逐页图片** 未实现；PDF 表格没有结构信息，硬生成 xlsx 等于假数据。
- PDF → PPTX 是**图片版**，文字不可编辑；PPTX → PDF 多图层、SmartArt、动画、母版装饰会简化。
- 文档转换覆盖段落、标题、表格、文本框文字；多栏、SmartArt、公式等复杂排版会丢失。
- GIF 单次最多 60 秒；`fps>20` 时浏览器对 GIF 延时精度有限，可能有轻微变速。
- 开发环境无真机/模拟器：硬件路径经代码审查与桌面 JVM 单测（乱码 21/21、GIF 3/3、PPTX 往返、字幕 6/6、PDF 抽文本、XLSX / ODT / DOCX / EPUB 生成回读），未做真机全覆盖验证。
- 视频转码是重编码：10 分钟 1080p 约 1–4 分钟，H.265 / VP9 更慢。

## 版本历史

七个版本都已正式发布（非草稿、非预发布），最新版为 **v1.4**：

| 版本 | versionCode | 完整包 | 精简包 | 要点 |
|---|---|---|---|---|
| [v1.4](../../releases/tag/v1.4) | 7 | 54.3 MB | 26.8 MB | 格式矩阵：字幕模块、图片 9 种输出、文档矩阵（ODT/RTF/XLSX/EPUB）、PDF 抽文本、视频 4 容器 + 8 音轨 |
| [v1.3.2](../../releases/tag/v1.3.2) | 6 | 56.8 MB | 26.8 MB | 修复文本 / PDF / 视频 / 万能转码 / 文档页闪退 |
| [v1.3.1](../../releases/tag/v1.3.1) | 5 | 56.8 MB | 26.8 MB | 选项标签不再被挤成竖排细条（该版本身会闪退，仅作历史留存） |
| [v1.3](../../releases/tag/v1.3) | 4 | 56.8 MB | 26.8 MB | 统一文件选择器、GIF 走 FFmpeg、PDF ⇄ PPTX、关于与许可页 |
| [v1.2](../../releases/tag/v1.2) | 3 | 54.2 MB | 26.7 MB | 首次内置 FFmpeg 6.0，新增万能转码 14 种输出 |
| [v1.1](../../releases/tag/v1.1) | 2 | 204 KB | — | 纯系统能力迭代 |
| [v1.0](../../releases/tag/v1.0) | 1 | 175 KB | — | 首个可用版本 |

> 出问题的版本不必删，历史留在 Releases 里便于回溯；要装就用最新的 v1.4。

## 许可与源码获取

本项目按 **GPL-3.0-or-later** 发布（见 [LICENSE](LICENSE)），原因是内置的 `ffmpeg-kit-full-gpl` 含 GPL 组件（x264、x265）。

**GPLv3 §6 要求的对应源码（Corresponding Source）就是本仓库：**

- 应用自身的全部源码、资源、构建脚本 → `app/`、`tools/`、`docs/`
- FFmpeg / ffmpeg-kit / x264 / x265 的上游源码地址、以及重新产出同一份 `.so` 的构建方式 → [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)
- 应用内「关于与许可」页展示了同样的声明，符合 GPLv3 §5(d) 的 Appropriate Legal Notices 要求

第三方组件一览（详见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)）：

| 组件 | 版本 | 许可 |
|---|---|---|
| FFmpegKit | 6.0-2（full-gpl） | LGPL-3.0 |
| FFmpeg（libav*） | 6.0 | GPL-3.0（启用 GPL 组件） |
| x264 / x265 | — | GPL-2.0 |
| smart-exception-java | 0.2.1 | Apache-2.0 |

**专利与商标提示：** H.264 / H.265 可能涉及 MPEG-LA / Via LA 等专利池授权，本项目作者未取得相关许可，请勿用于商业分发；本应用与「格式工厂 / FormatFactory」（上海格致网络科技有限公司）无任何关联，非其官方产品、未获授权。

**免责声明：** 本软件按"现状"提供，不附带任何担保。作者不对使用本软件造成的数据丢失、转换失败或间接损失负责，转换重要文件前请自行备份。
