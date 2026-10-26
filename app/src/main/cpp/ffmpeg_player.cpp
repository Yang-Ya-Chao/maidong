#include <jni.h>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <atomic>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <unistd.h>
#include <cerrno>
extern "C" {
#include <libavformat/avformat.h>
#include <libavcodec/avcodec.h>
#include <libavfilter/avfilter.h>
#include <libavfilter/buffersrc.h>
#include <libavfilter/buffersink.h>
#include <libavutil/opt.h>
#include <libavutil/imgutils.h>
#include <libavutil/time.h>
#include <libswscale/swscale.h>
}

namespace {
JavaVM* vm = nullptr;
struct Env {
    JNIEnv* e = nullptr;
    bool attached = false;
    Env() {
        if (vm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_6) != JNI_OK) {
            attached = vm->AttachCurrentThread(&e, nullptr) == JNI_OK;
        }
    }
    ~Env() { if (attached) vm->DetachCurrentThread(); }
};
std::string errorText(int code) {
    char text[AV_ERROR_MAX_STRING_SIZE];
    av_strerror(code, text, sizeof(text));
    return text;
}
struct Packet {
    AVPacket* data = nullptr;
    int serial = 0;
    bool flush = false;
    bool eof = false;
    int track = -1;
    int64_t target = 0;
};
struct Queue {
    std::mutex mutex;
    std::condition_variable changed;
    std::deque<Packet> packets;
    size_t bytes = 0;
    void clearLocked() {
        for (auto& p : packets) av_packet_free(&p.data);
        packets.clear(); bytes = 0;
    }
    ~Queue() { clearLocked(); }
    void reset(Packet p) {
        std::lock_guard<std::mutex> lock(mutex);
        clearLocked(); packets.push_back(p); changed.notify_all();
    }
};
struct Player {
    jobject owner;
    jmethodID prepared, failure, firstFrame, pcm, audioReset, audioEnd, complete, clock;
    std::atomic<bool> stop{false}, playing{false}, failed{false};
    std::atomic<int> serial{1}, audioDone{0}, videoDone{0}, pitch{0};
    std::atomic<int64_t> command{0}, applied{0};
    std::mutex control, surfaceMutex;
    int64_t seekTarget = 0;
    int requestedAudio = 0;
    bool audioOnly;
    AVFormatContext* format = nullptr;
    std::vector<int> audioTracks;
    int videoTrack = -1;
    int64_t duration = 0, originUs = 0;
    int inputFd = -1;
    int64_t fdStart = 0, fdLength = -1, fdPosition = 0;
    AVIOContext* customIo = nullptr;
    ANativeWindow* window = nullptr;
    Queue audioQueue, videoQueue;
    std::thread reader, audioThread, videoThread;

