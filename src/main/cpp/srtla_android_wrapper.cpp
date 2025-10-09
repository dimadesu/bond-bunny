#include "srtla_android_wrapper.h"
#include <android/log.h>
#include <android/multinetwork.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <unistd.h>
#include <fcntl.h>
#include <cstring>
#include <thread>
#include <mutex>
#include <atomic>
#include <map>
#include <vector>
#include <cstdlib>
#include <ctime>

// Include original SRTLA headers
extern "C" {
#include "common.h"
}

#define LOG_TAG "SRTLAAndroidWrapper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Forward declarations from original SRTLA
extern "C" {
    // Connection management
    typedef struct conn {
        struct conn *next;
        int fd;
        time_t last_rcvd;
        time_t last_sent;
        struct sockaddr src;
        int removed;
        int in_flight_pkts;
        int window;
        int pkt_idx;
        int pkt_log[256];  // PKT_LOG_SZ
    } conn_t;
    
    // Global variables from original SRTLA (we'll encapsulate in session)
    extern struct sockaddr srtla_addr, srt_addr;
    extern conn_t *conns;
    extern int listenfd;
    extern char srtla_id[256];  // SRTLA_ID_LEN
    
    // Original functions we'll use
    int add_active_fd(int fd);
    void remove_active_fd(int fd);
    void send_reg1_all_conn();
    void send_keepalive_all_conn();
}

// Android-specific connection wrapper
typedef struct android_conn {
    conn_t base;                  // Original SRTLA connection structure
    std::string virtual_ip;       // SRTLA protocol identifier (e.g. "10.0.1.1")  
    std::string real_ip;          // Actual interface IP (e.g. "172.20.10.2")
    long network_handle;          // Android network handle for socket binding
    std::string network_type;     // "WiFi" or "Cellular"
    std::string connection_id;    // Unique identifier
} android_conn_t;

// Android SRTLA session - encapsulates original globals
struct srtla_android_session {
    // Original SRTLA state
    struct sockaddr srtla_addr;   // Server address
    struct sockaddr srt_addr;     // Local SRT listener address  
    int listenfd;                 // SRT listener socket
    char srtla_id[256];           // Session ID (SRTLA_ID_LEN)
    conn_t *conns;                // Connection list
    
    // Android-specific fields
    std::string server_host;
    int server_port;
    int local_port;
    bool running;
    std::thread worker_thread;
    
    // Connection management using original SRTLA structures
    std::map<std::string, android_conn_t*> connections;
    std::mutex connections_mutex;
};

// Generate virtual IP based on network type for SRTLA protocol
std::string generateVirtualIP(const std::string& network_type) {
    if (network_type == "WiFi") {
        return "10.0.1.1";
    } else if (network_type == "Cellular") {
        return "10.0.2.1";
    } else {
        return "10.0.9.1";  // Fallback for unknown types
    }
}

// Implementation of SRTLAAndroidWrapper methods

SRTLAAndroidWrapper::SRTLAAndroidWrapper() : running_(false) {
    session_ = std::make_unique<srtla_android_session>();
    // Initialize original SRTLA session ID
    for (int i = 0; i < 256; i++) {
        session_->srtla_id[i] = rand() & 0xFF;
    }
    session_->conns = nullptr;
    session_->running = false;
}

SRTLAAndroidWrapper::~SRTLAAndroidWrapper() {
    shutdown();
}

