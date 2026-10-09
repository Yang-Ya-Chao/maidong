<p align="center">
  <img src="app/src/main/res/drawable-nodpi/ktv_logo.png" width="140" alt="Maidong KTV Logo">
</p>

<h1 align="center">Maidong KTV</h1>

<p align="center">A local karaoke application for Android TV and landscape song-selection devices</p>

<p align="center">Remote Control · Song Discovery · Request Queue · Download Management · Stable Playback</p>

## Version 2.0.0

Branch: `feature/igeba-media`. The bundled catalog contains 81,801 songs, 4,807 singers, 63 category playlists, and 7 charts. Existing pages, favorites, personal playlists, and singer portraits are retained.

[Releases](https://gitee.com/yangyachao-X/maidong-ktv/releases) · [Chinese documentation](README.md)

## Core Features

- Full TV remote navigation with directional keys, confirmation, back, and long-press actions.
- Consistent focus feedback for buttons, lists, cards, switches, and dialogs.
- Home entries for charts, song titles, singers, frequently played songs, favorites, categories, and local songs.
- Online and local search with language filters, paging, and initial-letter keyboard input.
- Singer browsing with portrait tags and singer-specific song lists.
- Song ordering, batch ordering, queue prioritization, favorites, and file management.
- Real-time synchronization across queued, played, and downloaded song lists.
- New requests join the end of the queue without interrupting the current requested song.
- Automatic playback when requested songs are available, including smooth transitions from public playback.

## Playback

- AndroidX Media3 playback for local and downloaded songs.
- Continuous playback while navigating between pages or switching between windowed and full-screen modes.
- Play, pause, skip, replay, progress display, and seeking controls.
- Original-vocal and accompaniment switching without changing the current play or pause state.
- Synchronized playback state across windowed controls, full-screen controls, and status overlays.
- Song title overlays, temporary mode prompts, and persistent pause indicators.
- Video ratio, automatic full screen, audio-video synchronization, and public playback settings.
- Maximum song volume and configurable original-vocal and accompaniment behavior.

## Downloads And Data

- Background downloads with progress, resume support, and automatic retry.
- File completeness checks with retry and removal of failed tasks.
- Recognition, counting, and playback of downloaded local songs.
- Bundled song, singer, playlist, and chart databases for offline browsing.
- Directory creation, database scanning, and download startup only after storage permission is granted.
- Local storage by default; optional USB storage falls back to local storage when unavailable, unwritable, or short of space. USB songs are scanned from the maidongktv directory.
- Unified Maidong KTV data storage to avoid conflicts with other applications.

## Settings

- Data settings for library updates, local scanning, reserved space, automatic cleanup, and a local library reset that preserves USB files.
- Playback settings for public playback, automatic full screen, video ratio, synchronization, and title overlays.
- Sound settings for volume behavior, maximum song volume, original vocals, and accompaniment.
- Interface settings for language, floating controls, carousel content, and song title overlays.
- Dialog selections remain temporary until confirmed and provide clear remote-control focus feedback.

## Interface And Interaction

- Landscape TV layout optimized for long-distance viewing.
- Consistent state and meaning across top, windowed, and full-screen playback controls.
- Stable focus during list refreshes, paging, tab changes, and song ordering.
- Focus changes color only and preserves each control's original dimensions, corners, and layout.
- A consistent circular Maidong logo across the home screen, player, and application information.

## Storage And Releases

- Local downloads: `/storage/emulated/0/MaidongKTV/video/cloud-song`.
- USB downloads: `maidongktv/video/cloud-song` under the selected volume root.
- Song metadata is bundled in `igeba_catalog.db`; portrait URL mappings are stored separately in `singer_portraits.db`.
- Personal media snapshots are stored in `personal_media.db`. Only unambiguous title-and-singer matches are mapped during legacy favorites migration.
- Library reset restores the bundled catalog and clears local download caches; USB files are preserved.
- Application updates use Gitee Release version tags and APK attachments.