    Player(JNIEnv* e, jobject object, bool only) : audioOnly(only) {
        owner = e->NewGlobalRef(object);
        jclass c = e->GetObjectClass(object);
        prepared = e->GetMethodID(c, "onNativePrepared", "(JIIILjava/lang/String;Ljava/lang/String;)V");
        failure = e->GetMethodID(c, "onNativeError", "(ILjava/lang/String;)V");
        firstFrame = e->GetMethodID(c, "onNativeFrame", "()V");
        pcm = e->GetMethodID(c, "onNativePcm", "(Ljava/nio/ByteBuffer;IJI)Z");
        audioReset = e->GetMethodID(c, "onNativeAudioReset", "(IJ)V");
        audioEnd = e->GetMethodID(c, "onNativeAudioEnd", "()V");
        complete = e->GetMethodID(c, "onNativeComplete", "()V");
        clock = e->GetMethodID(c, "clockMillis", "()J");
        e->DeleteLocalRef(c);
    }
    void wake() { audioQueue.changed.notify_all(); videoQueue.changed.notify_all(); }
    void cancel() { stop = true; wake(); }
    ~Player() {
        cancel();
        if (reader.joinable()) reader.join();
        if (audioThread.joinable()) audioThread.join();
        if (videoThread.joinable()) videoThread.join();
        avformat_close_input(&format);
        if (customIo) { av_freep(&customIo->buffer); avio_context_free(&customIo); }
        if (inputFd >= 0) close(inputFd);
        if (window) ANativeWindow_release(window);
        Env env; env.e->DeleteGlobalRef(owner);
    }
    bool checkJava(JNIEnv* e) {
        if (!e->ExceptionCheck()) return true;
        e->ExceptionDescribe(); e->ExceptionClear(); cancel(); return false;
    }
    void fail(int code, const std::string& message) {
        if (stop || failed.exchange(true)) return;
        __android_log_print(ANDROID_LOG_ERROR, "KtvFFmpeg", "%s", message.c_str());
        Env env;
        jstring text = env.e->NewStringUTF(message.c_str());
        env.e->CallVoidMethod(owner, failure, code, text);
        env.e->DeleteLocalRef(text); checkJava(env.e); cancel();
    }
    bool active(int generation) { return !stop && serial == generation; }
    bool waitPlaying(int generation) {
        while (active(generation) && !playing) av_usleep(5000);
        return active(generation);
    }
    int64_t position(JNIEnv* e) {
        auto result = e->CallLongMethod(owner, clock); checkJava(e); return result;
    }
    bool push(Queue& q, Packet p, int64_t version) {
        std::unique_lock<std::mutex> lock(q.mutex);
        q.changed.wait(lock, [&]{ return stop || command != version ||
            (q.packets.size() < 160 && q.bytes < 8 * 1024 * 1024); });
        if (stop || command != version) { av_packet_free(&p.data); return false; }
        if (p.data) q.bytes += p.data->size;
        q.packets.push_back(p); q.changed.notify_all(); return true;
    }
    bool pop(Queue& q, Packet& p) {
        std::unique_lock<std::mutex> lock(q.mutex);
        q.changed.wait(lock, [&]{ return stop || !q.packets.empty(); });
        if (stop) return false;
        p = q.packets.front(); q.packets.pop_front();
        if (p.data) q.bytes -= p.data->size;
        q.changed.notify_all(); return true;
    }
    AVCodecContext* decoder(int track) {
        AVCodecParameters* parameters = format->streams[track]->codecpar;
        const AVCodec* codec = avcodec_find_decoder(parameters->codec_id);
        if (!codec) { fail(4005, "FFmpeg decoder unavailable: " + std::string(avcodec_get_name(parameters->codec_id))); return nullptr; }
        AVCodecContext* context = avcodec_alloc_context3(codec);
        if (!context) { fail(4001, "Decoder allocation failed"); return nullptr; }
        int result = avcodec_parameters_to_context(context, parameters);
        context->pkt_timebase = format->streams[track]->time_base;
        context->thread_count = 2;
        if (result >= 0) result = avcodec_open2(context, codec, nullptr);
        if (result < 0) { avcodec_free_context(&context); fail(4001, errorText(result)); }
        return context;
    }
    static int interrupted(void* opaque) { return static_cast<Player*>(opaque)->stop ? 1 : 0; }
    static int readFd(void* opaque, uint8_t* data, int size) {
        auto p = static_cast<Player*>(opaque);
        if (p->stop) return AVERROR_EXIT;
        if (p->fdLength >= 0) size = static_cast<int>(std::min<int64_t>(size, p->fdLength - p->fdPosition));
        if (size <= 0) return AVERROR_EOF;
        int result = static_cast<int>(read(p->inputFd, data, size));
        if (result > 0) p->fdPosition += result;
        return result > 0 ? result : (result == 0 ? AVERROR_EOF : AVERROR(errno));
    }
    static int64_t seekFd(void* opaque, int64_t offset, int whence) {
        auto p = static_cast<Player*>(opaque);
        if (whence == AVSEEK_SIZE) return p->fdLength;
        whence &= ~AVSEEK_FORCE;
        int64_t target = whence == SEEK_SET ? offset : whence == SEEK_CUR ? p->fdPosition + offset : p->fdLength + offset;
        if (target < 0 || (p->fdLength >= 0 && target > p->fdLength)) return AVERROR(EINVAL);
        int64_t result = lseek64(p->inputFd, p->fdStart + target, SEEK_SET);
        if (result < 0) return AVERROR(errno);
        return p->fdPosition = result - p->fdStart;
    }
    void open(const std::string& url, int fd, int64_t start, int64_t length, const std::string& caFile) {
        if (stop) return;
        avformat_network_init();
        format = avformat_alloc_context();
        if (!format) { fail(4001, "Format allocation failed"); return; }
        format->interrupt_callback = {interrupted, this};
        if (fd >= 0) {
            inputFd = dup(fd); fdStart = start; fdLength = length;
            if (inputFd < 0) { fail(2000, "Content descriptor duplication failed"); return; }
            lseek64(inputFd, start, SEEK_SET);
            customIo = avio_alloc_context(static_cast<unsigned char*>(av_malloc(65536)), 65536, 0, this, readFd, nullptr, seekFd);
            if (!customIo) { fail(2000, "Content IO allocation failed"); return; }
            format->pb = customIo; format->flags |= AVFMT_FLAG_CUSTOM_IO;
        }
        AVDictionary* options = nullptr;
        av_dict_set(&options, "rw_timeout", "15000000", 0);
        av_dict_set(&options, "tls_verify", "1", 0);
        if (!caFile.empty()) av_dict_set(&options, "ca_file", caFile.c_str(), 0);
        int result = avformat_open_input(&format, fd >= 0 ? nullptr : url.c_str(), nullptr, &options);
        av_dict_free(&options);
        if (result >= 0) result = avformat_find_stream_info(format, nullptr);
        if (result < 0) { fail(2000, "Open media: " + errorText(result)); return; }
        if (stop) return;
        originUs = format->start_time == AV_NOPTS_VALUE ? 0 : format->start_time;
        duration = format->duration == AV_NOPTS_VALUE ? 0 : format->duration / 1000;
        int bestVideo = av_find_best_stream(format, AVMEDIA_TYPE_VIDEO, -1, -1, nullptr, 0);
        videoTrack = audioOnly || bestVideo < 0 ? -1 : bestVideo;
        for (unsigned i = 0; i < format->nb_streams; ++i)
            if (format->streams[i]->codecpar->codec_type == AVMEDIA_TYPE_AUDIO) audioTracks.push_back(i);
        if (audioTracks.empty() && videoTrack < 0) { fail(4005, "No playable audio/video tracks"); return; }
        // Check actual native codec availability, without a MIME allowlist.
        for (int index : {videoTrack, audioTracks.empty() ? -1 : audioTracks[0]}) {
            if (index >= 0 && !avcodec_find_decoder(format->streams[index]->codecpar->codec_id)) {
                fail(4005, "Missing native decoder: " + std::string(avcodec_get_name(format->streams[index]->codecpar->codec_id))); return;
            }
        }
        Env env;
        auto vp = videoTrack >= 0 ? format->streams[videoTrack]->codecpar : nullptr;
        auto ap = audioTracks.empty() ? nullptr : format->streams[audioTracks[0]]->codecpar;
        jstring videoName = env.e->NewStringUTF(vp ? avcodec_get_name(vp->codec_id) : "");
        jstring audioName = env.e->NewStringUTF(ap ? avcodec_get_name(ap->codec_id) : "");
        env.e->CallVoidMethod(owner, prepared, static_cast<jlong>(duration), vp ? vp->width : 0, vp ? vp->height : 0,
            static_cast<jint>(audioTracks.size()), videoName, audioName);
        env.e->DeleteLocalRef(videoName); env.e->DeleteLocalRef(audioName);
        if (!checkJava(env.e) || stop) return;
        audioQueue.reset({nullptr, 1, true, false, audioTracks.empty() ? -1 : audioTracks[0], 0});
        videoQueue.reset({nullptr, 1, true, false, videoTrack, 0});
        if (!audioTracks.empty()) audioThread = std::thread([this]{ decodeAudio(); }); else audioDone = 1;
        if (videoTrack >= 0) videoThread = std::thread([this]{ decodeVideo(); }); else videoDone = 1;
        reader = std::thread([this]{ demux(); });
    }
    void requestSeek(int64_t target, int audio) {
        std::lock_guard<std::mutex> lock(control);
        seekTarget = std::max<int64_t>(0, target);
        if (audio >= 0) requestedAudio = audio;
        ++command; wake();
    }
    void demux() {
        Env env;
        int64_t version = 0;
        int selected = 0;
        bool eof = false, notified = false;
        AVPacket* packet = av_packet_alloc();
        while (!stop) {
            if (command != version) {
                int64_t target, nextVersion;
                { std::lock_guard<std::mutex> lock(control); target = seekTarget; selected = requestedAudio; nextVersion = command; }
                int result = avformat_seek_file(format, -1, INT64_MIN, target * 1000 + originUs, INT64_MAX, AVSEEK_FLAG_BACKWARD);
                if (result < 0) { fail(2000, "Seek: " + errorText(result)); break; }
                version = nextVersion; applied = version;
                int generation = ++serial;
                int track = audioTracks.empty() ? -1 : audioTracks[std::min<int>(selected, audioTracks.size() - 1)];
                audioQueue.reset({nullptr, generation, true, false, track, target});
                videoQueue.reset({nullptr, generation, true, false, videoTrack, target});
                if (audioTracks.empty()) {
                    audioDone = generation;
                    env.e->CallVoidMethod(owner, audioReset, generation, static_cast<jlong>(target));
                    checkJava(env.e);
                }
                if (videoTrack < 0) videoDone = generation;
                eof = false; notified = false;
            }
            if (eof) {
                if (!notified && audioDone == serial && videoDone == serial && playing) {
                    env.e->CallVoidMethod(owner, complete); checkJava(env.e); notified = true; playing = false;
                }
                av_usleep(5000); continue;
            }
            int result = av_read_frame(format, packet);
            if (result == AVERROR_EOF) {
                push(audioQueue, {nullptr, serial, false, true}, version);
                push(videoQueue, {nullptr, serial, false, true}, version);
                eof = true; continue;
            }
            if (result < 0) { if (!stop) fail(2000, "Read media: " + errorText(result)); break; }
            int audioIndex = audioTracks.empty() ? -1 : audioTracks[std::min<int>(selected, audioTracks.size() - 1)];
            Queue* queue = packet->stream_index == audioIndex ? &audioQueue : packet->stream_index == videoTrack ? &videoQueue : nullptr;
            if (queue) push(*queue, {av_packet_clone(packet), serial}, version);
            av_packet_unref(packet);
        }
        av_packet_free(&packet);
    }
    int64_t pts(AVFrame* frame, int track, int64_t fallback) {
        return frame->best_effort_timestamp == AV_NOPTS_VALUE ? fallback :
            (av_rescale_q(frame->best_effort_timestamp, format->streams[track]->time_base, AV_TIME_BASE_Q) - originUs) / 1000;
    }
    bool send(AVCodecContext* codec, AVPacket* packet) {
        int result = avcodec_send_packet(codec, packet);
        if (result < 0 && result != AVERROR_EOF) { fail(4003, "Decode packet: " + errorText(result)); return false; }
        return true;
    }
    void decodeAudio() {
        Env env;
        AVCodecContext* codec = nullptr;
        AVFilterGraph* graph = nullptr;
        AVFilterContext *source = nullptr, *sink = nullptr;
        AVFrame* frame = av_frame_alloc();
        AVFrame* output = av_frame_alloc();
        int track = -1, generation = 1, graphPitch = 999;
        int64_t target = 0, nextPts = 0, outputPts = 0;
        auto freeGraph = [&]{ avfilter_graph_free(&graph); source = sink = nullptr; graphPitch = 999; };
        auto drain = [&]() {
            int result;
            while ((result = av_buffersink_get_frame(sink, output)) >= 0) {
                if (!waitPlaying(generation)) { av_frame_unref(output); return false; }
                int bytes = output->nb_samples * 4;
                jobject buffer = env.e->NewDirectByteBuffer(output->data[0], bytes);
                bool ok = env.e->CallBooleanMethod(owner, pcm, buffer, bytes, static_cast<jlong>(outputPts), generation);
                env.e->DeleteLocalRef(buffer);
                outputPts += output->nb_samples * 1000LL / 48000;
                av_frame_unref(output);
                if (!checkJava(env.e) || !ok) return false;
            }
            if (result != AVERROR(EAGAIN) && result != AVERROR_EOF) { fail(4003, "Audio filter: " + errorText(result)); return false; }
            return true;
        };
        Packet packet;
        while (pop(audioQueue, packet)) {
            if (packet.flush) {
                generation = packet.serial; track = packet.track; target = nextPts = outputPts = packet.target;
                freeGraph(); avcodec_free_context(&codec); codec = decoder(track);
                if (!codec) break;
                env.e->CallVoidMethod(owner, audioReset, generation, static_cast<jlong>(target));
                if (!checkJava(env.e)) break;
                continue;
            }
            if (!active(packet.serial) || !codec) { av_packet_free(&packet.data); continue; }
            if (!send(codec, packet.data)) { av_packet_free(&packet.data); break; }
            av_packet_free(&packet.data);
            int result;
            while ((result = avcodec_receive_frame(codec, frame)) >= 0) {
                if (!active(generation)) { av_frame_unref(frame); break; }
                int64_t timestamp = pts(frame, track, nextPts);
                nextPts = timestamp + frame->nb_samples * 1000LL / frame->sample_rate;
                if (nextPts <= target) { av_frame_unref(frame); continue; }
                if (!graph || graphPitch != pitch) {
                    if (graph) { av_buffersrc_add_frame_flags(source, nullptr, 0); if (!drain() && !active(generation)) { av_frame_unref(frame); break; } }
                    freeGraph(); graphPitch = pitch;
                    graph = avfilter_graph_alloc(); graph->nb_threads = 1;
                    char layout[128], arguments[512];
                    av_channel_layout_describe(&frame->ch_layout, layout, sizeof(layout));
                    snprintf(arguments, sizeof(arguments), "time_base=1/%d:sample_rate=%d:sample_fmt=%s:channel_layout=%s",
                        frame->sample_rate, frame->sample_rate, av_get_sample_fmt_name(static_cast<AVSampleFormat>(frame->format)), layout);
                    result = avfilter_graph_create_filter(&source, avfilter_get_by_name("abuffer"), "input", arguments, nullptr, graph);
                    if (result >= 0) result = avfilter_graph_create_filter(&sink, avfilter_get_by_name("abuffersink"), "output", nullptr, nullptr, graph);
                    double ratio = pow(2.0, graphPitch / 12.0);
                    char filters[256];
                    snprintf(filters, sizeof(filters), "asetrate=%d,aresample=48000,atempo=%.9f,aformat=sample_fmts=s16:sample_rates=48000:channel_layouts=stereo",
                        static_cast<int>(frame->sample_rate * ratio), 1.0 / ratio);
                    AVFilterInOut *inputs = avfilter_inout_alloc(), *outputs = avfilter_inout_alloc();
                    outputs->name = av_strdup("in"); outputs->filter_ctx = source; outputs->pad_idx = 0;
                    inputs->name = av_strdup("out"); inputs->filter_ctx = sink; inputs->pad_idx = 0;
                    if (result >= 0) result = avfilter_graph_parse_ptr(graph, filters, &inputs, &outputs, nullptr);
                    avfilter_inout_free(&inputs); avfilter_inout_free(&outputs);
                    if (result >= 0) result = avfilter_graph_config(graph, nullptr);
                    if (result < 0) { fail(4003, "Configure audio filter: " + errorText(result)); av_frame_unref(frame); break; }
                }
                frame->pts = av_rescale_q(timestamp, {1, 1000}, {1, frame->sample_rate});
                result = av_buffersrc_add_frame_flags(source, frame, AV_BUFFERSRC_FLAG_KEEP_REF);
                av_frame_unref(frame);
                if (result < 0) { fail(4003, "Filter input: " + errorText(result)); break; }
                if (!drain()) break;
            }
            if (result < 0 && result != AVERROR(EAGAIN) && result != AVERROR_EOF) { fail(4003, "Decode audio: " + errorText(result)); break; }
            if (packet.eof && active(generation)) {
                if (graph) { av_buffersrc_add_frame_flags(source, nullptr, 0); drain(); }
                // Let queued PCM reach the output before signaling completion.
                int64_t until = av_gettime_relative() + 3000000;
                while (active(generation) && playing && position(env.e) + 30 < nextPts && av_gettime_relative() < until) av_usleep(5000);
                if (active(generation)) { env.e->CallVoidMethod(owner, audioEnd); checkJava(env.e); }
                audioDone = generation;
            }
        }
        freeGraph(); avcodec_free_context(&codec); av_frame_free(&frame); av_frame_free(&output);
    }
    void decodeVideo() {
        Env env;
        AVCodecContext* codec = decoder(videoTrack);
        if (!codec) return;
        AVFrame* frame = av_frame_alloc();
        SwsContext* scaler = nullptr;
        int generation = 1;
        int64_t target = 0, nextPts = 0;
        bool seekPreroll = false;
        int prerollErrors = 0;
        Packet packet;
        while (pop(videoQueue, packet)) {
            if (packet.flush) {
                avcodec_flush_buffers(codec); generation = packet.serial; target = nextPts = packet.target;
                seekPreroll = target > 0; prerollErrors = 0; continue;
            }
            if (!active(packet.serial)) { av_packet_free(&packet.data); continue; }
            int sent = avcodec_send_packet(codec, packet.data);
            av_packet_free(&packet.data);
            // Matroska RV40 cue points can precede a decodable reference picture.
            // Permit bounded decoder preroll only after a seek, never during normal playback.
            if (sent == AVERROR_INVALIDDATA && seekPreroll && ++prerollErrors <= 180) continue;
            if (sent < 0 && sent != AVERROR_EOF) { fail(4003, "Decode video packet: " + errorText(sent)); break; }
            int result;
            while ((result = avcodec_receive_frame(codec, frame)) >= 0) {
                int64_t timestamp = pts(frame, videoTrack, nextPts);
                nextPts = timestamp + 40;
                if (timestamp < target || !waitPlaying(generation)) { av_frame_unref(frame); continue; }
                seekPreroll = false;
                int64_t difference;
                while (active(generation) && (difference = timestamp - position(env.e)) > 10) {
                    if (!waitPlaying(generation)) break;
                    av_usleep(static_cast<unsigned>(std::min<int64_t>(difference * 1000, 10000)));
                }
                if (active(generation) && timestamp + 150 >= position(env.e)) {
                    std::lock_guard<std::mutex> lock(surfaceMutex);
                    if (window) {
                        ANativeWindow_setBuffersGeometry(window, frame->width, frame->height, WINDOW_FORMAT_RGBA_8888);
                        ANativeWindow_Buffer buffer;
                        if (ANativeWindow_lock(window, &buffer, nullptr) == 0) {
                            scaler = sws_getCachedContext(scaler, frame->width, frame->height, static_cast<AVPixelFormat>(frame->format),
                                frame->width, frame->height, AV_PIX_FMT_RGBA, SWS_BILINEAR, nullptr, nullptr, nullptr);
                            if (scaler && buffer.width >= frame->width && buffer.height >= frame->height) {
                                uint8_t* dst[] = {static_cast<uint8_t*>(buffer.bits), nullptr, nullptr, nullptr};
                                int strides[] = {buffer.stride * 4, 0, 0, 0};
                                sws_scale(scaler, frame->data, frame->linesize, 0, frame->height, dst, strides);
                                ANativeWindow_unlockAndPost(window);
                                env.e->CallVoidMethod(owner, firstFrame); checkJava(env.e);
                            } else ANativeWindow_unlockAndPost(window);
                        }
                    }
                }
                av_frame_unref(frame);
            }
            if (result == AVERROR_INVALIDDATA && seekPreroll && ++prerollErrors <= 180) continue;
            if (result < 0 && result != AVERROR(EAGAIN) && result != AVERROR_EOF) { fail(4003, "Decode video: " + errorText(result)); break; }
            if (packet.eof && active(generation)) videoDone = generation;
        }
        sws_freeContext(scaler); av_frame_free(&frame); avcodec_free_context(&codec);
    }
};
Player* ptr(jlong value) { return reinterpret_cast<Player*>(value); }
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* javaVm, void*) {
    vm = javaVm; av_log_set_level(AV_LOG_ERROR); return JNI_VERSION_1_6;
}
extern "C" JNIEXPORT jlong JNICALL Java_com_local_ktv_player_NativeFfmpegSession_nativeCreate(JNIEnv* e, jobject self, jboolean audioOnly) {
    return reinterpret_cast<jlong>(new Player(e, self, audioOnly));
}
extern "C" JNIEXPORT void JNICALL Java_com_local_ktv_player_NativeFfmpegSession_nativeOpen(JNIEnv* e, jobject, jlong handle, jstring source, jint fd, jlong start, jlong length, jstring caFile) {
    const char* url = e->GetStringUTFChars(source, nullptr);
    std::string copy(url); e->ReleaseStringUTFChars(source, url);
    const char* ca = e->GetStringUTFChars(caFile, nullptr);
    std::string caCopy(ca); e->ReleaseStringUTFChars(caFile, ca);
    ptr(handle)->open(copy, fd, start, length, caCopy);
}
extern "C" JNIEXPORT void JNICALL Java_com_local_ktv_player_NativeFfmpegSession_nativePlay(JNIEnv*, jobject, jlong handle, jboolean play) { ptr(handle)->playing = play; ptr(handle)->wake(); }
extern "C" JNIEXPORT void JNICALL Java_com_local_ktv_player_NativeFfmpegSession_nativeSeek(JNIEnv*, jobject, jlong handle, jlong target, jint audio) { ptr(handle)->requestSeek(target, audio); }
extern "C" JNIEXPORT void JNICALL Java_com_local_ktv_player_NativeFfmpegSession_nativePitch(JNIEnv*, jobject, jlong handle, jint step) { ptr(handle)->pitch = std::clamp<int>(step, -5, 5); }
extern "C" JNIEXPORT void JNICALL Java_com_local_ktv_player_NativeFfmpegSession_nativeSurface(JNIEnv* e, jobject, jlong handle, jobject surface) {
    auto p = ptr(handle); std::lock_guard<std::mutex> lock(p->surfaceMutex);
    if (p->window) ANativeWindow_release(p->window);
    p->window = surface ? ANativeWindow_fromSurface(e, surface) : nullptr;
}
extern "C" JNIEXPORT void JNICALL Java_com_local_ktv_player_NativeFfmpegSession_nativeStop(JNIEnv*, jobject, jlong handle) { ptr(handle)->cancel(); }
extern "C" JNIEXPORT void JNICALL Java_com_local_ktv_player_NativeFfmpegSession_nativeRelease(JNIEnv*, jobject, jlong handle) { delete ptr(handle); }

