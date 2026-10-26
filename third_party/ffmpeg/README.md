# Native FFmpeg playback

The app links directly to FFmpeg. There is no Media3, ExoPlayer, NextLib, system
MediaPlayer decoding, or file-extension/MIME decoder allowlist in this pipeline.
`libavformat` probes and demuxes input, `libavcodec` decodes audio/video,
`libavfilter` changes pitch without changing playback speed, and `libswscale`
converts video to RGBA. Android AudioTrack and ANativeWindow present decoded data.

## Pinned native distribution

- Original artifact: `com.arthenica:ffmpeg-kit-full:6.0.LTS` (LGPL full variant).
- FFmpeg binary version: `6.0(front)`; C headers: upstream FFmpeg 6.0.
- Four ABIs: armeabi-v7a, arm64-v8a, x86, x86_64. Minimum Android API remains 21.
- Six FFmpeg shared libraries are used directly. The FFmpegKit Java/CLI wrappers
  and NextLib are not dependencies. The C++ runtime comes from the selected NDK.
- `tools/prepare_ffmpeg.py` records exact source URLs and SHA-256 digests and
  restores the libraries/headers. Downloads use a mirror because the original
  artifacts were removed from Maven Central. Hash mismatch aborts preparation.
- LGPL and third-party license texts ship in `assets/ffmpeg-licenses`.
- Upstream source: https://ffmpeg.org/releases/ffmpeg-6.0.tar.xz
- Distribution source/build scripts: https://github.com/arthenica/ffmpeg-kit/tree/v6.0.LTS
- Original source information: https://github.com/arthenica/ffmpeg-kit/wiki/Source

This is an explicitly pinned API-21/32-bit-compatible distribution, not a claim
that it is the newest FFmpeg release. A future library upgrade needs matched
headers, all four ABIs, and the regression matrix below. The prebuilt libraries
must be rebuilt before claiming Android 16-KB-page compatibility.

## Threading and ownership

Each media item owns an independent native session. Open/close/join run on a
serial control executor. Native demux, audio and video each have a worker thread.
Packet queues are bounded. Stop interrupts blocking I/O and wakes the queues;
old callbacks cannot affect a replacement song. Seek/track switching clears
queues and decoder/filter buffers using a new serial. Surface replacement is
protected independently and does not restart the source.

Decoded audio always becomes stereo, 48-kHz, signed 16-bit PCM before KTV
channel/gain processing. Video follows the AudioTrack playback head rather than
decoded-byte counts; video-only playback and the video tail use a monotonic clock.
Network input has a 15-second I/O timeout. HTTPS uses Android's trusted CA store
exported to FFmpeg's `ca_file`, with TLS verification enabled. SAF/content input
uses a duplicated descriptor and custom FFmpeg AVIO, including asset offsets.

## Validation

`DecoderTestActivity` is a debug-only regression harness. It copies local test
assets, fully decodes every audio/video stream to EOF using the APK's own FFmpeg
libraries, then exercises the production player: first frame, nonzero PCM,
pause/resume, seeking, second audio track, pitch/channel processing, Surface
transfer and natural completion. Test media is ignored by Git and never belongs
in a release build. Emulator PCM/frame results do not establish TV/HDMI listening
quality, ARM performance or real-device compatibility.

Test songs are excluded from APKs by default. For an isolated regression build,
set the Gradle property `includeDecoderSamples`; subsequent installs can reuse
files in the app's external-files `ffmpeg-validation` directory. That test folder
is preserved by app updates. The harness also measures decoded duration so a
truncated sample's stale container duration does not make a seek test invalid.

After a seek, RV40 may initially reject packets that lack reference pictures.
The video worker permits at most 180 invalid preroll packets until it decodes
the requested frame, then restores normal fatal-error handling. This does not
enable silent decoder fallback or suppress persistent errors during playback.
