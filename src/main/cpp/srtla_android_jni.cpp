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
#include <atomic>
#include <chrono>  // Add this include for std::chrono

// Forward declarations for all SRTLA C functions we call
extern "C" {
    int srtla_start_android(const char* listen_port, const char* srtla_host, 
                           const char* srtla_port, const char* ips_file);
    void srtla_stop_android();
    void schedule_update_conns(int signal);
    
    // Stats functions
    int srtla_get_connection_count();
    int srtla_get_active_connection_count();
    int srtla_get_total_in_flight_packets();
    int srtla_get_total_window_size();
    int srtla_get_connection_details(char* buffer, int buffer_size);
    int srtla_is_reconnecting();
    
    // Virtual IP functions
    void srtla_set_network_socket(const char* virtual_ip, const char* real_ip, 
                                  int network_type, int socket_fd);
    
    // Per-connection bitrate functions
    int srtla_get_connection_bitrates(double* bitrates_mbps, char connection_types[][16], 
                                      char connection_ips[][64], int* load_percentages,
                                      int max_connections);
    
    // Comprehensive connection window data function
    int srtla_get_connection_window_data(double* bitrates_mbps, char connection_types[][16], 
                                        char connection_ips[][64], int* load_percentages,
                                        int* window_sizes, int* inflight_packets,
                                        int max_connections);
    
    void srtla_on_connection_established();
    void srtla_notify_network_change();
    void srtla_clear_all_sockets();
}

static pthread_t srtla_thread;
static std::atomic<bool> srtla_running(false);
static std::atomic<bool> srtla_should_stop(false);
static std::atomic<bool> srtla_retry_enabled(false);
static std::atomic<int> srtla_retry_count(0);
static std::atomic<bool> srtla_connected(false);
static std::atomic<bool> srtla_has_ever_connected(false);

struct SrtlaParams {
    char listen_port[16];
    char srtla_host[256];
    char srtla_port[16];
    char ips_file[512];
};

