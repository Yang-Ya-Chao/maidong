# 保留应用入口。
-keep class com.local.ktv.KtvApplication { *; }
-keep class com.local.ktv.MainActivity { *; }
-keep class com.local.ktv.BootReceiver { *; }
-dontwarn edu.umd.cs.findbugs.annotations.SuppressFBWarnings
# JNI resolves FFmpeg callbacks and native entry points by name.
-keep class com.local.ktv.player.NativeFfmpegSession { *; }
