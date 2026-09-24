#include <jni.h>
#include <string>

// @ https://blog.csdn.net/xlxxcc/article/details/51106721

/*
jstring charTojstring(JNIEnv* env, const char* pat) {
    //定义java String类 strClass
    jclass strClass = (env)->FindClass("Ljava/lang/String;");
    //获取String(byte[],String)的构造器,用于将本地byte[]数组转换为一个新String
    jmethodID ctorID = (env)->GetMethodID(strClass, "<init>", "([BLjava/lang/String;)V");
    //建立byte数组
    jbyteArray bytes = (env)->NewByteArray(strlen(pat));
    //将char* 转换为byte数组
    (env)->SetByteArrayRegion(bytes, 0, strlen(pat), (jbyte*) pat);
    // 设置String, 保存语言类型,用于byte数组转换至String时的参数
    jstring encoding = (env)->NewStringUTF("GB2312");
    //将byte数组转换为java String,并输出
    return (jstring) (env)->NewObject(strClass, ctorID, bytes, encoding);
}
*/

char* jstringToChar(JNIEnv* env, jstring jstr) {
    char* rtn = NULL;
    jclass clsstring = env->FindClass("java/lang/String");
    jstring strencode = env->NewStringUTF("GB2312");
    jmethodID mid = env->GetMethodID(clsstring, "getBytes", "(Ljava/lang/String;)[B");
    jbyteArray barr = (jbyteArray) env->CallObjectMethod(jstr, mid, strencode);
    jsize alen = env->GetArrayLength(barr);
    jbyte* ba = env->GetByteArrayElements(barr, JNI_FALSE);
    if (alen > 0) {
        rtn = (char*) malloc(alen + 1);
        memcpy(rtn, ba, alen);
        rtn[alen] = 0;
    }
    env->ReleaseByteArrayElements(barr, ba, 0);
    return rtn;
}


extern "C" JNIEXPORT jlong JNICALL
Java_com_omarea_vtools_SceneJNI_getKernelPropLong(
        JNIEnv *env,
        jobject,
        jstring path) {
    // std::string hello = "Hello from C++";
    // return env->NewStringUTF(hello.c_str());

    // 读取路径 /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq
    char* charData = jstringToChar(env, path);

    FILE *kernelProp = fopen(charData, "r");
    if (kernelProp == nullptr)
        return -1;
    long freq;
    fscanf(kernelProp,"%ld",&freq);
    fclose(kernelProp);
    return freq;
}

#include <dirent.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstring>
#include <cstdlib>

// Touch a single file into the page cache. Files larger than the budget are
// skipped, matching vmtouch's -m flag (per-file limit, not a global cap).
static long long scene_touch_file(const char* path, long long budget, long long* files) {
    struct stat st;
    if (stat(path, &st) != 0) return 0;
    if (!S_ISREG(st.st_mode)) return 0;
    if (st.st_size <= 0 || st.st_size > budget) return 0;

    int fd = open(path, O_RDONLY | O_NOATIME);
    if (fd < 0) fd = open(path, O_RDONLY);
    if (fd < 0) return 0;

    void* map = mmap(nullptr, (size_t) st.st_size, PROT_READ, MAP_SHARED, fd, 0);
    if (map == MAP_FAILED) {
        close(fd);
        return 0;
    }

    long page = sysconf(_SC_PAGESIZE);
    volatile char sink = 0;
    long long touched = 0;
    for (long long offset = 0; offset < st.st_size; offset += page) {
        sink = (char) (sink + ((volatile char*) map)[offset]);
        touched += page;
    }

    munmap(map, (size_t) st.st_size);
    close(fd);
    (*files)++;
    return touched;
}

static long long scene_touch_path(const char* path, long long budget, long long* files) {
    struct stat st;
    if (stat(path, &st) != 0) return 0;
    if (S_ISREG(st.st_mode)) {
        return scene_touch_file(path, budget, files);
    }
    if (!S_ISDIR(st.st_mode)) return 0;

    DIR* dir = opendir(path);
    if (dir == nullptr) return 0;

    long long touched = 0;
    struct dirent* entry;
    while ((entry = readdir(dir)) != nullptr) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) continue;
        std::string child = std::string(path) + "/" + entry->d_name;
        touched += scene_touch_path(child.c_str(), budget, files);
    }
    closedir(dir);
    return touched;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_omarea_vtools_SceneJNI_preloadPath(
        JNIEnv *env,
        jobject,
        jstring path,
        jlong budgetMb) {
    char* charData = jstringToChar(env, path);
    if (charData == nullptr) {
        return -1;
    }
    long long budget = budgetMb * 1024LL * 1024LL;
    long long files = 0;
    long long touched = scene_touch_path(charData, budget, &files);
    free(charData);
    if (files == 0) {
        return -1;
    }
    return touched;
}