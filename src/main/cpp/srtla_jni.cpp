#include <jni.h>
#include <string>
#include <android/log.h>
#include <android/multinetwork.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <cstring>
#include <netdb.h>
#include "include/srtla_core.h"
#include "include/srtla_connection.h"

#define LOG_TAG "SrtlaNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global JNI variables - g_java_vm is accessible from other files
JavaVM* g_java_vm = nullptr;

namespace {
    srtla::SrtlaCore* g_srtla_core = nullptr;
    jobject g_callback_obj = nullptr;
    jmethodID g_update_conn_method = nullptr;
    jmethodID g_get_conns_string_method = nullptr;
    jmethodID g_get_last_update_method = nullptr;
    
    // Store initialization parameters for startBonding
    int g_local_port = 0;
    std::string g_server_host;
    std::string g_server_port;
}

/**
 * Refresh all connections - reset SRTLA state and re-register all connections
 * This is useful when networks change or connections get into a bad state
 * Signature: void refreshConnections()
 */
JNIEXPORT void JNICALL
Java_com_example_srtla_NativeSrtlaService_refreshConnections(
    JNIEnv* env,
    jobject thiz) {
    
    (void)env; (void)thiz; // Unused parameters
    
    if (!g_srtla_core) {
        LOGE("SRTLA core not initialized - cannot refresh connections");
        return;
    }
    
    LOGI("Refreshing all SRTLA connections - resetting state and re-registering");
    
    // Reset the SRTLA core state - this will clear all connections and reset registration
    g_srtla_core->refresh_all_connections();
}

/**
 * Callback from native to Java to update connection statistics
 */
void native_stats_callback(const char* ip, int* stats, int stats_count) {
    if (!g_java_vm || !g_callback_obj || !g_update_conn_method) {
        return;
    }
    
    JNIEnv* env = nullptr;
    bool attached = false;
    
    // Get JNI environment
    if (g_java_vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_java_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            attached = true;
        } else {
            LOGE("Failed to attach thread for callback");
            return;
        }
    }
    
    // Call Java method: void updateConn(String connId, int window, int inflight, int nak, int score, 
    //                                  int weight, long bytesSent, long packetsSent,
    //                                  long bytesReceived, long packetsReceived, int rtt, int isActive)
    jstring jip = env->NewStringUTF(ip);
    
    if (stats_count >= 11) {
        env->CallVoidMethod(g_callback_obj, g_update_conn_method,
            jip,
            stats[0], stats[1], stats[2], stats[3],
            stats[4], (jlong)stats[5], (jlong)stats[6], (jlong)stats[7],
            (jlong)stats[8], stats[9], stats[10]);
    }
    
    env->DeleteLocalRef(jip);
    
    if (attached) {
        g_java_vm->DetachCurrentThread();
    }
}

/**
 * Native callback to get connection string from Java
 * This allows native code to query Java for current connection state
 */
std::string native_get_conns_string() {
    LOGI("native_get_conns_string() called - checking Java callbacks");
    
    if (!g_java_vm || !g_callback_obj || !g_get_conns_string_method) {
        LOGE("Cannot get conns string - Java callbacks not initialized (vm=%p, obj=%p, method=%p)", 
             g_java_vm, g_callback_obj, g_get_conns_string_method);
        return "";
    }
    
    JNIEnv* env = nullptr;
    bool attached = false;
    
    // Get JNI environment
    if (g_java_vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_java_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            attached = true;
            LOGI("Attached thread for getConnsString callback");
        } else {
            LOGE("Failed to attach thread for getConnsString callback");
            return "";
        }
    }
    
    // Call Java method: String getConnsString()
    jstring result = (jstring)env->CallObjectMethod(g_callback_obj, g_get_conns_string_method);
    std::string conns_string;
    
    if (result) {
        const char* chars = env->GetStringUTFChars(result, nullptr);
        conns_string = std::string(chars);
        env->ReleaseStringUTFChars(result, chars);
        env->DeleteLocalRef(result);
        LOGI("Native got conns string: '%s'", conns_string.c_str());
    } else {
        LOGE("Java getConnsString returned null");
    }
    
    if (attached) {
        g_java_vm->DetachCurrentThread();
    }
    
    return conns_string;
}

/**
 * Native callback to get connection update index from Java
 */