bool SRTLAAndroidWrapper::initialize(const std::string& server_host, int server_port, int local_port) {
        if (running_) {
            LOGE("Session already running");
            return false;
        }
        
        session_->server_host = server_host;
        session_->server_port = server_port;
        session_->local_port = local_port;
        
        // Resolve server address using original SRTLA format
        struct addrinfo hints, *result;
        memset(&hints, 0, sizeof(hints));
        hints.ai_family = AF_INET;
        hints.ai_socktype = SOCK_DGRAM;
        
        int ret = getaddrinfo(server_host.c_str(), std::to_string(server_port).c_str(), &hints, &result);
        if (ret != 0) {
            LOGE("Failed to resolve server: %s", gai_strerror(ret));
            return false;
        }
        
        memcpy(&session_->srtla_addr, result->ai_addr, result->ai_addrlen);
        freeaddrinfo(result);
        
        // Setup SRT listener address
        struct sockaddr_in *srt_in = (struct sockaddr_in*)&session_->srt_addr;
        srt_in->sin_family = AF_INET;
        srt_in->sin_addr.s_addr = INADDR_ANY;
        srt_in->sin_port = htons(local_port);
        
        // Create SRT listener socket
        session_->listenfd = socket(AF_INET, SOCK_DGRAM, 0);
        if (session_->listenfd < 0) {
            LOGE("Failed to create SRT listener socket");
            return false;
        }
        
        // Bind SRT listener socket
        if (bind(session_->listenfd, &session_->srt_addr, sizeof(session_->srt_addr)) < 0) {
            LOGE("Failed to bind SRT listener to port %d", local_port);
            close(session_->listenfd);
            session_->listenfd = -1;
            return false;
        }
        
        session_->running = true;
        running_ = true;
        
        LOGI("SRTLA session initialized: %s:%d -> local:%d", 
             server_host.c_str(), server_port, local_port);
        
        return true;
    }
    
    void SRTLAAndroidWrapper::shutdown() {
        if (!running_) return;
        
        running_ = false;
        session_->running = false;
        
        // Close SRT listener socket
        if (session_->listenfd >= 0) {
            close(session_->listenfd);
            session_->listenfd = -1;
        }
        
        // Close all SRTLA connections
        std::lock_guard<std::mutex> lock(session_mutex_);
        conn_t *current = session_->conns;
        while (current) {
            conn_t *next = current->next;
            if (current->fd >= 0) {
                close(current->fd);
            }
            delete current;
            current = next;
        }
        session_->conns = nullptr;
        
        LOGI("SRTLA session shutdown complete");
    }
    
    bool SRTLAAndroidWrapper::addConnection(long network_handle, const std::string& network_type, 
                      const std::string& real_ip, const std::string& virtual_ip) {
        std::lock_guard<std::mutex> lock(session_mutex_);
        
        if (!running_) {
            LOGE("Cannot add connection - session not running");
            return false;
        }
        
        // Create UDP socket for SRTLA connection
        int fd = socket(AF_INET, SOCK_DGRAM, 0);
        if (fd < 0) {
            LOGE("Failed to create SRTLA connection socket");
            return false;
        }
        
        // Bind socket to specific Android network
        if (android_setsocknetwork(network_handle, fd) != 0) {
            LOGE("Failed to bind socket to network handle %ld", network_handle);
            close(fd);
            return false;
        }
        
        // Create Android connection structure
        android_conn_t *android_conn = new android_conn_t();
        android_conn->network_handle = network_handle;
        android_conn->network_type = network_type;
        android_conn->real_ip = real_ip;
        android_conn->virtual_ip = virtual_ip;
        android_conn->connection_id = network_type + "_" + real_ip;
        
        // Initialize original SRTLA connection structure
        conn_t *conn = &android_conn->base;
        memset(conn, 0, sizeof(conn_t));
        conn->fd = fd;
        conn->last_rcvd = time(nullptr);
        conn->last_sent = time(nullptr);
        conn->removed = 0;
        conn->in_flight_pkts = 0;
        conn->window = 20;  // WINDOW_DEF
        conn->pkt_idx = 0;
        memcpy(&conn->src, &session_->srtla_addr, sizeof(conn->src));
        
        // Add to connection list (original SRTLA linked list)
        conn->next = session_->conns;
        session_->conns = conn;
        
        LOGI("Added SRTLA connection: %s (%s -> %s) handle=%ld", 
             network_type.c_str(), real_ip.c_str(), virtual_ip.c_str(), network_handle);
        
        return true;
    }
    
    bool SRTLAAndroidWrapper::removeConnection(long network_handle) {
        std::lock_guard<std::mutex> lock(session_mutex_);
        
        conn_t **current = &session_->conns;
        while (*current) {
            android_conn_t *android_conn = (android_conn_t*)*current;
            if (android_conn->network_handle == network_handle) {
                conn_t *to_remove = *current;
                *current = to_remove->next;
                
                if (to_remove->fd >= 0) {
                    close(to_remove->fd);
                }
                
                LOGI("Removed SRTLA connection for network handle %ld", network_handle);
                delete android_conn;
                return true;
            }
            current = &(*current)->next;
        }
        
        LOGW("Connection not found for network handle %ld", network_handle);
        return false;
    }
    
    int SRTLAAndroidWrapper::getActiveConnectionCount() {
        std::lock_guard<std::mutex> lock(session_mutex_);
        int count = 0;
        conn_t *current = session_->conns;
        while (current) {
            if (!current->removed) {
                count++;
            }
            current = current->next;
        }
        return count;
    }
    
    std::vector<std::string> SRTLAAndroidWrapper::getConnectionStats() {
        std::lock_guard<std::mutex> lock(session_mutex_);
        std::vector<std::string> stats;
        
        conn_t *current = session_->conns;
        while (current) {
            if (!current->removed) {
                android_conn_t *android_conn = (android_conn_t*)current;
                char stat[256];
                snprintf(stat, sizeof(stat), "%s: %s->%s window=%d in_flight=%d", 
                         android_conn->network_type.c_str(),
                         android_conn->real_ip.c_str(),
                         android_conn->virtual_ip.c_str(),
                         current->window,
                         current->in_flight_pkts);
                stats.push_back(std::string(stat));
            }
            current = current->next;
        }
        
        return stats;
    }

