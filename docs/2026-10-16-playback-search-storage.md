# 2026-10-16 播放、搜索与预留空间改进

- 自动播放：MP3音轨优先使用已有 FFmpeg 解码器，其他音视频维持系统优先；仍进入同一 PCM 声道、音量、升降调处理链。全音频优先 FFmpeg 的中间方案在 Vorbis 样本出现零值输出，最终已收窄为仅MP3优先；该调整用于绕开部分系统MP3解码兼容问题，尚无用户原始故障文件及目标电视日志，不能认定为已复现并确认其具体根因。
- 本地歌曲搜索：复用 SongSearch，补齐 singerNames 歌手别名与歌手拼音/首字母，目录中只含歌名拼音时也能匹配歌手。没有歌手元数据且文件名只有数字的歌曲，仍需要目录匹配或元数据才能识别歌手。
- 手机页面：歌曲行新增本地/在线标记；根据本地文件及已完成下载缓存判断，不能只依赖 remote 字段。网盘未缓存的歌曲显示在线。
- 预留空间：以实际保存位置的可用空间约束选项、恢复的配置和保存值；不足 0.5 GB 时不再强制 0.5 GB；保存前再次读取容量。打开设置修正历史值时不触发立即删歌。
- Android 4.4（API 19）：当前不支持。app minSdk=21，固定依赖 Media3 1.7.1 的 minSdk 同为21；SongStorage 还直接使用 API 21 的 android.system.Os、SAF tree 接口。需要独立旧系统兼容方案（可支持 API19 的播放器/本地库和存储实现），本轮没有降低 minSdk 或声称支持4.4。

依据：
- https://github.com/androidx/media/blob/1.7.1/constants.gradle
- https://developer.android.com/reference/android/system/Os

测试样本：
- 下载 https://storage.googleapis.com/exoplayer-test-media-0/play.mp3 与 https://samples.ffmpeg.org/Matroska/x264_no-b-frames.mkv ，不重新编码，仅封装成12秒 H.264 + MP3 MKV。
- 下载 https://samples.ffmpeg.org/Matroska/h264-TTA.mkv ，将其音轨转为 AC3 作为附加组合测试，保留 H.264 视频；源文件本身是截取样本，FFmpeg 会提示文件提前结束。没有将 TTA 作为本轮支持承诺。
- 另外回归仓库现有 AAC、EAC3、Vorbis 样本。
- 非零PCM样本计数仅在 Debug 启用，用于证明音频已解码并进入处理链，不能替代电视、HDMI/功放真实听感、原伴唱语义及音画同步验收。

测试产物：app/build/outputs/apk/debug/app-debug.apk（com.local.ktv.qa，可与正式版共存）。未提交或推送 Git。


最终验证（Android 11/API30 x86_64 官方模拟器）：
- 75项单元测试通过，0失败；手机歌曲行本地/在线标记和文本转义检查通过。
- 歌手中文、zjl、zhoujielun 搜索通过；64GB预留请求自动校正为5274140672字节（本次实际可用空间）。
- 五种样本全部有首帧、进度及非零PCM：AAC 1369179、AC3 802705、EAC3 3429971、MP3 1048895、Vorbis 1423038 个非零样本。
- AAC、Vorbis 使用系统解码；MP3、AC3、EAC3 使用 FFmpeg。详细记录见 artifacts/audio-compat/final-matrix.log。
- APK SHA-256：6c16c11cd4642fa30d5215522b24116bb5a38e8f92efd560e8a2ea747f62b7ba。
- 未取得用户原始故障文件；本次不能证明所有电视机型的MP3无声问题均已消除。实体电视/HDMI输出、原伴唱语义、升降调与音画同步仍需人工验收。