int native_get_last_update() {
    if (!g_java_vm || !g_callback_obj || !g_get_last_update_method) {
        return 0;
    }
    
    JNIEnv* env = nullptr;
    bool attached = false;
    
    // Get JNI environment
    if (g_java_vm->GetEnv((void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        if (g_java_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            attached = true;
        } else {
            return 0;
        }
    }
    
    // Call Java method: int getLastUpdate()
    int result = env->CallIntMethod(g_callback_obj, g_get_last_update_method);
    
    if (attached) {
        g_java_vm->DetachCurrentThread();
    }
    
    return result;
}

extern "C" {

/**
 * JNI_OnLoad - Called when library is loaded
 */
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    (void)reserved; // Unused parameter
    g_java_vm = vm;
    LOGI("SRTLA Native library loaded");
    return JNI_VERSION_1_6;
}

/**
 * Initialize SRTLA core (setup only, for connection additions)
 * Signature: int initializeCore(int localPort, String serverHost, String serverPort)
 */
JNIEXPORT jint JNICALL
Java_com_example_srtla_NativeSrtlaService_initializeCore(
    JNIEnv* env,
    jobject thiz,
    jint local_port,
    jstring server_host,
    jstring server_port) {
    
    LOGI("Initializing SRTLA core for port %d", local_port);
    
    // Save callback object for statistics updates (only if not already set)
    if (!g_callback_obj) {
        g_callback_obj = env->NewGlobalRef(thiz);
        
        // Get method IDs for Java callbacks
        jclass clazz = env->GetObjectClass(thiz);
        
        // updateConn method for statistics updates
        g_update_conn_method = env->GetMethodID(clazz, "updateConn",
            "(Ljava/lang/String;IIIIIJJJJII)V");
        if (!g_update_conn_method) {
            LOGE("Failed to find updateConn method");
            return -1;
        }
        
        // getConnsString method for native to query Java connection state
        g_get_conns_string_method = env->GetMethodID(clazz, "getConnsString",
            "()Ljava/lang/String;");
        if (!g_get_conns_string_method) {
            LOGE("Failed to find getConnsString method");
            return -1;
        }
        
        // getLastUpdate method for native to detect connection changes
        g_get_last_update_method = env->GetMethodID(clazz, "getLastUpdate",
            "()I");
        if (!g_get_last_update_method) {
            LOGE("Failed to find getLastUpdate method");
            return -1;
        }
        
        LOGI("All Java callback methods initialized successfully");
    }
    
    // Convert Java strings to C++
    const char* host_cstr = env->GetStringUTFChars(server_host, nullptr);
    const char* port_cstr = env->GetStringUTFChars(server_port, nullptr);
    
    g_server_host = std::string(host_cstr);
    g_server_port = std::string(port_cstr);
    g_local_port = local_port;
    
    env->ReleaseStringUTFChars(server_host, host_cstr);
    env->ReleaseStringUTFChars(server_port, port_cstr);
    
    // Create SRTLA core (setup only, don't start server yet)
    if (g_srtla_core) {
        delete g_srtla_core;
    }
    
    g_srtla_core = new srtla::SrtlaCore();
    g_srtla_core->set_stats_callback(native_stats_callback);
    g_srtla_core->set_java_callbacks(native_get_conns_string, native_get_last_update);
    
    LOGI("SRTLA core initialized with Java callbacks successfully");
    return 0;
}

/**
 * Start SRTLA service (blocking call that runs the server)
 * Signature: int initializeBonding(int localPort, String serverHost, String serverPort)
 */
JNIEXPORT jint JNICALL
Java_com_example_srtla_NativeSrtlaService_initializeBonding(
    JNIEnv* env,
    jobject thiz,
    jint local_port,
    jstring server_host,
    jstring server_port) {
    
    (void)local_port; (void)server_host; (void)server_port; // Use stored values
    
    if (!g_srtla_core) {
        LOGE("SRTLA core not initialized - call initializeCore first");
        return -1;
    }
    
    LOGI("Starting SRTLA bonding on port %d", g_local_port);
    
    // Register detected networks before starting the event loop
    LOGI("Calling back to Java to register detected networks...");
    jclass clazz = env->GetObjectClass(thiz);
    jmethodID register_method = env->GetMethodID(clazz, "registerDetectedNetworks", "()V");
    if (register_method) {
        env->CallVoidMethod(thiz, register_method);
        LOGI("Network registration callback completed");
    } else {
        LOGE("Failed to find registerDetectedNetworks method");
    }
    
    // This is a blocking call that runs the SRTLA server loop
    int result = g_srtla_core->start(g_local_port, g_server_host, g_server_port);
    
    if (result != 0) {
        LOGE("SRTLA service failed: %d", result);
    } else {
        LOGI("SRTLA service ended normally");
    }
    
    return result;
}

/**
 * Stop SRTLA service
 */
JNIEXPORT void JNICALL
Java_com_example_srtla_NativeSrtlaService_shutdownBonding(JNIEnv* env, jobject thiz) {
    (void)env; (void)thiz; // Unused parameters
    LOGI("Stopping SRTLA native service");
    
    if (g_srtla_core) {
        g_srtla_core->stop();
        delete g_srtla_core;
        g_srtla_core = nullptr;
    }
    
    if (g_callback_obj) {
        env->DeleteGlobalRef(g_callback_obj);
        g_callback_obj = nullptr;
    }
    
    g_update_conn_method = nullptr;
}

/**
 * Add a connection (network binding done in Java)
 * Signature: boolean addConnection(int fd, String virtualIp, int weight, String type)
 */
JNIEXPORT jboolean JNICALL
Java_com_example_srtla_NativeSrtlaService_addConnection(
    JNIEnv* env,
    jobject thiz,
    jint fd,
    jstring virtual_ip,
    jint weight,
    jstring type) {
    
    (void)thiz; // Unused parameter
    
    if (!g_srtla_core) {
        LOGE("SRTLA core not initialized");
        return JNI_FALSE;
    }
    
    const char* ip_cstr = env->GetStringUTFChars(virtual_ip, nullptr);
    const char* type_cstr = env->GetStringUTFChars(type, nullptr);
    
    std::string ip(ip_cstr);
    std::string conn_type(type_cstr);
    
    env->ReleaseStringUTFChars(virtual_ip, ip_cstr);
    env->ReleaseStringUTFChars(type, type_cstr);
    
    LOGI("Adding connection: fd=%d, ip=%s, weight=%d, type=%s", 
         fd, ip.c_str(), weight, conn_type.c_str());
    
    // Network binding already done in Java - just add the connection
    bool result = g_srtla_core->add_connection(fd, ip, weight, conn_type);
    return result ? JNI_TRUE : JNI_FALSE;
}

/**
 * Add a connection using network handle for native binding
 * Signature: boolean addConnectionWithNetworkHandle(long networkHandle, String virtualIp, int weight, String type, String serverHost, int serverPort)
 */
JNIEXPORT jboolean JNICALL
Java_com_example_srtla_NativeSrtlaService_addConnectionWithNetworkHandle(
    JNIEnv* env,
    jobject thiz,
    jlong network_handle,
    jstring virtual_ip,
    jint weight,
    jstring type,
    jstring server_host,
    jint server_port) {
    
    (void)thiz; // Unused parameter
    
    if (!g_srtla_core) {
        LOGE("SRTLA core not initialized");
        return JNI_FALSE;
    }
    
    // Extract string parameters
    const char* ip_cstr = env->GetStringUTFChars(virtual_ip, nullptr);
    const char* type_cstr = env->GetStringUTFChars(type, nullptr);
    const char* host_cstr = env->GetStringUTFChars(server_host, nullptr);
    
    std::string ip(ip_cstr);
    std::string conn_type(type_cstr);
    std::string host(host_cstr);
    
    env->ReleaseStringUTFChars(virtual_ip, ip_cstr);
    env->ReleaseStringUTFChars(type, type_cstr);
    env->ReleaseStringUTFChars(server_host, host_cstr);
    
    LOGI("Adding connection with network handle: handle=%ld, ip=%s, weight=%d, type=%s, server=%s:%d", 
         network_handle, ip.c_str(), weight, conn_type.c_str(), host.c_str(), server_port);
    
    // Create UDP socket
    int fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (fd < 0) {
        LOGE("Failed to create socket: %s", strerror(errno));
        return JNI_FALSE;
    }
    
    // Bind socket to specific network using android_setsocknetwork
    net_handle_t net_handle = static_cast<net_handle_t>(network_handle);
    int bind_result = android_setsocknetwork(net_handle, fd);
    if (bind_result != 0) {
        LOGE("Failed to bind socket to network handle %ld: %s", network_handle, strerror(errno));
        close(fd);
        return JNI_FALSE;
    }
    
    LOGI("Successfully bound socket fd=%d to network handle %ld", fd, network_handle);
    
    // Set socket options (like original SRTLA)
    int bufsize = 8 * 1024 * 1024;  // 8MB send buffer
    if (setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &bufsize, sizeof(bufsize)) < 0) {
        LOGE("Failed to set send buffer size to %d on fd=%d: %s", bufsize, fd, strerror(errno));
    }
    
    // Set non-blocking mode
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags >= 0) {
        fcntl(fd, F_SETFL, flags | O_NONBLOCK);
    }
    
    // Connect to SRTLA server
    struct sockaddr_in server_addr;
    memset(&server_addr, 0, sizeof(server_addr));
    server_addr.sin_family = AF_INET;
    server_addr.sin_port = htons(server_port);
    
    // Resolve hostname using getaddrinfo (supports both hostnames and IP addresses)
    struct addrinfo hints, *result = nullptr;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_DGRAM;
    
    std::string port_str = std::to_string(server_port);
    int ret = getaddrinfo(host.c_str(), port_str.c_str(), &hints, &result);
    if (ret != 0 || result == nullptr) {
        LOGE("Failed to resolve server address %s: %s", host.c_str(), gai_strerror(ret));
        close(fd);
        return JNI_FALSE;
    }
    
    // Copy resolved address
    memcpy(&server_addr, result->ai_addr, sizeof(server_addr));
    freeaddrinfo(result);
    
    char ip_str[INET_ADDRSTRLEN];
    inet_ntop(AF_INET, &server_addr.sin_addr, ip_str, sizeof(ip_str));
    LOGI("Resolved %s to %s:%d", host.c_str(), ip_str, server_port);
    
    // Connect the socket to the SRTLA server
    if (connect(fd, (struct sockaddr*)&server_addr, sizeof(server_addr)) < 0) {
        LOGE("Failed to connect socket to %s:%d: %s", host.c_str(), server_port, strerror(errno));
        close(fd);
        return JNI_FALSE;
    }
    
    LOGI("Successfully connected socket fd=%d to SRTLA server %s:%d", fd, host.c_str(), server_port);
    
    // Add connection to SRTLA core
    bool add_result = g_srtla_core->add_connection(fd, ip, weight, conn_type);
    
    if (!add_result) {
        LOGE("Failed to add connection to SRTLA core");
        close(fd);
        return JNI_FALSE;
    }
    
    LOGI("Successfully added connection: fd=%d, ip=%s, type=%s, weight=%d, networkHandle=%ld", 
         fd, ip.c_str(), conn_type.c_str(), weight, network_handle);
    
    return JNI_TRUE;
}