// Thread function to run SRTLA with retry logic
static void* srtla_thread_func(void* args) {
    SrtlaParams* params = (SrtlaParams*)args;
    const int RETRY_DELAY_MS = 3000;  // 3 seconds between retries
    const int INITIAL_CONNECTION_TIMEOUT_MS = 10000;  // 10 seconds for initial connection
    
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Starting SRTLA thread with params: host=%s port=%s", 
                       params->srtla_host, params->srtla_port);
    
    // Reset ALL state at thread start to ensure clean slate
    srtla_retry_count.store(0);
    srtla_connected.store(false);
    srtla_has_ever_connected.store(false);
    
    // Track when we started for timeout detection (local to this thread)
    auto thread_start_time = std::chrono::steady_clock::now();
    
    while (!srtla_should_stop.load()) {
        // Log current attempt
        if (srtla_has_ever_connected.load()) {
            // We had a connection before, this is a reconnection attempt
            __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Reconnection attempt %d after disconnect", 
                              srtla_retry_count.load() + 1);
        } else if (srtla_retry_count.load() > 0) {
            // Never connected but retrying
            __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Initial connection retry attempt %d", 
                              srtla_retry_count.load());
        } else {
            // Very first attempt
            __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Initial connection attempt");
        }
        
        // Call the Android-patched SRTLA function
        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Calling srtla_start_android()...");
        int result = srtla_start_android(params->listen_port, params->srtla_host,
                                       params->srtla_port, params->ips_file);
        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "srtla_start_android() returned: %d", result);
        
        // Check if we should stop
        if (srtla_should_stop.load()) {
            __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "SRTLA stopped by user");
            break;
        }
        
        // Mark as disconnected since srtla_start_android returned
        srtla_connected.store(false);
        
        // Determine if we should retry based on the result and connection history
        bool shouldRetry = false;
        bool shouldIncrementRetryCount = false;
        const char* failureReason = "";
        
        if (srtla_has_ever_connected.load()) {
            // We had a successful connection before, always retry and count it
            shouldRetry = true;
            shouldIncrementRetryCount = true;
            failureReason = "connection lost after being established";
        } else {
            // Never connected successfully yet
            auto now = std::chrono::steady_clock::now();
            auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - thread_start_time).count();
            
            if (elapsed > INITIAL_CONNECTION_TIMEOUT_MS) {
                // Been trying for a while, now count retries
                shouldRetry = true;
                shouldIncrementRetryCount = true;
                failureReason = "initial connection timeout reached, continuing retries";
            } else {
                // Still within initial timeout window, retry but don't count it yet
                shouldRetry = true;
                shouldIncrementRetryCount = false;
                failureReason = "initial connection failed, retrying within timeout window";
            }
        }
        
        if (!shouldRetry) {
            __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Not retrying, exiting thread");
            break;
        }
        
        // Only increment retry count after timeout period or if reconnecting
        if (shouldIncrementRetryCount) {
            srtla_retry_count.fetch_add(1);
        }
        
        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", 
            "Will retry in %dms (attempt %d) - reason: %s", 
            RETRY_DELAY_MS, srtla_retry_count.load(), failureReason);
        
        // Sleep with interruption check
        for (int i = 0; i < RETRY_DELAY_MS / 100 && !srtla_should_stop.load(); i++) {
            usleep(100 * 1000);
        }
    }
    
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "SRTLA thread exiting, cleaning up");
    delete params;
    
    // Reset state when thread exits
    srtla_running.store(false);
    srtla_retry_count.store(0);
    srtla_connected.store(false);
    srtla_has_ever_connected.store(false);
    
    return nullptr;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_srtla_NativeSrtlaJni_startSrtlaNative(JNIEnv *env, jclass clazz,
                                                     jstring listen_port, jstring srtla_host,
                                                     jstring srtla_port, jstring ips_file) {
    if (srtla_running.load()) {
        __android_log_print(ANDROID_LOG_WARN, "SRTLA-JNI", "SRTLA already running, ignoring start request");
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
    
    // Reset ALL state before starting
    srtla_should_stop.store(false);
    srtla_retry_count.store(0);
    srtla_connected.store(false);
    srtla_has_ever_connected.store(false);
    srtla_running.store(true);
    
    // Start SRTLA in background thread with retry logic
    if (pthread_create(&srtla_thread, nullptr, srtla_thread_func, params) != 0) {
        srtla_running.store(false);
        delete params;
        __android_log_print(ANDROID_LOG_ERROR, "SRTLA-JNI", "Failed to create SRTLA thread");
        return -1;
    }
    
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "SRTLA thread started successfully");
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_srtla_NativeSrtlaJni_stopSrtlaNative(JNIEnv *env, jclass clazz) {
    if (!srtla_running.load()) {
        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "SRTLA not running, nothing to stop");
        return 0;
    }
    
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Stopping SRTLA process...");
    
    // Signal the SRTLA process to stop
    srtla_should_stop.store(true);
    srtla_stop_android();
    
    // IMPORTANT: Clear any virtual IP socket mappings in the native SRTLA code
    // This ensures we don't try to use stale FDs after restart
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Clearing virtual IP socket mappings");
    srtla_clear_all_sockets();
    
    // Wait for thread to actually exit with a reasonable timeout
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Waiting for thread to exit...");
    
    // Use a loop to check if thread is still running with timeout
    int wait_count = 0;
    const int max_wait = 50; // 5 seconds (50 * 100ms)
    
    while (srtla_running.load() && wait_count < max_wait) {
        usleep(100000); // 100ms
        wait_count++;
    }
    
    if (wait_count >= max_wait) {
        __android_log_print(ANDROID_LOG_WARN, "SRTLA-JNI", "Thread did not exit in time, detaching thread");
        pthread_detach(srtla_thread);
    } else {
        // Thread exited, join it
        void* thread_result;
        pthread_join(srtla_thread, &thread_result);
        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Thread joined successfully after %d ms", wait_count * 100);
    }
    
    // Force reset ALL state after stopping - ensure clean slate for next start
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Force resetting all state after stop");
    srtla_running.store(false);
    srtla_should_stop.store(false);
    srtla_retry_count.store(0);
    srtla_connected.store(false);
    srtla_has_ever_connected.store(false);
    srtla_retry_enabled.store(false);
    
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "SRTLA fully stopped and state completely reset");
    
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_srtla_NativeSrtlaJni_isRunningSrtlaNative(JNIEnv *env, jclass clazz) {
    return srtla_running.load();
}

// New function to get retry status
extern "C" JNIEXPORT jint JNICALL
Java_com_example_srtla_NativeSrtlaJni_getRetryCount(JNIEnv *env, jclass clazz) {
    return srtla_retry_count.load();
}

