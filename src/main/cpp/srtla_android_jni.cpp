/*
 * srtla_android_jni.cpp - Minimal JNI wrapper for Android-patched SRTLA
 * 
 * This is the ONLY wrapper code needed. All SRTLA functionality comes
 * from the original srtla_send.c with minimal Android patches.
 */

#include <jni.h>
#include <pthread.h>
#include <android/log.h>
#include <cstring>  // For strncpy

// Forward declare the Android SRTLA functions from patched srtla_send.c
extern "C" int srtla_start_android(const char* listen_port, const char* srtla_host, 
                                  const char* srtla_port, const char* ips_file);
extern "C" void srtla_stop_android(void);

static pthread_t srtla_thread;
static bool srtla_running = false;

struct SrtlaParams {
    char listen_port[16];
    char srtla_host[256];
    char srtla_port[16];
    char ips_file[512];
};

// Thread function to run SRTLA
static void* srtla_thread_func(void* args) {
    SrtlaParams* params = (SrtlaParams*)args;
    
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Starting SRTLA with original code...");
    
    // Call the Android-patched SRTLA function - this is 99% original SRTLA!
    int result = srtla_start_android(params->listen_port, params->srtla_host,
                                   params->srtla_port, params->ips_file);
    
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "SRTLA finished with result: %d", result);
    
    delete params;
    srtla_running = false;
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_srtla_MainActivity_startSrtlaNative(JNIEnv *env, jobject thiz,
                                                     jstring listen_port, jstring srtla_host,
                                                     jstring srtla_port, jstring ips_file) {
    if (srtla_running) {
        return -1; // Already running
    }
    
    // Convert Java strings to C strings
    const char* c_listen_port = env->GetStringUTFChars(listen_port, nullptr);
    const char* c_srtla_host = env->GetStringUTFChars(srtla_host, nullptr);
    const char* c_srtla_port = env->GetStringUTFChars(srtla_port, nullptr);
    const char* c_ips_file = env->GetStringUTFChars(ips_file, nullptr);
    
    // Create parameter struct for thread
    SrtlaParams* params = new SrtlaParams();
    strncpy(params->listen_port, c_listen_port, sizeof(params->listen_port) - 1);
    strncpy(params->srtla_host, c_srtla_host, sizeof(params->srtla_host) - 1);
    strncpy(params->srtla_port, c_srtla_port, sizeof(params->srtla_port) - 1);
    strncpy(params->ips_file, c_ips_file, sizeof(params->ips_file) - 1);
    
    // Release Java strings
    env->ReleaseStringUTFChars(listen_port, c_listen_port);
    env->ReleaseStringUTFChars(srtla_host, c_srtla_host);
    env->ReleaseStringUTFChars(srtla_port, c_srtla_port);
    env->ReleaseStringUTFChars(ips_file, c_ips_file);
    
    // Start SRTLA in background thread
    srtla_running = true;
    if (pthread_create(&srtla_thread, nullptr, srtla_thread_func, params) != 0) {
        srtla_running = false;
        delete params;
        return -1;
    }
    
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_srtla_MainActivity_stopSrtlaNative(JNIEnv *env, jobject thiz) {
    if (!srtla_running) {
        return 0;
    }
    
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Stopping SRTLA process...");
    
    // Signal the SRTLA process to stop gracefully
    srtla_stop_android();
    
    // Wait for thread to finish
    pthread_join(srtla_thread, nullptr);
    srtla_running = false;
    
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "SRTLA process stopped");
    
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_srtla_MainActivity_isRunningSrtlaNative(JNIEnv *env, jobject thiz) {
    return srtla_running;
}