/**
 * Add a connection with automatic virtual IP allocation
 * Signature: String addConnectionAutoIP(int fd, int weight, String type, Network androidNetwork)
 */
JNIEXPORT jstring JNICALL
Java_com_example_srtla_NativeSrtlaService_addConnectionAutoIP(
    JNIEnv* env,
    jobject thiz,
    jint fd,
    jint weight,
    jstring type,
    jobject android_network) {
    
    (void)thiz; (void)android_network; // Unused parameters
    
    if (!g_srtla_core) {
        LOGE("SRTLA core not initialized");
        return nullptr;
    }
    
    const char* type_cstr = env->GetStringUTFChars(type, nullptr);
    std::string conn_type(type_cstr);
    env->ReleaseStringUTFChars(type, type_cstr);
    
    LOGI("Adding connection with auto-IP allocation: fd=%d, weight=%d, type=%s", 
         fd, weight, conn_type.c_str());
    
    // Try to add connection with automatic IP allocation
    std::string allocated_ip = g_srtla_core->add_connection_auto_ip(fd, weight, conn_type);
    
    if (!allocated_ip.empty()) {
        LOGI("Successfully allocated virtual IP %s for connection", allocated_ip.c_str());
        return env->NewStringUTF(allocated_ip.c_str());
    } else {
        LOGE("Failed to add connection with auto-IP allocation");
        return nullptr;
    }
}

