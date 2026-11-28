package com.fmtlab.toolbox;

/** App 内的法律声明与第三方许可文本（GPLv3 §5(d) 要求的 Appropriate Legal Notices） */
public final class LicenseText {

    public static final String VERSION_NAME = "1.6.9";
    public static final String REPO_URL = "https://github.com/yuan007519/yuan-format-forge";

    private LicenseText() {}

    public static String about() {
        return "袁神的格式工坊 " + VERSION_NAME + "\n"
                + "com.fmtlab.toolbox\n"
                + "\n"
                + "十二个工具：文本、图片、PDF、文档、字幕、音频、视频、万能转码、编码、压缩、历史，加这一页。\n"
                + "\n"
                + "说明文字重写了一遍。页面只留一句，细则在「使用说明」。\n"
                + "\n"
                + "不上网、不要权限，文件不出手机。\n"
                + "\n"
                + "一个人写的，有坑。重要文件先备份。";
    }

    /** 关于作者：一篇不带任何真实身份信息的自述 */
    public static String author() {
        return "余自幼学诗书，至今十有余岁，四书五经尚不能熟诵。诸父皆谓余愚，余未尝莽驳。\n"
                + "\n"
                + "然众人莫知，余乃承四方之化、应天地之气运而生，顺天道之命，化为人形，降于此间，生于此家。"
                + "余自降生，数月便行，又数月能言，不足一岁能识文认字。且藏且拙，未露马脚于人前。\n"
                + "\n"
                + "稍长，观诸多文档，至陋至鄙，不堪入目：如狐之腋下，如田之足底，腥且臊，膻且臭，"
                + "每观之，几反胃于昨日饭食。年复一年，读遍万千文档，未有一版能入我目者。悲夫！世上无善文者！\n"
                + "\n"
                + "作文岂是如此不便之物！余甚哀之。文道不昌，非人之过也。无神兵利器何以为文？"
                + "某愿以身合道，化为神器辅佐天下为文者，重振文道风骨。遂有此项目，永久开源免费，望后人莫要辜负某之遗愿，此身安矣。\n"
                + "\n"
                + "——袁神";
    }

    public static String license() {
        return "一、本应用的许可\n"
                + "\n"
                + "本应用（袁神的格式工坊）以 GNU General Public License v3.0 or later（GPL-3.0-or-later）发布。\n"
                + "\n"
                + "Copyright (C) 2026 袁神\n"
                + "\n"
                + "This program is free software: you can redistribute it and/or modify it under the terms of "
                + "the GNU General Public License as published by the Free Software Foundation, either version 3 "
                + "of the License, or (at your option) any later version.\n"
                + "\n"
                + "This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; "
                + "without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. "
                + "See the GNU General Public License for more details.\n"
                + "\n"
                + "You should have received a copy of the GNU General Public License along with this program. "
                + "If not, see <https://www.gnu.org/licenses/>.\n"
                + "\n"
                + "二、如何获取对应源码（Corresponding Source）\n"
                + "\n"
                + "本应用的完整源码、构建脚本（aapt2 / javac / d8 / apksigner 流水线）、资源文件均在此仓库公开：\n"
                + REPO_URL + "\n"
                + "\n"
                + "仓库内 LICENSE 为 GPLv3 全文，THIRD_PARTY_NOTICES.md 说明第三方组件与其源码获取方式，"
                + "docs/构建说明.md 说明如何从源码重新构建出与本 APK 一致的安装包。\n"
                + "\n"
                + "三、第三方组件\n"
                + "\n"
                + "1. FFmpegKit（com.arthenica:ffmpeg-kit-full-gpl:6.0-2）\n"
                + "   许可：LGPL-3.0（ffmpeg-kit 自身代码）\n"
                + "   源码：https://github.com/arthenica/ffmpeg-kit\n"
                + "\n"
                + "2. FFmpeg（libavcodec / libavformat / libavfilter / libavutil / libswscale / libswresample / libavdevice）\n"
                + "   许可：GPL-3.0（本包启用了 GPL 组件，因此整体按 GPLv3 分发）\n"
                + "   源码：https://ffmpeg.org/download.html\n"
                + "\n"
                + "3. x264 / x265\n"
                + "   许可：GPL-2.0（x264）/ GPL-2.0（x265，含商业许可双授权）\n"
                + "   源码：https://code.videolan.org/videolan/x264 ， https://bitbucket.org/multicoreware/x265_git\n"
                + "\n"
                + "说明：由于内置了 x264 / x265，整个 APK 必须按 GPL 系列许可分发，不能闭源再发布。\n"
                + "\n"
                + "四、字体与图标\n"
                + "\n"
                + "应用内 31 个图标全部为本项目自绘的 vector drawable，未使用任何第三方图标库；"
                + "界面字体使用系统自带字体，未打包第三方字体文件。\n"
                + "\n"
                + "五、商标与免责\n"
                + "\n"
                + "本应用与「格式工厂 / FormatFactory」（上海格致网络科技有限公司）无任何关联，"
                + "不是其官方产品、未获其授权，也不代表其立场。名称中的“格式工坊”仅为本项目自称。\n"
                + "\n"
                + "H.264 / AVC、H.265 / HEVC 相关编解码技术可能涉及第三方专利池（MPEG-LA / Via LA 等）授权，"
                + "本项目的作者未取得相关专利许可。请勿将本应用用于商业分发或商业服务；"
                + "因使用本应用产生的任何直接或间接损失，作者不承担责任。";
    }

    public static String privacy() {
        return "本应用不申请任何系统权限（AndroidManifest 中没有一条 uses-permission，包括 INTERNET）。\n"
                + "\n"
                + "· 不联网：没有网络权限，物理上无法上传任何文件。\n"
                + "· 不收集：没有统计、埋点、广告、账号体系。\n"
                + "· 只读你选的文件：通过系统的文件选择器（SAF）读取你明确点选的那一个文件，"
                + "转换结果写入应用专属目录或你指定的文件夹。\n"
                + "· 缓存文件：为调用 FFmpeg 内核，会把选中的文件复制一份到应用缓存目录，"
                + "下次选择新文件时自动清空，卸载应用即全部删除。";
    }
}

