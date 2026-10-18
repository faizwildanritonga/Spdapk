#include <jni.h>
#include <android/log.h>
#include <unistd.h>
#include <pthread.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>
#include <string>

#define LOG_TAG "spd_native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" int spd_dump_main(int argc, char **argv);

static JavaVM *g_vm = nullptr;

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void * /*reserved*/) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

struct ReaderCtx {
    int fd;
    jobject serviceRef; // global ref ke instance UsbWatchService
};

// Baca output spd_dump baris per baris, forward ke Kotlin lewat
// UsbWatchService.onNativeLogLine(String) supaya langsung nongol di UI,
// sekaligus tetap ke logcat (tag spd_native) buat jaga-jaga.
static void *stdout_reader_thread(void *arg) {
    auto *ctx = reinterpret_cast<ReaderCtx *>(arg);
    FILE *stream = fdopen(ctx->fd, "r");
    if (!stream) {
        delete ctx;
        return nullptr;
    }

    JNIEnv *env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv((void **) &env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
    }

    jclass cls = nullptr;
    jmethodID mid = nullptr;
    if (env != nullptr) {
        cls = env->GetObjectClass(ctx->serviceRef);
        mid = env->GetMethodID(cls, "onNativeLogLine", "(Ljava/lang/String;)V");
    }

    char buf[512];
    while (fgets(buf, sizeof(buf), stream)) {
        size_t len = strlen(buf);
        if (len > 0 && buf[len - 1] == '\n') buf[len - 1] = '\0';
        LOGI("%s", buf);
        if (env != nullptr && mid != nullptr) {
            jstring jline = env->NewStringUTF(buf);
            env->CallVoidMethod(ctx->serviceRef, mid, jline);
            env->DeleteLocalRef(jline);
        }
    }
    fclose(stream);

    if (env != nullptr) env->DeleteGlobalRef(ctx->serviceRef);
    if (attached) g_vm->DetachCurrentThread();
    delete ctx;
    return nullptr;
}

static void redirect_stdio_and_spawn_reader(jobject serviceGlobalRef) {
    int pipefd[2];
    if (pipe(pipefd) != 0) {
        LOGE("pipe() gagal: %s", strerror(errno));
        return;
    }
    dup2(pipefd[1], STDOUT_FILENO);
    dup2(pipefd[1], STDERR_FILENO);
    close(pipefd[1]);
    setvbuf(stdout, nullptr, _IOLBF, 0);

    auto *ctx = new ReaderCtx{pipefd[0], serviceGlobalRef};
    pthread_t t;
    pthread_create(&t, nullptr, stdout_reader_thread, ctx);
    pthread_detach(t);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_uni_spdusbcheck_NativeBridge_runSpdDump(
        JNIEnv *env, jobject /* this (NativeBridge object) */,
        jint usbFd, jobjectArray extraArgs, jobject serviceObj) {

    static bool stdioRedirected = false;
    if (!stdioRedirected) {
        jobject globalRef = env->NewGlobalRef(serviceObj);
        redirect_stdio_and_spawn_reader(globalRef);
        stdioRedirected = true;
    }

    std::vector<std::string> argStorage;
    argStorage.push_back("spd_dump");
    argStorage.push_back("--usb-fd");
    argStorage.push_back(std::to_string(usbFd));

    if (extraArgs != nullptr) {
        jsize n = env->GetArrayLength(extraArgs);
        for (jsize i = 0; i < n; i++) {
            auto jstr = (jstring) env->GetObjectArrayElement(extraArgs, i);
            const char *cstr = env->GetStringUTFChars(jstr, nullptr);
            argStorage.emplace_back(cstr);
            env->ReleaseStringUTFChars(jstr, cstr);
            env->DeleteLocalRef(jstr);
        }
    }

    std::vector<char *> argv;
    for (auto &s : argStorage) argv.push_back(const_cast<char *>(s.c_str()));
    argv.push_back(nullptr);

    int ret = spd_dump_main((int) argStorage.size(), argv.data());
    LOGI("spd_dump_main selesai, return=%d", ret);
    return ret;
}