/**
 * Remove a connection
 */
JNIEXPORT jboolean JNICALL
Java_com_example_srtla_NativeSrtlaService_removeConnection(
    JNIEnv* env,
    jobject thiz,
    jstring virtual_ip) {
    
    (void)thiz; // Unused parameter
    
    if (!g_srtla_core) {
        return JNI_FALSE;
    }
    
    const char* ip_cstr = env->GetStringUTFChars(virtual_ip, nullptr);
    std::string ip(ip_cstr);
    env->ReleaseStringUTFChars(virtual_ip, ip_cstr);
    
    bool result = g_srtla_core->remove_connection(ip);
    return result ? JNI_TRUE : JNI_FALSE;
}

/**
 * Update connection weight
 */
JNIEXPORT void JNICALL
Java_com_example_srtla_NativeSrtlaService_updateConnectionWeight(
    JNIEnv* env,
    jobject thiz,
    jstring virtual_ip,
    jint weight) {
    
    (void)thiz; // Unused parameter
    
    if (!g_srtla_core) {
        return;
    }
    
    const char* ip_cstr = env->GetStringUTFChars(virtual_ip, nullptr);
    std::string ip(ip_cstr);
    env->ReleaseStringUTFChars(virtual_ip, ip_cstr);
    
    g_srtla_core->update_connection_weight(ip, weight);
}

