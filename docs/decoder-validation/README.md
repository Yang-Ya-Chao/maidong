# 模拟器 MKV 解码切换回归（2026-10-15）

修复：旧播放器停止输出并释放后，更换 KtvSurfaceView，再让新解码器绑定新 Surface。保留媒体、进度、播放意图、音调、声道与音轨选择的既有重建流程。

上游问题：https://github.com/anilbeesetti/nextlib/pull/192

测试设备：夜神模拟器 `127.0.0.1:62001`，Android 7.1 / API 25；Debug 应用 `com.local.ktv.qa`。本次测试不是 Android TV 真机验收。

下载样本保存在 `app/src/debug/assets/decoder-samples`，已通过 ffprobe 提取元数据和 SHA-256（sample-metadata.json）。样本仅用于本地 Debug 回归，已加入 .gitignore，不进入 Release。

| 文件 | 下载来源 | 内容 |
|---|---|---|
| h264-eac3.mkv | https://samples.ffmpeg.org/Matroska/H264+EAC3.mkv | H.264 / E-AC-3 |
| vorbis-multiaudio.mkv | https://samples.ffmpeg.org/Matroska/vorbis-audio-switch.mkv | H.264 / 4 路 Vorbis / ASS、SRT、字体附件 |
| multitrack.mkv | https://samples.ffmpeg.org/Matroska/multiple_tracks.mkv | 两路 MPEG-4 视频、MP3/AAC 音频、SRT |
| msmpeg4v3-subrip.mkv | https://samples.ffmpeg.org/Matroska/bug669-simpleblocks.mkv | MSMPEG4v3 / SRT / 无音频 |
| hdr10tags-both.mkv | https://fate-suite.ffmpeg.org/mkv/hdr10tags-both.mkv | HEVC / HDR 元数据 / 无音频 / 约 0.4 秒 |
| spherical.mkv | https://fate-suite.ffmpeg.org/mkv/spherical.mkv | H.264 / 1080p / 无音频 / 球面元数据 |
| test7_cut.mkv | https://fate-suite.ffmpeg.org/mkv/test7_cut.mkv | H.264 / AAC，截断边界样本 |
| aac-sbr.mkv | https://samples.ffmpeg.org/Matroska/aac-sbr-ffaac-slow.mkv | H.264 / HE-AAC / 字幕、字体附件；测试开头约 22 秒 |

Debug 专用 DecoderTestActivity 将以上文件复制到模拟器 `/storage/emulated/0/Android/data/com.local.ktv.qa/files/decoder-samples/`，通过项目实际 KtvPlaybackEngine 播放。每个样本依次检查：SYSTEM → SOFTWARE → SYSTEM → AUTO → SOFTWARE → SYSTEM。

通过条件：无播放错误、当前播放器已输出首帧、进度持续增加（短样本允许正常播完）、有音频的样本必须初始化音频解码器。记录实际解码器名称、切换前后进度、首帧、音视频分别通过状态。短样本或临近片尾会回到起点，其他切换保留进度。

这验证默认选轨的解封装与播放、解码切换和首帧恢复；不等同于人工听音、逐帧流畅度、HDR 显示正确性、字幕渲染、所有音轨逐一切换、原伴唱、音调或长时间音画同步验收。

运行：安装 Debug APK 后，通过 AndroidMCP 启动 `com.local.ktv.qa/com.local.ktv.DecoderTestActivity`。结果写入模拟器同目录 results.txt，并以 DecoderMatrix 标签输出日志。

## 第一轮结果（兼容性完善前）

最终运行：2026-10-15 07:43:51–07:46:53（模拟器日志时间）；8 个下载原样本，48 项播放检查，22 项通过、26 项未通过。完整逐项记录见 matrix-results.log 和 matrix-results.json。未通过项不代表切换卡死：同时检查了不受支持的编码与文件边界样本。

| 样本 | 系统 | FFmpeg | 自动 | 通过数 |
|---|---|---|---|---|
| H.264 + Vorbis 多音轨 | 通过 | 通过 | 通过 | 6/6 |
| H.264 + E-AC-3 | 画面通过，音频未初始化 | 通过 | 通过 | 3/6 |
| HEVC HDR 短片段 | 通过 | 通过 | 通过 | 6/6 |
| MPEG-4 多视频/多音轨 | 默认选轨通过 | 视频未初始化，AAC 已初始化 | 默认选轨通过 | 4/6 |
| H.264 1080p 球面样本 | 解码器初始化，但未输出首帧 | 通过 | 通过 | 3/6 |
| MSMPEG4v3 + SRT | 未输出视频 | 未输出视频 | 未输出视频 | 0/6 |
| test7_cut（H.264 + AAC） | EOFException / 2000 | EOFException / 2000 | EOFException / 2000 | 0/6 |
| aac-sbr（H.264 + HE-AAC） | 读取位置越界 / 2008 | 读取位置越界 / 2008 | 读取位置越界 / 2008 | 0/6 |