// JNI implementations

// JNI implementations
extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_srtla_SRTLANative_createSession(JNIEnv *env, jobject thiz) {
    auto* wrapper = new SRTLAAndroidWrapper();
    return reinterpret_cast<jlong>(wrapper);
}

JNIEXPORT void JNICALL
Java_com_example_srtla_SRTLANative_destroySession(JNIEnv *env, jobject thiz, jlong session_ptr) {
    auto* wrapper = reinterpret_cast<SRTLAAndroidWrapper*>(session_ptr);
    delete wrapper;
}

JNIEXPORT jboolean JNICALL
Java_com_example_srtla_SRTLANative_initialize(JNIEnv *env, jobject thiz, jlong session_ptr,
                                              jstring server_host, jint server_port, jint local_port) {
    auto* wrapper = reinterpret_cast<SRTLAAndroidWrapper*>(session_ptr);
    if (!wrapper) return JNI_FALSE;
    
    const char* host_cstr = env->GetStringUTFChars(server_host, nullptr);
    std::string host(host_cstr);
    env->ReleaseStringUTFChars(server_host, host_cstr);
    
    return wrapper->initialize(host, server_port, local_port) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_srtla_SRTLANative_shutdown(JNIEnv *env, jobject thiz, jlong session_ptr) {
    auto* wrapper = reinterpret_cast<SRTLAAndroidWrapper*>(session_ptr);
    if (wrapper) {
        wrapper->shutdown();
    }
}

JNIEXPORT jboolean JNICALL
Java_com_example_srtla_SRTLANative_addConnection(JNIEnv *env, jobject thiz, jlong session_ptr,
                                                 jlong network_handle, jstring network_type, 
                                                 jstring real_ip, jstring virtual_ip) {
    auto* wrapper = reinterpret_cast<SRTLAAndroidWrapper*>(session_ptr);
    if (!wrapper) return JNI_FALSE;
    
    const char* type_cstr = env->GetStringUTFChars(network_type, nullptr);
    std::string type(type_cstr);
    env->ReleaseStringUTFChars(network_type, type_cstr);
    
    const char* real_cstr = env->GetStringUTFChars(real_ip, nullptr);
    std::string real(real_cstr);
    env->ReleaseStringUTFChars(real_ip, real_cstr);
    
    const char* virtual_cstr = env->GetStringUTFChars(virtual_ip, nullptr);
    std::string virtual_str(virtual_cstr);
    env->ReleaseStringUTFChars(virtual_ip, virtual_cstr);
    
    return wrapper->addConnection((long)network_handle, type, real, virtual_str) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_srtla_SRTLANative_removeConnection(JNIEnv *env, jobject thiz, jlong session_ptr,
                                                    jlong network_handle) {
    auto* wrapper = reinterpret_cast<SRTLAAndroidWrapper*>(session_ptr);
    if (!wrapper) return;
    
    wrapper->removeConnection((long)network_handle);
}

JNIEXPORT jint JNICALL
Java_com_example_srtla_SRTLANative_getActiveConnectionCount(JNIEnv *env, jobject thiz, jlong session_ptr) {
    auto* wrapper = reinterpret_cast<SRTLAAndroidWrapper*>(session_ptr);
    return wrapper ? wrapper->getActiveConnectionCount() : 0;
}

JNIEXPORT jobjectArray JNICALL
Java_com_example_srtla_SRTLANative_getConnectionStats(JNIEnv *env, jobject thiz, jlong session_ptr) {
    auto* wrapper = reinterpret_cast<SRTLAAndroidWrapper*>(session_ptr);
    if (!wrapper) return nullptr;
    
    auto stats = wrapper->getConnectionStats();
    jobjectArray result = env->NewObjectArray(stats.size(), env->FindClass("java/lang/String"), nullptr);
    
    for (size_t i = 0; i < stats.size(); i++) {
        jstring stat_str = env->NewStringUTF(stats[i].c_str());
        env->SetObjectArrayElement(result, i, stat_str);
        env->DeleteLocalRef(stat_str);
    }
    
    return result;
}

} // extern "C"