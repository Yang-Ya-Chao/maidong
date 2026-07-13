# Maidong-KTV

麦唱KTV点歌系统是一个运行在Android设备上的KTV点歌应用程序，支持触摸屏和遥控器操作。该系统提供完整的歌曲点播、播放、管理功能，适用于家庭KTV、商用KTV包厢等场景。

## 功能特性

### 歌曲管理
- 歌曲分类浏览（按语言、歌手、风格、排行榜等）
- 歌曲搜索（歌名、歌手、拼音首字母）
- 歌曲收藏与播放列表管理
- 下载管理（支持歌曲缓存到本地）
- U盘歌曲导入

### 播放功能
- 歌曲播放控制（播放、暂停、切歌、重唱）
- 原声/伴奏切换
- 音量调节（音乐音量、麦克风音量）
- 多种音效模式（流行、摇滚、抒情、民谣等）
- 气氛灯光效果
- 歌词同步显示

### 系统设置
- 播放模式设置（单曲循环、顺序播放、自动下一首）
- 录音功能（录制演唱内容）
- 评分系统
- 屏幕亮度调节
- 存储空间管理
- 自动清理下载文件

### 网络与同步
- 远程手机点歌（通过局域网）
- 歌曲目录同步
- 播放状态持久化

## 技术架构

### 核心组件
- **KtvApplication**: 应用入口，负责初始化
- **MainActivity**: 主界面，包含所有业务逻辑
- **KtvPlaybackEngine**: 播放引擎，基于IJKPlayer实现
- **KtvVideoView**: 视频播放组件
- **MuseDatabase**: 歌曲数据库，支持SQLite
- **SongLibrary**: 歌曲库管理
- **SongOkDownloadManager**: 下载管理器
- **KtvStore**: 配置存储
- **KtvStateDatabase**: 播放状态数据库

### 依赖库
- IJKPlayer: 音视频播放
- OKHttp: 网络请求
- SQLite: 本地数据库

## 项目结构

```
app/src/main/java/com/local/ktv/
├── MainActivity.kt          # 主界面与业务逻辑
├── KtvApplication.kt        # 应用入口
├── Song.kt                  # 歌曲数据模型
├── MuseDatabase.kt          # 歌曲数据库
├── SongLibrary.kt           # 歌曲库管理
├── SongApiClient.kt         # 歌曲API客户端
├── SongOkDownloadManager.kt # 下载管理
├── KtvStore.kt              # 配置存储
├── KtvStateDatabase.kt      # 状态数据库
├── KtvVideoView.kt          # 视频播放组件
├── KtvPlaybackEngine.kt     # 播放引擎
├── LocalRemoteServer.kt     # 远程服务器
├── DownloadTask.kt         # 下载任务
├── CatalogClient.kt         # 目录客户端
├── TsDecryptor.kt          # TS视频解密
├── player/                  # 播放器相关
├── BootReceiver.kt          # 启动接收器
└── adapters/               # 列表适配器
```

## 使用说明

1. **安装**: 将应用安装到Android设备
2. **初始化**: 首次启动会自动下载歌曲数据库
3. **点歌**: 通过触摸屏或遥控器选择歌曲
4. **播放**: 选择歌曲后自动开始播放
5. **控制**: 使用底部控制栏或遥控器进行播放控制

## 配置要求

- Android 5.0+
- 支持触摸屏或遥控器
- 建议存储空间充足以缓存歌曲