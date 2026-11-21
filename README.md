# 袁神的格式工坊 · Yuan Format Forge

一个纯本机、离线运行的安卓文件格式转换工具箱。**内置 FFmpeg 6.0**，图片 / PDF / 文档 / 音频 / 视频 / 文本乱码复原全部在手机上完成——不联网、不上传文件、无广告、无账号。

- 包名 `com.fmtlab.toolbox`，版本 1.2（versionCode 3）
- minSdk 24（Android 7.0），targetSdk 34
- 体积 54.2 MB，原生库覆盖 `arm64-v8a` 与 `armeabi-v7a`
- 不用 Gradle、不引 AndroidX，纯 framework + 命令行工具链出包

## 功能

首页八张卡片：

| # | 模块 | 能力 |
|---|---|---|
| 1 | 文本 · 乱码复原 | 自动识别 UTF-8 / GBK / Big5 / Shift_JIS / EUC-KR / UTF-16(BOM) / Windows-1252；还原 `锟斤拷`、`å¤`、`Ã©`、`浣犲ソ` 类乱码；按 6 种编码另存 |
| 2 | 图片 | PNG / JPG / WEBP / BMP 批量互转、质量滑杆、缩放档位、EXIF 横竖纠正 |
| 3 | PDF | PDF ⇄ 图片（2×/3×/4×，可指定页码）；图片合成 PDF（跟随尺寸 / A4 竖 / A4 横） |
| 4 | 文档 | docx / xlsx / pptx / html / md / txt / csv → Markdown / HTML / TXT / CSV / HTML 表格，带预览 |
| 5 | 音频提取 | 视频 → WAV / M4A（AAC 直拷）/ AAC 裸流（手写 ADTS 头） |
| 6 | 视频工具 | MP4 换壳、WebM 换壳、GIF 动图（精确调色板 + Floyd–Steinberg 抖动）、抽帧 JPG |
| 7 | 万能转码 | 内置 FFmpeg：14 种输出格式，三档画质，实时进度与倍速，可取消 |
| 8 | 编码工具箱 | Base64 / URL / HEX / Unicode 双向编解码 |

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

画质档位：高清＝原分辨率；标准＝超 1080 缩到 1080；小体积＝超 720 缩到 720。

## 下载与安装

安装包在 **Releases**：`袁神的格式工坊-1.2-完整版.apk`（54.2 MB）。
仓库只放源码与文档，二进制走 Release 附件。

1. 打开 [Releases](../../releases) 页面，下载 `袁神的格式工坊-1.2-完整版.apk`。
2. 传到手机后点击安装；系统提示"未知来源"时允许该来源（自签名 APK 的正常提示）。
3. 打开即用，无需注册。

自签名证书 SHA-256：

```
646ee67e50f20131e315efb5d4654733da4f608173c103ae65f694597238f8b4
```

同签名才能覆盖安装升级。

## 目录结构

```
app/                  安卓工程（AndroidManifest.xml + res/ + src/）
├── src/com/fmtlab/toolbox/
│   ├── HomeActivity.java      首页 8 张卡片
│   ├── TextActivity.java      乱码复原
│   ├── ImageActivity.java
│   ├── PdfActivity.java
│   ├── DocActivity.java
│   ├── AudioActivity.java
│   ├── VideoActivity.java     换壳 / GIF / 抽帧
│   ├── ConvertActivity.java   万能转码（FFmpeg）
│   ├── CodecActivity.java
│   ├── Ui.java  ExportProvider.java
│   └── core/                  纯 Java：编码检测、乱码修复、文档转换、BMP、GIF 编码
tools/                构建脚本（build.ps1 / pkg.py / apkcheck.py）
docs/                 使用说明与构建说明
```

安装包不落在仓库里，作为 Release 附件分发（单文件 54 MB，走 Git 历史会让仓库膨胀）。

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

细节与踩过的坑（so 必须 STORED + `extractNativeLibs=true`、`armeabi-v7a` 的库带 `_neon` 后缀、FFmpeg 回调抽象方法名是 `apply`、`Statistics.getTime()` 单位是毫秒）见 [`docs/构建说明.md`](docs/构建说明.md)。

## 已知限制

- PDF 只渲染不 OCR，扫描件能出图但不识别文字。
- 文档转换覆盖段落、标题、表格、文本框文字；多栏、SmartArt、公式等复杂排版会丢失。
- 字幕烧录未开放到界面（libass 已编入库内）。
- 开发环境无真机/模拟器，硬件相关路径经代码审查与桌面 JVM 单测，未做真机全覆盖验证。
- 视频转码是重编码：10 分钟 1080p 约 1–4 分钟，H.265 / VP9 更慢。

## 许可

本项目按 **GPL-3.0-or-later** 发布（见 [LICENSE](LICENSE)），原因是内置的 `ffmpeg-kit-full-gpl` 含 GPL 组件（x264、x265）。

- 自己用、分享给朋友：没问题。
- 上架应用商店或商用分发：需遵守 GPL，随包提供对应源码（本仓库即源码）。

FFmpeg 本体为 LGPL/GPL 双轨，ffmpeg-kit 为 LGPL-3.0（full-gpl 变体因打包 GPL 库而整体受 GPL 约束）。
