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
#include <cstdio>   // For snprintf
#include <unistd.h>  // For sleep
#include <sys/socket.h>
#include <netinet/in.h>
#include <errno.h>

// Forward declare the Android SRTLA functions from patched srtla_send.c
extern "C" int srtla_start_android(const char* listen_port, const char* srtla_host, 
                                  const char* srtla_port, const char* ips_file);
extern "C" void srtla_stop_android(void);
extern "C" void schedule_update_conns(int signal);

// Stats functions
extern "C" int srtla_get_connection_count(void);
extern "C" int srtla_get_active_connection_count(void);
extern "C" int srtla_get_total_in_flight_packets(void);
extern "C" int srtla_get_total_window_size(void);
extern "C" int srtla_get_connection_details(char* buffer, int buffer_size);

// Virtual IP functions
extern "C" void srtla_set_network_socket(const char* virtual_ip, const char* real_ip, 
                                        int network_type, int socket_fd);

// Per-connection bitrate functions
extern "C" int srtla_get_connection_bitrates(double* bitrates_mbps, char connection_types[][16], 
                                            char connection_ips[][64], int* load_percentages,
                                            int max_connections);

// Comprehensive connection window data function
extern "C" int srtla_get_connection_window_data(double* bitrates_mbps, char connection_types[][16], 
                                               char connection_ips[][64], int* load_percentages,
                                               int* window_sizes, int* inflight_packets,
                                               int max_connections);

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
Java_com_example_srtla_NativeSrtlaJni_startSrtlaNative(JNIEnv *env, jclass clazz,
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
Java_com_example_srtla_NativeSrtlaJni_stopSrtlaNative(JNIEnv *env, jclass clazz) {
    if (!srtla_running) {
        return 0;
    }
    
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Stopping SRTLA process...");
    
    // Signal the SRTLA process to stop gracefully
    srtla_stop_android();
    
    // Mark as not running immediately - the thread will finish cleanup
    srtla_running = false;
    
    // Detach the thread so it can clean up itself without blocking the UI
    pthread_detach(srtla_thread);
    
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "SRTLA stop signal sent, thread detached");
    
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_srtla_NativeSrtlaJni_isRunningSrtlaNative(JNIEnv *env, jclass clazz) {
    return srtla_running;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_srtla_NativeSrtlaJni_notifyNetworkChange(JNIEnv *env, jclass clazz) {
    if (srtla_running) {
        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Network change notification received");
        schedule_update_conns(0);  // Pass 0 as dummy signal parameter
    } else {
        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Network change notification ignored - SRTLA not running");
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_srtla_NativeSrtlaJni_getConnectionCount(JNIEnv *env, jclass clazz) {
    if (!srtla_running) {
        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "getConnectionCount: SRTLA not running");
        return 0;
    }
    int count = srtla_get_connection_count();
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "getConnectionCount: %d", count);
    return count;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_srtla_NativeSrtlaJni_getActiveConnectionCount(JNIEnv *env, jclass clazz) {
    if (!srtla_running) {
        return 0;
    }
    int count = srtla_get_active_connection_count();
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "getActiveConnectionCount: %d", count);
    return count;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_srtla_NativeSrtlaJni_getTotalInFlightPackets(JNIEnv *env, jclass clazz) {
    if (!srtla_running) {
        return 0;
    }
    int count = srtla_get_total_in_flight_packets();
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "getTotalInFlightPackets: %d", count);
    return count;
}



extern "C" JNIEXPORT jstring JNICALL
Java_com_example_srtla_NativeSrtlaJni_getAllStats(JNIEnv *env, jclass clazz) {
    if (!srtla_running) {
        return env->NewStringUTF("No native SRTLA connections");
    }
    
    // Get summary stats
    int totalConnections = srtla_get_connection_count();
    int activeConnections = srtla_get_active_connection_count();
    int inFlightPackets = srtla_get_total_in_flight_packets();
    
    // Get detailed per-connection stats
    char detailsBuffer[1024];
    int detailsLen = srtla_get_connection_details(detailsBuffer, sizeof(detailsBuffer));
    
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "getAllStats: total=%d, active=%d, inflight=%d", 
                       totalConnections, activeConnections, inFlightPackets);
    
    // Format combined stats string
    char statsBuffer[1536];
    int len = snprintf(statsBuffer, sizeof(statsBuffer),
                       "📡 Native SRTLA Stats\n"
                       "Connections: %d total, %d active\n"
                       "Total in-flight: %d\n"
                       "%s",
                       totalConnections, activeConnections, 
                       inFlightPackets,
                       (detailsLen > 0) ? detailsBuffer : "No connection details available");
    
    return env->NewStringUTF(statsBuffer);
}

// Virtual IP JNI function
extern "C" JNIEXPORT void JNICALL
Java_com_example_srtla_NativeSrtlaJni_setNetworkSocket(JNIEnv *env, jclass clazz,
                                                      jstring virtual_ip, jstring real_ip,
                                                      jint network_type, jint socket_fd) {
    const char *virtual_ip_str = env->GetStringUTFChars(virtual_ip, nullptr);
    const char *real_ip_str = env->GetStringUTFChars(real_ip, nullptr);
    
    srtla_set_network_socket(virtual_ip_str, real_ip_str, network_type, socket_fd);
    
    env->ReleaseStringUTFChars(virtual_ip, virtual_ip_str);
    env->ReleaseStringUTFChars(real_ip, real_ip_str);
}

// Native UDP socket creation for NativeSrtlaService
extern "C" JNIEXPORT jint JNICALL
Java_com_example_srtla_NativeSrtlaService_createUdpSocketNative(JNIEnv *env, jobject thiz) {
    int sockfd = socket(AF_INET, SOCK_DGRAM | SOCK_NONBLOCK, 0);
    if (sockfd < 0) {
        __android_log_print(ANDROID_LOG_ERROR, "SRTLA-JNI", "Failed to create UDP socket: %s", strerror(errno));
        return -1;
    }
    
    // Set socket options
    int bufsize = 212992;
    if (setsockopt(sockfd, SOL_SOCKET, SO_SNDBUF, &bufsize, sizeof(bufsize)) != 0) {
        __android_log_print(ANDROID_LOG_WARN, "SRTLA-JNI", "Failed to set send buffer size: %s", strerror(errno));
    }
    if (setsockopt(sockfd, SOL_SOCKET, SO_RCVBUF, &bufsize, sizeof(bufsize)) != 0) {
        __android_log_print(ANDROID_LOG_WARN, "SRTLA-JNI", "Failed to set recv buffer size: %s", strerror(errno));
    }
    
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Created native UDP socket with FD: %d", sockfd);
    return sockfd;
}

// Native socket close for NativeSrtlaService
extern "C" JNIEXPORT void JNICALL
Java_com_example_srtla_NativeSrtlaService_closeSocketNative(JNIEnv *env, jobject thiz, jint sockfd) {
    if (sockfd >= 0) {
        if (close(sockfd) == 0) {
            __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Successfully closed socket FD: %d", sockfd);
        } else {
            __android_log_print(ANDROID_LOG_ERROR, "SRTLA-JNI", "Failed to close socket FD %d: %s", sockfd, strerror(errno));
        }
    } else {
        __android_log_print(ANDROID_LOG_WARN, "SRTLA-JNI", "Attempted to close invalid socket FD: %d", sockfd);
    }
}

// Per-connection bitrate JNI functions
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_example_srtla_NativeSrtlaJni_getConnectionBitrates(JNIEnv *env, jclass clazz) {
    const int MAX_CONNECTIONS = 10;
    double bitrates[MAX_CONNECTIONS];
    char connection_types[MAX_CONNECTIONS][16];
    char connection_ips[MAX_CONNECTIONS][64];
    int load_percentages[MAX_CONNECTIONS];
    
    int conn_count = srtla_get_connection_bitrates(bitrates, connection_types, 
                                                  connection_ips, load_percentages, 
                                                  MAX_CONNECTIONS);
    
    if (conn_count <= 0) {
        return env->NewDoubleArray(0);
    }
    
    jdoubleArray result = env->NewDoubleArray(conn_count);
    env->SetDoubleArrayRegion(result, 0, conn_count, bitrates);
    return result;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_srtla_NativeSrtlaJni_getConnectionTypes(JNIEnv *env, jclass clazz) {
    const int MAX_CONNECTIONS = 10;
    double bitrates[MAX_CONNECTIONS];
    char connection_types[MAX_CONNECTIONS][16];
    char connection_ips[MAX_CONNECTIONS][64];
    int load_percentages[MAX_CONNECTIONS];
    
    int conn_count = srtla_get_connection_bitrates(bitrates, connection_types, 
                                                  connection_ips, load_percentages, 
                                                  MAX_CONNECTIONS);
    
    if (conn_count <= 0) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }
    
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(conn_count, stringClass, nullptr);
    
    for (int i = 0; i < conn_count; i++) {
        env->SetObjectArrayElement(result, i, env->NewStringUTF(connection_types[i]));
    }
    
    return result;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_example_srtla_NativeSrtlaJni_getConnectionIPs(JNIEnv *env, jclass clazz) {
    const int MAX_CONNECTIONS = 10;
    double bitrates[MAX_CONNECTIONS];
    char connection_types[MAX_CONNECTIONS][16];
    char connection_ips[MAX_CONNECTIONS][64];
    int load_percentages[MAX_CONNECTIONS];
    
    int conn_count = srtla_get_connection_bitrates(bitrates, connection_types, 
                                                  connection_ips, load_percentages, 
                                                  MAX_CONNECTIONS);
    
    if (conn_count <= 0) {
        return env->NewObjectArray(0, env->FindClass("java/lang/String"), nullptr);
    }
    
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray result = env->NewObjectArray(conn_count, stringClass, nullptr);
    
    for (int i = 0; i < conn_count; i++) {
        env->SetObjectArrayElement(result, i, env->NewStringUTF(connection_ips[i]));
    }
    
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_srtla_NativeSrtlaJni_getConnectionLoadPercentages(JNIEnv *env, jclass clazz) {
    const int MAX_CONNECTIONS = 10;
    double bitrates[MAX_CONNECTIONS];
    char connection_types[MAX_CONNECTIONS][16];
    char connection_ips[MAX_CONNECTIONS][64];
    int load_percentages[MAX_CONNECTIONS];
    
    int conn_count = srtla_get_connection_bitrates(bitrates, connection_types, 
                                                  connection_ips, load_percentages, 
                                                  MAX_CONNECTIONS);
    
    if (conn_count <= 0) {
        return env->NewIntArray(0);
    }
    
    jintArray result = env->NewIntArray(conn_count);
    env->SetIntArrayRegion(result, 0, conn_count, load_percentages);
    return result;
}

// New JNI functions for comprehensive window data
extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_srtla_NativeSrtlaJni_getConnectionWindowSizes(JNIEnv *env, jclass clazz) {
    const int MAX_CONNECTIONS = 10;
    double bitrates[MAX_CONNECTIONS];
    char connection_types[MAX_CONNECTIONS][16];
    char connection_ips[MAX_CONNECTIONS][64];
    int load_percentages[MAX_CONNECTIONS];
    int window_sizes[MAX_CONNECTIONS];
    int inflight_packets[MAX_CONNECTIONS];
    
    int conn_count = srtla_get_connection_window_data(bitrates, connection_types, 
                                                     connection_ips, load_percentages,
                                                     window_sizes, inflight_packets,
                                                     MAX_CONNECTIONS);
    
    if (conn_count <= 0) {
        return env->NewIntArray(0);
    }
    
    jintArray result = env->NewIntArray(conn_count);
    env->SetIntArrayRegion(result, 0, conn_count, window_sizes);
    return result;
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_example_srtla_NativeSrtlaJni_getConnectionInFlightPackets(JNIEnv *env, jclass clazz) {
    const int MAX_CONNECTIONS = 10;
    double bitrates[MAX_CONNECTIONS];
    char connection_types[MAX_CONNECTIONS][16];
    char connection_ips[MAX_CONNECTIONS][64];
    int load_percentages[MAX_CONNECTIONS];
    int window_sizes[MAX_CONNECTIONS];
    int inflight_packets[MAX_CONNECTIONS];
    
    int conn_count = srtla_get_connection_window_data(bitrates, connection_types, 
                                                     connection_ips, load_percentages,
                                                     window_sizes, inflight_packets,
                                                     MAX_CONNECTIONS);
    
    if (conn_count <= 0) {
        return env->NewIntArray(0);
    }
    
    jintArray result = env->NewIntArray(conn_count);
    env->SetIntArrayRegion(result, 0, conn_count, inflight_packets);
    return result;
}