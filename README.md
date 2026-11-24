# 袁神的格式工坊（Yuan Format Forge）

Android 端的本地文件格式转换工具。内置 FFmpeg 6.0，图片、PDF、Office 文档、字幕、音频、视频、文本编码转换都在设备上完成，不联网，也不声明任何权限。

- 包名 `com.fmtlab.toolbox`
- minSdk 24，targetSdk 34
- 构建方式：aapt2 + javac + d8 + apksigner，不使用 Gradle
- 语言：Java（framework API，无 AndroidX）

## 功能

| 模块 | 输入 | 输出 |
|---|---|---|
| 文本乱码修复 | txt / log / md / csv 等文本 | 6 种编码另存 |
| 图片 | png / jpg / jpeg / jfif / webp / bmp / gif / ico / tif / tiff / tga / qoi / ppm / psd | png / jpg / webp / bmp / tiff / tga / qoi / ico / ppm |
| PDF | pdf / 图片 | png / jpg / webp / pptx / txt / md / html / docx / pdf |
| 文档 | docx / xlsx / pptx / odt / ods / odp / epub / html / md / rtf / csv / tsv / json / xml / yaml / txt | txt / md / html / docx / odt / rtf / pdf / epub / csv / tsv / json / xlsx / ods；pptx 另可导出 png / jpg |
| 字幕 | srt / vtt / ass / ssa | srt / vtt / ass / ssa / txt |
| 音频 | aac / flac / m4a / mp3 / ogg / opus / wav / wma | wav / m4a / aac / mp3 / flac / ogg / opus / wma |
| 视频 | mp4 / m4v / m4s / mkv / mov / avi / flv / wmv / webm / ts / mts / m2ts / mst | mp4 / webm / mkv / mov / gif / jpg（抽帧）/ mp3 / m4a / wav / flac / ogg / aac / opus / wma |
| 万能转码 | FFmpeg 可解的常见音视频 | 14 种（MP4 H.264/H.265、MKV、MOV、AVI、WMV、FLV、WebM、MP3、M4A、AAC、WAV、FLAC、OGG） |
| 编码工具箱 | 文本 | Base64 / URL / HEX / Unicode 双向转换 |

文档转换只处理文字与表格内容，不保留版式。PDF 转 PPTX 是逐页图片版；PDF 转文本依赖 PDF 内的文字层（中文 CID 字体走 ToUnicode 表，简单字体走 /Encoding /Differences）。选 DOCX 时，扫描件或字体确实没有 Unicode 映射的 PDF 会自动改成按页嵌图的图片版 Word，文字不可编辑。

## 下载

Release 页面提供两个包，功能相同，区别是内置的 FFmpeg 原生库：

| 文件 | 体积 | 说明 |
|---|---|---|
| `yuan-format-forge_1.5.3_arm64.apk` | 26.9 MB | 只含 arm64-v8a |
| `yuan-format-forge_1.5.3_full.apk` | 54.3 MB | arm64-v8a + armeabi-v7a |

签名证书（自签名，与历史版本一致，可覆盖升级）：

```
SHA-256 646ee67e50f20131e315efb5d4654733da4f608173c103ae65f694597238f8b4
```

## 构建

需要 JDK 17、Android SDK（`platforms;android-34`、`build-tools;34.0.0`），以及两个 FFmpegKit 依赖：

| 依赖 | 地址 |
|---|---|
| ffmpeg-kit-full-gpl 6.0-2 | `https://mirrors.huaweicloud.com/repository/maven/com/arthenica/ffmpeg-kit-full-gpl/6.0-2/ffmpeg-kit-full-gpl-6.0-2.aar` |
| smart-exception-java 0.2.1 | `https://mirrors.huaweicloud.com/repository/maven/com/arthenica/smart-exception-java/0.2.1/smart-exception-java-0.2.1.jar` |
| smart-exception-common 0.2.1 | `https://mirrors.huaweicloud.com/repository/maven/com/arthenica/smart-exception-common/0.2.1/smart-exception-common-0.2.1.jar` |

两个 smart-exception jar 必须一起进 classpath 与 d8，只带 `smart-exception-java` 会在真机上缺 `PackageLoader`，FFmpegKit 初始化失败。

构建流程：

```
aapt2 compile -> aapt2 link -> javac -> jar -> d8
-> 合并 classes*.dex、lib/<abi>/*.so（so 用 STORED）
-> zipalign -p -f 4 -> apksigner sign -> apksigner verify
```

具体命令见 `tools/build.ps1`，踩坑记录见 `docs/构建说明.md`。

## 目录

```
app/                     Android 工程
  AndroidManifest.xml
  res/                   布局、资源
  src/com/fmtlab/toolbox/
    HomeActivity.java    模块入口
    TextActivity.java    乱码修复
    ImageActivity.java   图片转换
    PdfActivity.java     PDF 转图片 / 图片合 PDF / PDF <-> PPTX / PDF 抽文本
    DocActivity.java     文档矩阵、PPTX 导图
    SubtitleActivity.java 字幕转换
    AudioActivity.java   音频提取
    VideoActivity.java   换壳 / GIF / 抽帧 / 音轨
    ConvertActivity.java 万能转码
    CodecActivity.java   编码工具箱
    AboutActivity.java   许可与版本信息
    Picker.java          文件选择与类型判断
    Ui.java              通用界面工具
    core/                无 Android 依赖的转换实现
      DocMatrix.java     文档矩阵入口
      DocConverter.java  docx / xlsx / pptx 文本提取
      DocxWriter.java    DOCX 生成
      XlsxWriter.java    XLSX 生成
      Odf.java           ODT / ODS / ODP 读写
      Epub.java          EPUB 读写
      Rtf.java           RTF 文本提取
      PdfText.java       PDF 文字层提取
      PdfExport.java     文本排版成 PDF
      PptxReader/PptxWriter.java  PPTX 读写
      Subtitle.java      字幕格式解析与生成
      Ffmts.java         FFmpeg 参数构造与执行
      Ooxml.java         ZIP / XML 工具
tools/                   构建脚本
docs/                    使用说明、构建说明
```

## 已知限制

- 不含 OCR，图片与扫描版 PDF 无法转成文字。
- 不支持相机 RAW、SVG 渲染、JXL、AVIF 编码、heic/heif。
- 不支持旧二进制格式：doc / wps / wpt / et / ett / dps / dpt / xls / ofd / mobi。
- 不支持音乐平台加密格式。
- PDF 转 XLSX、PPTX 转 ODP 未实现。
- 文档转换不保留多栏、SmartArt、公式等版式。
- 只在桌面 JVM 上做了核心逻辑单测，没有真机自动化测试。

## 许可

GPL-3.0-or-later。内置 ffmpeg-kit-full-gpl，包含 x264、x265，因此整体按 GPL 分发。第三方组件与上游源码地址见 `THIRD_PARTY_NOTICES.md`。

H.264 / H.265 涉及专利池授权，本项目未取得许可，请勿用于商业分发。本项目与「格式工厂 / FormatFactory」无关。
