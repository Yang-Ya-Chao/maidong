# Maidong-KTV

Maidong-KTV is a karaoke song selection application running on Android devices, supporting both touchscreen and remote control operations. The system provides comprehensive functions for song browsing, playback, and management, making it suitable for home karaoke setups and commercial KTV rooms.

## Features

### Song Management
- Categorized song browsing (by language, artist, genre, charts, etc.)
- Song search (by title, artist, Pinyin initials)
- Song collection and playlist management
- Download management (supports caching songs locally)
- USB drive song import

### Playback Functions
- Song playback controls (play, pause, skip, replay)
- Original audio / backing track switching
- Volume adjustment (music volume, microphone volume)
- Multiple audio effect modes (pop, rock, ballad, folk, etc.)
- Ambient lighting effects
- Synchronized lyric display

### System Settings
- Playback mode settings (single loop, sequential play, auto-next)
- Recording function (records vocal performances)
- Rating system
- Screen brightness adjustment
- Storage space management
- Automatic cleanup of downloaded files

### Network & Synchronization
- Remote smartphone song selection (via local network)
- Song library synchronization
- Playback state persistence

## Technical Architecture

### Core Components
- **KtvApplication**: Application entry point, responsible for initialization
- **MainActivity**: Main interface containing all business logic
- **KtvPlaybackEngine**: Playback engine implemented using IJKPlayer
- **KtvVideoView**: Video playback component
- **MuseDatabase**: Song database supporting SQLite
- **SongLibrary**: Song library management
- **SongOkDownloadManager**: Download manager
- **KtvStore**: Configuration storage
- **KtvStateDatabase**: Playback state database

### Dependencies
- IJKPlayer: Audio/Video playback
- OKHttp: Network requests
- SQLite: Local database

## Project Structure

```
app/src/main/java/com/local/ktv/
├── MainActivity.kt          # Main interface and business logic
├── KtvApplication.kt        # Application entry point
├── Song.kt                  # Song data model
├── MuseDatabase.kt          # Song database
├── SongLibrary.kt           # Song library management
├── SongApiClient.kt         # Song API client
├── SongOkDownloadManager.kt # Download manager
├── KtvStore.kt              # Configuration storage
├── KtvStateDatabase.kt      # State database
├── KtvVideoView.kt          # Video playback component
├── KtvPlaybackEngine.kt     # Playback engine
├── LocalRemoteServer.kt     # Remote server
├── DownloadTask.kt          # Download task
├── CatalogClient.kt         # Catalog client
├── TsDecryptor.kt           # TS video decryption
├── player/                  # Player-related components
├── BootReceiver.kt          # Boot receiver
└── adapters/                # List adapters
```

## Usage Instructions

1. **Installation**: Install the app on an Android device
2. **Initialization**: On first launch, the song database will be automatically downloaded
3. **Song Selection**: Choose songs via touchscreen or remote control
4. **Playback**: Songs begin playing automatically after selection
5. **Control**: Use the bottom control bar or remote control for playback operations

## System Requirements

- Android 5.0+
- Touchscreen or remote control support
- Recommended: Sufficient storage space for song caching