// Add a callback that SRTLA can call when connection is established
extern "C" void srtla_on_connection_established() {
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Connection established callback from SRTLA");
    
    bool wasConnected = srtla_connected.load();
    bool hadEverConnected = srtla_has_ever_connected.load();
    
    srtla_connected.store(true);
    srtla_has_ever_connected.store(true);
    
    // Only reset retry count if this is a new connection or reconnection
    if (!wasConnected) {
        srtla_retry_count.store(0);
        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Connection established, retry count reset");
    }
    
    if (!hadEverConnected) {
        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "First successful connection achieved");
    }
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
    if (!srtla_running.load()) {
        return env->NewStringUTF("");
    }
    
    // Get connection counts
    int totalConnections = srtla_get_connection_count();
    int activeConnections = srtla_get_active_connection_count();
    int retryCount = srtla_retry_count.load();
    bool isConnected = srtla_connected.load();
    bool hasEverConnected = srtla_has_ever_connected.load();
    bool isReconnecting = srtla_is_reconnecting();
    
    __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", 
        "getAllStats: total=%d, active=%d, retry_count=%d, connected=%d, ever_connected=%d, reconnecting=%d", 
        totalConnections, activeConnections, retryCount, isConnected, hasEverConnected, isReconnecting);
    
    // If we have active connections but not marked as connected, update state
    if (!isConnected && activeConnections > 0) {
        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Detected active connections, marking as connected");
        srtla_connected.store(true);
        srtla_has_ever_connected.store(true);
        srtla_retry_count.store(0);
        isConnected = true;  // Update local variable
        retryCount = 0;      // Update local variable
    }
    
    // If no active connections but we were connected, mark as disconnected
    if (isConnected && activeConnections == 0) {
        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Lost all connections, marking as disconnected");
        srtla_connected.store(false);
        isConnected = false;
    }
    
    // Determine what to show based on state
    if (!hasEverConnected && retryCount == 0) {
        // Initial connection attempt, show "Connecting..."
        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Initial connection attempt in progress");
        return env->NewStringUTF("");
    }
    
    if (isReconnecting || (!isConnected && hasEverConnected)) {
        // We're reconnecting (lost connection and trying to restore)
        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "Reconnecting after connection loss");
        return env->NewStringUTF("");
    }
    
    if (!isConnected && retryCount > 0) {
        // We're in retry mode (either never connected or lost connection)
        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "In retry mode (attempt %d)", retryCount);
        return env->NewStringUTF("");
    }
    
    // Get detailed per-connection stats
    char detailsBuffer[1024];
    int detailsLen = srtla_get_connection_details(detailsBuffer, sizeof(detailsBuffer));
    
    // If we have no stats data yet
    if (detailsLen <= 0 || strlen(detailsBuffer) == 0) {
        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", "No stats data available yet");
        return env->NewStringUTF("");
    }
    
    return env->NewStringUTF(detailsBuffer);
}

// Update isRetrying to check both retry count and reconnecting flag
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_srtla_NativeSrtlaJni_isRetrying(JNIEnv *env, jclass clazz) {
    if (!srtla_running.load()) {
        return JNI_FALSE;
    }
    
    int retryCount = srtla_retry_count.load();
    bool isConnected = srtla_connected.load();
    bool isReconnecting = srtla_is_reconnecting();
    int activeCount = srtla_get_active_connection_count();
    
    // We're retrying/reconnecting if:
    // 1. retry count > 0 AND not connected (initial connection attempts)
    // 2. is_reconnecting flag is set (lost connection and attempting to reconnect)
    // 3. not connected but has ever connected (detected disconnection)
    bool hasEverConnected = srtla_has_ever_connected.load();
    bool isRetrying = ((retryCount > 0) && !isConnected) || 
                      isReconnecting || 
                      (!isConnected && hasEverConnected && activeCount == 0);
    
    if (isRetrying) {
        __android_log_print(ANDROID_LOG_INFO, "SRTLA-JNI", 
                          "isRetrying: true (retry_count=%d, connected=%d, reconnecting=%d, active=%d)", 
                          retryCount, isConnected, isReconnecting, activeCount);
    }
    
    return isRetrying ? JNI_TRUE : JNI_FALSE;
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

// Add a new JNI method to check if connected
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_srtla_NativeSrtlaJni_isConnected(JNIEnv *env, jclass clazz) {
    return srtla_connected.load();
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

extern "C" JNIEXPORT jbooleanArray JNICALL
Java_com_example_srtla_NativeSrtlaJni_getConnectionActiveStatus(JNIEnv *env, jclass clazz) {
    const int MAX_CONNECTIONS = 10;
    double bitrates[MAX_CONNECTIONS];
    char connection_types[MAX_CONNECTIONS][16];
    char connection_ips[MAX_CONNECTIONS][64];
    int load_percentages[MAX_CONNECTIONS];
    int window_sizes[MAX_CONNECTIONS];
    int inflight_packets[MAX_CONNECTIONS];
    int active_status[MAX_CONNECTIONS];
    
    int conn_count = srtla_get_connection_window_data(bitrates, connection_types, 
                                                     connection_ips, load_percentages,
                                                     window_sizes, inflight_packets,
                                                     MAX_CONNECTIONS);
    
    if (conn_count <= 0) {
        return env->NewBooleanArray(0);
    }
    
    // Determine active status based on available data
    jbooleanArray result = env->NewBooleanArray(conn_count);
    jboolean* statusArray = new jboolean[conn_count];
    for (int i = 0; i < conn_count; i++) {
        // Connection is active if it has bitrate > 0.1 Mbps OR in-flight packets > 0
        bool isActive = (bitrates[i] > 0.1) || (inflight_packets[i] > 0);
        statusArray[i] = isActive ? JNI_TRUE : JNI_FALSE;
    }
    env->SetBooleanArrayRegion(result, 0, conn_count, statusArray);
    delete[] statusArray;
    return result;
}