结论：本次实际切换未再出现 `BufferQueueProducer: already connected` 或原先 Surface 占用引起的 4001 解码器初始化错误。支持的样本切回系统解码后能够重新输出首帧并继续推进进度，例如 Vorbis 样本的 FFmpeg → 系统为 6914 → 10258 ms，再次切回系统为 16381 → 19507 ms。

HEVC 样本只有约 0.4 秒，本次仅证明解析、首帧和正常结束，不证明持续 HEVC 播放性能或 HDR 色彩正确性。系统 H.264 1080p 首帧失败、MSMPEG4v3 不支持、显式 FFmpeg 模式缺少 MPEG-4 视频，以及两个文件读取失败仍存在，未将其宣称为正常。AAC 样本在主机 FFmpeg 的开头 25 秒读取可成功，Media3 仍报 2008；该差异需另行排查，不能仅凭扩展名归因于 AAC 解码器。

错误码依据：[Media3 PlaybackException](https://developer.android.com/reference/androidx/media3/common/PlaybackException)。Matroska 官方说明 test7 包含未知、垃圾和损坏元素：[Test suite](https://www.matroska.org/downloads/test_suite.html)。

构建与交付：`:app:assembleDebug`、`:app:testDebugUnitTest` 通过。最终 Debug 测试 APK 大小 124,694,682 字节，SHA-256 `707817fdb3b121e6b95487f50ca4fa3c6736c0cc7a01bc523073a2987e426f0b`。包含约 70 MB 测试媒体，安装在夜神的 `com.local.ktv.qa`，与原正式包并存；未提交 Git。

截图：h264-after-switch.png、hevc-frame.png。截图仅证明捕获时画面，进度和切换结论以逐项日志为依据。

重新获取忽略的媒体文件可运行本目录 download-samples.ps1；脚本按 sample-metadata.json 校验 SHA-256，然后再通过 AndroidMCP 构建和安装 Debug 包。

## 最新结果：兼容性完善与防崩回归

最终运行：2026-10-15 08:25:44–08:28:46，夜神原生 x86，进程 14939。完整跑完 8 个样本、48 项检查，27 项播放通过，21 项未通过；没有进程崩溃。逐项记录为 matrix-improved-results.log / matrix-improved-results.json。这是本轮最终结果，前文保留为修改前基线。

| 样本 | 系统 | 兼容（FFmpeg 优先） | 自动 | 通过数 |
|---|---|---|---|---|
| H.264 + Vorbis 多音轨 | 通过 | 通过 | 通过 | 6/6 |
| H.264 + E-AC-3 | 有画面，音频未初始化 | 通过 | 通过 | 3/6 |
| HEVC HDR 短片段 | 通过 | 通过 | 通过 | 6/6 |
| MPEG-4 多视频/多音轨 | 默认选轨通过 | 默认选轨通过，系统视频 + FFmpeg AAC | 默认选轨通过 | 6/6 |
| H.264 High 4:4:4 1080p | 系统无首帧 | 已拦截，4005 不支持 | 已拦截，4005 不支持 | 0/6 |
| MSMPEG4v3 + SRT | 无视频 | 无视频 | 无视频 | 0/6 |
| test7_cut | EOF / 2000 | EOF / 2000 | EOF / 2000 | 0/6 |
| aac-sbr | 顺序读取通过 | 顺序读取通过 | 顺序读取通过 | 6/6 |

实际改动：

- 兼容模式保留系统解码器，FFmpeg 优先，修复 MPEG-4 在兼容模式没有画面的问题。
- MKV/WebM 仅在开播位置 0、未出首帧、错误码 2008 时，允许一次跳过 Cues 索引的恢复。当前媒体切换模式时保留恢复状态，下一首重置。该 AAC 样本已能播放，但可能无法跳转；回归会在每次切换前回到起点，不将它算作进度保持验证。
- Debug 打包 ARM、ARM64、x86、x86_64 四种原生库。最终运行日志 nativeDir 为 `/data/app/com.local.ktv.qa-2/lib/x86`；Release 构建配置仍仅保留 ARM、ARM64。
- 发现此前只被描述为“1080p”的 spherical.mkv 实际是 H.264 High 4:4:4 Predictive / yuv444p。先后在 ARM 转译与原生 x86 两轮中复现 SIGSEGV，原生堆栈指向 libswscale，故不能依据早先偶尔出帧的结果认定可靠支持。现在对实际选中的 FFmpeg High 4:4:4 轨道，在进入 native 解码前返回明确的 4005 不支持错误；普通 AVC Main/High 不拦截，禁用视频的音频副引擎不受影响。该配置目前仍不能播放，防崩处理不等于补齐解码能力。

失败证据保留为 arm-translation-crash.log、native-x86-crash.log。补上 x86 库解决了架构匹配问题，但没有修复 High 4:4:4 转换缺陷，二者不混同。

对比基线：MPEG-4 4/6 → 6/6，AAC 索引异常 0/6 → 6/6；先前偶尔通过的 High 4:4:4 3/6 改为明确不支持 0/6，以避免进程崩溃。最终总数 27/48，不把拒绝播放伪装成播放通过。

`:app:assembleDebug`、`:app:testDebugUnitTest` 均通过。新增索引恢复边界与 AVC profile 防误拦截测试。最新测试 APK 大小 130,137,406 字节，SHA-256 `b30d8fcc4f6ca0257d849ad20a14a60a8c692ff45994341b4c3ee8a07bebec63`，已安装到夜神 `com.local.ktv.qa`；未提交 Git。

进一步覆盖 MSMPEG4v3、High 4:4:4 等配置，需要扩展/替换底层解码与格式映射、修复像素格式转换，再做设备回归。实际缺失或损坏的数据不能靠更换解码模式恢复。本轮不宣称所有 MKV、逐音轨切换、人工听音、HDR 色彩或长时间音画同步通过。

实现依据：[Media3 1.7.1 MatroskaExtractor](https://github.com/androidx/media/blob/1.7.1/libraries/extractor/src/main/java/androidx/media3/extractor/mkv/MatroskaExtractor.java)、[DefaultTrackSelector](https://github.com/androidx/media/blob/1.7.1/libraries/exoplayer/src/main/java/androidx/media3/exoplayer/trackselection/DefaultTrackSelector.java)、[Android NDK ABI 文档](https://developer.android.com/ndk/guides/abis)。

## 当前版本：全自动解码（2026-10-15）

移除主应用的解码设置入口和弹窗，忽略旧 SYSTEM/SOFTWARE 偏好。主视频和两个音频副引擎默认 AUTO。音视频轨道分别恢复，失败时尝试另一解码路径，各轨道最多一次备用切换，避免往返重试。播放已就绪但 5 秒没有视频首帧时触发恢复；暂停、缓冲和纯音频副引擎不触发此检查。保留初始 MKV 索引异常恢复。

解码路径耗尽后停止当前引擎，主应用提示并交给队列播放下一首，不再从歌曲源层重复打开同一个解码失败文件。网络和文件读取错误保留原有有限重试。

最终 Debug 构建和单元测试通过，已安装到夜神。09:07:30–09:09:35、进程 19834 的引擎回归先写入旧 SYSTEM 偏好，再断言引擎启动为 AUTO；8 个样本各运行一次，无人工选择解码器，5 个通过，3 个仍失败，本轮没有进程崩溃。

| 样本 | 自动播放结果 |
| --- | --- |
| aac-sbr.mkv | 通过，索引异常后顺序播放 |
| h264-eac3.mkv | 通过 |
| hdr10tags-both.mkv | 短片段出帧并正常结束；未验收 HDR 色彩 |
| multitrack.mkv | 通过；未逐音轨验收 |
| vorbis-multiaudio.mkv | 通过；未逐音轨验收 |
| msmpeg4v3-subrip.mkv | 未输出视频首帧，仍不支持 |
| spherical.mkv | High 4:4:4 仍未输出视频首帧 |
| test7_cut.mkv | 文件读取异常 2000，未通过 |

以上为当轮观察结果；模拟器后续重启使原 logcat 不再可回取，因此没有补写原始日志文件。本轮不宣称主界面点歌队列、原伴唱、人工听音或长时间同步已经完成设备验收。

当前 APK：130,498,584 字节，SHA-256 `2def62b7393ac073957e09d72ef248fb14caa71346191e5a9639032ddc8684a1`。此节取代前述人工模式选择流程；历史测试记录保留。未提交 Git。
