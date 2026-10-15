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

// main() asli spd_dump.c di-rename jadi ini lewat -Dmain=spd_dump_main
// (lihat CMakeLists.txt). Argumen & perilaku persis sama kayak CLI biasa.
extern "C" int spd_dump_main(int argc, char **argv);

// --- redirect stdout/stderr spd_dump ke logcat ---
// spd_dump nulis semua output lewat printf ke stdout biasa, yang di proses
// Android native nggak nongol di mana-mana secara default. Trik standar:
// bikin pipe, dup2 ke stdout/stderr, baca pipe-nya di thread terpisah,
// forward tiap baris ke __android_log_print.
// TODO Fase berikutnya: forward juga ke UI terminal app (lewat JNI callback
// ke method Kotlin), bukan cuma logcat. Buat sekarang, lihat via:
//   adb logcat -s spd_native
static void *stdout_reader_thread(void *arg) {
    int fd = *reinterpret_cast<int *>(arg);
    free(arg);
    char buf[512];
    FILE *stream = fdopen(fd, "r");
    if (!stream) return nullptr;
    while (fgets(buf, sizeof(buf), stream)) {
        size_t len = strlen(buf);
        if (len > 0 && buf[len - 1] == '\n') buf[len - 1] = '\0';
        LOGI("%s", buf);
    }
    fclose(stream);
    return nullptr;
}

static void redirect_stdio_to_logcat() {
    int pipefd[2];
    if (pipe(pipefd) != 0) {
        LOGE("pipe() gagal: %s", strerror(errno));
        return;
    }
    dup2(pipefd[1], STDOUT_FILENO);
    dup2(pipefd[1], STDERR_FILENO);
    close(pipefd[1]);
    setvbuf(stdout, nullptr, _IOLBF, 0); // line-buffered biar langsung flush

    int *readFdHeap = (int *) malloc(sizeof(int));
    *readFdHeap = pipefd[0];
    pthread_t t;
    pthread_create(&t, nullptr, stdout_reader_thread, readFdHeap);
    pthread_detach(t);
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_uni_spdusbcheck_NativeBridge_runSpdDump(
        JNIEnv *env, jobject /* this */, jint usbFd, jobjectArray extraArgs) {

    static bool stdioRedirected = false;
    if (!stdioRedirected) {
        redirect_stdio_to_logcat();
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

    LOGI("panggil spd_dump_main dengan %zu argumen (fd=%d)", argStorage.size(), usbFd);
    int ret = spd_dump_main((int) argStorage.size(), argv.data());
    LOGI("spd_dump_main selesai, return=%d", ret);
    return ret;
}
