# 第三方组件与许可声明（THIRD_PARTY_NOTICES）

本文件是「袁神的格式工坊」（包名 `com.fmtlab.toolbox`）对其内置第三方组件的许可声明。
应用整体以 **GPL-3.0-or-later** 分发，完整条文见仓库根目录 `LICENSE`。

---

## 1. FFmpegKit

| 项目 | 说明 |
| --- | --- |
| Maven 坐标 | `com.arthenica:ffmpeg-kit-full-gpl:6.0-2` |
| 打包文件 | `ffmpeg-kit-full-gpl-6.0-2.aar`（56,925,260 字节） |
| 许可 | LGPL-3.0（ffmpeg-kit 自身） |
| 上游源码 | <https://github.com/arthenica/ffmpeg-kit>（tag `6.0.2`，对应 6.0-2） |
| 本项目使用方式 | 以 aar 形式静态打包 `classes.jar` 与 `jni/{arm64-v8a,armeabi-v7a}/*.so`，未修改其代码 |

> 注意：ffmpeg-kit 项目已于 2025 年初从 Maven Central 与 GitHub Releases 撤下二进制，
> 本项目使用的是公共镜像（华为云 / 阿里云 Maven 镜像）中仍然缓存的 6.0-2 版本 aar。
> 重新构建时需要同样的文件，见 `docs/构建说明.md`。

## 2. FFmpeg（原生库）

| 项目 | 说明 |
| --- | --- |
| 打包文件 | aar 内 `libavcodec` / `libavformat` / `libavfilter` / `libavutil` / `libswscale` / `libswresample` / `libavdevice` 的 `arm64-v8a`、`armeabi-v7a` 版本（共 20 个 `.so`） |
| 许可 | **GPL-3.0**（本包启用了 GPL 组件，因此整包按 GPLv3 分发，而非 LGPL） |
| 上游源码 | <https://ffmpeg.org/download.html> ；ffmpeg-kit 的编译脚本见 <https://github.com/arthenica/ffmpeg-kit/tree/main/scripts> |
| 启用的 GPL 组件 | `libx264`、`libx265`、`libvpx`（VP8/VP9）、`libmp3lame`、`libvorbis`、`libopus`、`libflac` 等 |

GPLv3 §6 要求分发目标代码时提供“对应源码（Corresponding Source）”。本项目通过以下方式满足：

1. 本应用自身的完整源码、资源、构建脚本在本仓库公开（`app/`、`tools/`、`docs/`）；
2. FFmpeg 与 ffmpeg-kit 的源码为上述公开上游地址，未经修改；
3. 若需要精确复现同一份 `.so`，可按 ffmpeg-kit 官方脚本以 `--enable-gpl` + `full-gpl` 变体重编，
   或直接使用同一镜像缓存中的 aar（sha256 见下）。

`ffmpeg-kit-full-gpl-6.0-2.aar` SHA-256：

```
（构建时可自行校验：certutil -hashfile ffmpeg-kit-full-gpl-6.0-2.aar SHA256）
```

## 3. x264 / x265

| 项目 | 说明 |
| --- | --- |
| 许可 | x264：GPL-2.0；x265：GPL-2.0（上游另有商业许可可选，本项目使用 GPL 分支） |
| 源码 | <https://code.videolan.org/videolan/x264> ；<https://bitbucket.org/multicoreware/x265_git> |
| 使用方式 | 作为 FFmpeg 的编码器静态链接进 `libavcodec`，仅通过 FFmpeg 命令行调用（`libx264` / `libx265`） |

## 4. smart-exception-java

| 项目 | 说明 |
| --- | --- |
| 坐标 | `com.arthenica:smart-exception-java:0.2.1` |
| 许可 | Apache-2.0 |
| 源码 | <https://github.com/arthenica/smart-exception> |
| 作用 | ffmpeg-kit 的编译期依赖，用于 `FFmpegKit` 的异常封装 |

## 5. 未使用的第三方资源

- **图标**：应用内 31 个图标全部为本项目自绘 `vector drawable`（`app/src/main/res/drawable/ic_*.xml`），未使用 Material Icons、Font Awesome 等第三方图标集。
- **字体**：只使用 Android 系统自带字体（`sans-serif` / `sans-serif-medium`），未打包任何 `.ttf` / `.otf`。
- **图片 / 音效 / 模板**：无。
- **网络与统计 SDK**：无。APK 未声明任何 `uses-permission`，包括 `INTERNET`。

## 6. 专利与商标提示（不属于开源许可范畴）

- **H.264 / AVC、H.265 / HEVC**：相关编解码技术可能落入 MPEG-LA / Via LA 等专利池的许可范围，
  本项目作者**未取得**任何专利许可。个人自用一般不构成分发；公开分发、预装、商业服务需自行解决专利授权。
- **商标**：本应用与「格式工厂 / FormatFactory」（上海格致网络科技有限公司）无任何关联，
  非其官方产品、未获授权，名称中的「格式工坊」为本项目自称。

## 7. 免责声明

本软件按“现状”提供，不附带任何明示或暗示的担保。作者不对使用本软件造成的
数据丢失、转换失败、设备异常或任何间接损失承担责任。转换重要文件前请自行备份。

---

如果你分发本应用的 APK，请把本文件与 `LICENSE` 一起随包提供；应用内「关于与许可」页
（首页第 9 张卡片）也展示了同样的信息。
