#include <jni.h>
#include <string>

// @ https://blog.csdn.net/xlxxcc/article/details/51106721

/*
jstring charTojstring(JNIEnv* env, const char* pat) {
    // Define the java String class strClass
    jclass strClass = (env)->FindClass("Ljava/lang/String;");
    // Get the String(byte[],String) constructor to build a new String from a native byte[]
    jmethodID ctorID = (env)->GetMethodID(strClass, "<init>", "([BLjava/lang/String;)V");
    // Create the byte array
    jbyteArray bytes = (env)->NewByteArray(strlen(pat));
    // Copy char* into the byte array
    (env)->SetByteArrayRegion(bytes, 0, strlen(pat), (jbyte*) pat);
    // Create the encoding String used when converting the byte array back to a String
    jstring encoding = (env)->NewStringUTF("GB2312");
    // Convert the byte array to a java String and return it
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

    // Read the path /sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq
    char* charData = jstringToChar(env, path);

    FILE *kernelProp = fopen(charData, "r");
    if (kernelProp == nullptr)
        return -1;
    long freq;
    fscanf(kernelProp,"%ld",&freq);
    fclose(kernelProp);
    return freq;
}