// Debug harness: decode every audio/video packet using the exact APK libraries, including EOF drain.
extern "C" JNIEXPORT jstring JNICALL Java_com_local_ktv_FfmpegValidation_checkFile(JNIEnv* e, jobject, jstring path) {
    const char* raw = e->GetStringUTFChars(path, nullptr);
    std::string file(raw); e->ReleaseStringUTFChars(path, raw);
    AVFormatContext* format = nullptr;
    int result = avformat_open_input(&format, file.c_str(), nullptr, nullptr);
    if (result >= 0) result = avformat_find_stream_info(format, nullptr);
    std::vector<AVCodecContext*> decoders;
    int64_t videoFrames = 0, audioSamples = 0, lastTimestampUs = 0;
    int audioTracks = 0, videoTracks = 0, errors = result < 0 ? 1 : 0;
    AVFrame* frame = av_frame_alloc();
    AVPacket* packet = av_packet_alloc();
    if (result >= 0) {
        decoders.resize(format->nb_streams, nullptr);
        for (unsigned i = 0; i < format->nb_streams; ++i) {
            auto p = format->streams[i]->codecpar;
            if (p->codec_type != AVMEDIA_TYPE_VIDEO && p->codec_type != AVMEDIA_TYPE_AUDIO) continue;
            if (p->codec_type == AVMEDIA_TYPE_AUDIO) ++audioTracks; else ++videoTracks;
            const AVCodec* codec = avcodec_find_decoder(p->codec_id);
            if (!codec) { ++errors; continue; }
            decoders[i] = avcodec_alloc_context3(codec);
            avcodec_parameters_to_context(decoders[i], p);
            decoders[i]->thread_count = 2;
            if (avcodec_open2(decoders[i], codec, nullptr) < 0) { ++errors; avcodec_free_context(&decoders[i]); }
        }
        auto receive = [&](AVCodecContext* codec, int index) {
            int code;
            while ((code = avcodec_receive_frame(codec, frame)) >= 0) {
                if (codec->codec_type == AVMEDIA_TYPE_VIDEO) ++videoFrames;
                else audioSamples += frame->nb_samples;
                if (frame->best_effort_timestamp != AV_NOPTS_VALUE) {
                    int64_t end = av_rescale_q(frame->best_effort_timestamp, format->streams[index]->time_base, AV_TIME_BASE_Q);
                    if (codec->codec_type == AVMEDIA_TYPE_AUDIO) end += frame->nb_samples * 1000000LL / frame->sample_rate;
                    else end += 40000;
                    lastTimestampUs = std::max(lastTimestampUs, end);
                }
                av_frame_unref(frame);
            }
            if (code != AVERROR(EAGAIN) && code != AVERROR_EOF) ++errors;
        };
        while ((result = av_read_frame(format, packet)) >= 0) {
            auto codec = decoders[packet->stream_index];
            if (codec) {
                if (avcodec_send_packet(codec, packet) < 0) ++errors;
                receive(codec, packet->stream_index);
            }
            av_packet_unref(packet);
        }
        if (result != AVERROR_EOF) ++errors;
        for (size_t index = 0; index < decoders.size(); ++index) if (auto& codec = decoders[index]) {
            if (avcodec_send_packet(codec, nullptr) < 0) ++errors;
            receive(codec, index); avcodec_free_context(&codec);
        }
    }
    av_packet_free(&packet); av_frame_free(&frame); avformat_close_input(&format);
    char text[320];
    snprintf(text, sizeof(text), "{\"errors\":%d,\"videoTracks\":%d,\"audioTracks\":%d,\"videoFrames\":%lld,\"audioSamples\":%lld,\"decodedDurationMs\":%lld}",
        errors, videoTracks, audioTracks, static_cast<long long>(videoFrames), static_cast<long long>(audioSamples), static_cast<long long>(lastTimestampUs / 1000));
    return e->NewStringUTF(text);
}
