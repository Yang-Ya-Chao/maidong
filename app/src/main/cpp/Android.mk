LOCAL_PATH := $(call my-dir)
FFMPEG_ROOT := $(LOCAL_PATH)/../../../../third_party/ffmpeg

include $(CLEAR_VARS)
LOCAL_MODULE := avutil
LOCAL_SRC_FILES := $(FFMPEG_ROOT)/lib/$(TARGET_ARCH_ABI)/libavutil.so
include $(PREBUILT_SHARED_LIBRARY)
include $(CLEAR_VARS)
LOCAL_MODULE := avcodec
LOCAL_SRC_FILES := $(FFMPEG_ROOT)/lib/$(TARGET_ARCH_ABI)/libavcodec.so
include $(PREBUILT_SHARED_LIBRARY)
include $(CLEAR_VARS)
LOCAL_MODULE := avformat
LOCAL_SRC_FILES := $(FFMPEG_ROOT)/lib/$(TARGET_ARCH_ABI)/libavformat.so
include $(PREBUILT_SHARED_LIBRARY)
include $(CLEAR_VARS)
LOCAL_MODULE := avfilter
LOCAL_SRC_FILES := $(FFMPEG_ROOT)/lib/$(TARGET_ARCH_ABI)/libavfilter.so
include $(PREBUILT_SHARED_LIBRARY)
include $(CLEAR_VARS)
LOCAL_MODULE := swresample
LOCAL_SRC_FILES := $(FFMPEG_ROOT)/lib/$(TARGET_ARCH_ABI)/libswresample.so
include $(PREBUILT_SHARED_LIBRARY)
include $(CLEAR_VARS)
LOCAL_MODULE := swscale
LOCAL_SRC_FILES := $(FFMPEG_ROOT)/lib/$(TARGET_ARCH_ABI)/libswscale.so
include $(PREBUILT_SHARED_LIBRARY)

include $(CLEAR_VARS)
LOCAL_MODULE := ktvffmpeg
LOCAL_SRC_FILES := ffmpeg_player.cpp
LOCAL_C_INCLUDES := $(FFMPEG_ROOT)/include
LOCAL_CPPFLAGS := -std=c++17 -Wall -Wextra -Werror=return-type -fexceptions
LOCAL_LDFLAGS := -Wl,-z,max-page-size=16384
LOCAL_SHARED_LIBRARIES := avformat avcodec avfilter avutil swresample swscale
LOCAL_LDLIBS := -landroid -llog
include $(BUILD_SHARED_LIBRARY)