/**
 * Allocate a virtual IP
 * Signature: String allocateVirtualIP()
 */
JNIEXPORT jstring JNICALL
Java_com_example_srtla_NativeSrtlaService_allocateVirtualIP(
    JNIEnv* env,
    jobject thiz) {
    
    (void)thiz; // Unused parameter
    if (!g_srtla_core) {
        LOGE("SRTLA core not initialized");
        return nullptr;
    }
    
    std::string virtual_ip = g_srtla_core->allocate_virtual_ip();
    if (!virtual_ip.empty()) {
        LOGI("Allocated virtual IP: %s", virtual_ip.c_str());
        return env->NewStringUTF(virtual_ip.c_str());
    } else {
        LOGE("Failed to allocate virtual IP - pool exhausted");
        return nullptr;
    }
}

/**
 * Release a virtual IP back to the pool
 * Signature: void releaseVirtualIP(String virtualIP)
 */
JNIEXPORT void JNICALL
Java_com_example_srtla_NativeSrtlaService_releaseVirtualIP(
    JNIEnv* env,
    jobject thiz,
    jstring virtual_ip) {

    (void)thiz; // Unused parameter
    
    if (!g_srtla_core) {
        return;
    }
    
    const char* ip_cstr = env->GetStringUTFChars(virtual_ip, nullptr);
    std::string ip(ip_cstr);
    env->ReleaseStringUTFChars(virtual_ip, ip_cstr);
    
    LOGI("Releasing virtual IP: %s", ip.c_str());
    g_srtla_core->release_virtual_ip(ip);
}

/**
 * Force refresh all connections - emergency recovery method
 * Signature: void forceRefreshConnections()
 */
JNIEXPORT void JNICALL
Java_com_example_srtla_NativeSrtlaService_forceRefreshConnections(
    JNIEnv* env,
    jobject thiz) {
    
    (void)env; (void)thiz; // Unused parameters
    
    if (!g_srtla_core) {
        LOGE("SRTLA core not initialized");
        return;
    }
    
    LOGI("*** EMERGENCY: Force refreshing all connections from Java request ***");
    g_srtla_core->refresh_all_connections();
}

/**
 * Get count of connections in CONNECTED state
 * Signature: int getConnectedConnectionCount()
 */
JNIEXPORT jint JNICALL
Java_com_example_srtla_NativeSrtlaService_getConnectedConnectionCount(
    JNIEnv* env,
    jobject thiz) {
    
    (void)env; (void)thiz; // Unused parameters
    
    if (!g_srtla_core) {
        LOGE("SRTLA core not initialized");
        return 0;
    }
    
    int connected_count = g_srtla_core->get_connected_connection_count();
    LOGI("Connected connection count: %d", connected_count);
    return connected_count;
}

} // extern "C"
