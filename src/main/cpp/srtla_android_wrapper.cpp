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

// Include SRTLA headers
extern "C" {
#include "common.h"
}

#define LOG_TAG "SRTLAAndroidWrapper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// Android-specific connection structure  
struct srtla_android_connection {
    int fd;
    std::string virtual_ip;     // SRTLA protocol identifier (e.g. "10.0.1.1")
    std::string real_ip;        // Actual interface IP (e.g. "172.20.10.2")
    long network_handle;
    struct sockaddr_in local_addr;
    struct sockaddr_in server_addr;
    time_t last_sent;
    time_t last_received;
    int window;
    int in_flight_packets;
    bool active;
    std::string connection_id;
    std::string network_type;   // "WiFi" or "Cellular"
};

// Android session structure
struct srtla_android_session {
    std::string server_host;
    int server_port;
    int local_port;
    int listen_fd;
    struct sockaddr_in server_addr;
    struct sockaddr_in client_addr;
    
    std::vector<std::unique_ptr<srtla_android_connection>> connections;
    std::mutex connections_mutex;
    std::atomic<bool> running;
    std::thread processing_thread;
    
    char session_id[SRTLA_ID_LEN];
    bool session_registered;
    
    srtla_android_session() : server_port(0), local_port(0), listen_fd(-1), 
                             running(false), session_registered(false) {
        memset(session_id, 0, SRTLA_ID_LEN);
    }
};

// Generate virtual IP based on network type
std::string generateVirtualIP(const std::string& network_type) {
    if (network_type == "WiFi") {
        return "10.0.1.1";
    } else if (network_type == "Cellular") {
        return "10.0.2.1";
    } else {
        // Fallback for unknown types
        return "10.0.9.1";
    }
}

SRTLAAndroidWrapper::SRTLAAndroidWrapper() : running_(false) {
    session_ = std::make_unique<srtla_android_session>();
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
    
    // Resolve server address
    struct hostent* he = gethostbyname(server_host.c_str());
    if (!he) {
        LOGE("Failed to resolve server host: %s", server_host.c_str());
        return false;
    }
    
    memset(&session_->server_addr, 0, sizeof(session_->server_addr));
    session_->server_addr.sin_family = AF_INET;
    session_->server_addr.sin_port = htons(server_port);
    memcpy(&session_->server_addr.sin_addr, he->h_addr_list[0], he->h_length);
    
    // Create listening socket for SRT client connections
    session_->listen_fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (session_->listen_fd < 0) {
        LOGE("Failed to create listen socket");
        return false;
    }
    
    struct sockaddr_in listen_addr;
    memset(&listen_addr, 0, sizeof(listen_addr));
    listen_addr.sin_family = AF_INET;
    listen_addr.sin_addr.s_addr = INADDR_ANY;
    listen_addr.sin_port = htons(local_port);
    
    if (bind(session_->listen_fd, (struct sockaddr*)&listen_addr, sizeof(listen_addr)) < 0) {
        LOGE("Failed to bind listen socket to port %d", local_port);
        close(session_->listen_fd);
        session_->listen_fd = -1;
        return false;
    }
    
    // Generate random session ID
    FILE* random_fd = fopen("/dev/urandom", "rb");
    if (random_fd) {
        fread(session_->session_id, 1, SRTLA_ID_LEN, random_fd);
        fclose(random_fd);
    } else {
        // Fallback to time-based ID
        uint64_t timestamp = time(nullptr);
        memcpy(session_->session_id, &timestamp, sizeof(timestamp));
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
    
    // Close listening socket
    if (session_->listen_fd >= 0) {
        close(session_->listen_fd);
        session_->listen_fd = -1;
    }
    
    // Close all connections
    removeAllConnections();
    
    LOGI("SRTLA session shut down");
}

bool SRTLAAndroidWrapper::addConnection(const std::string& real_ip, long network_handle, const std::string& network_type) {
    if (!running_) {
        LOGE("Session not running");
        return false;
    }
    
    // Generate virtual IP for this network type
    std::string virtual_ip = generateVirtualIP(network_type);
    
    LOGI("Adding connection: real_ip=%s, virtual_ip=%s, type=%s, handle=%ld", 
         real_ip.c_str(), virtual_ip.c_str(), network_type.c_str(), network_handle);
    
    std::lock_guard<std::mutex> lock(session_->connections_mutex);
    
    // Check if connection with same virtual IP already exists
    for (const auto& conn : session_->connections) {
        if (conn->virtual_ip == virtual_ip) {
            LOGW("Connection with virtual IP %s already exists, removing old one", virtual_ip.c_str());
            // Remove existing connection for this network type
            session_->connections.erase(
                std::remove_if(session_->connections.begin(), session_->connections.end(),
                    [&virtual_ip](const std::unique_ptr<srtla_android_connection>& conn) {
                        return conn->virtual_ip == virtual_ip;
                    }),
                session_->connections.end());
            break;
        }
    }
    
    auto connection = std::make_unique<srtla_android_connection>();
    connection->virtual_ip = virtual_ip;
    connection->real_ip = real_ip;
    connection->network_handle = network_handle;
    connection->network_type = network_type;
    connection->connection_id = virtual_ip + ":" + network_type;
    connection->window = 20 * 1000; // Default window from SRTLA
    connection->in_flight_packets = 0;
    connection->active = false;
    
    // Parse real IP for socket binding (use real interface IP)
    if (inet_aton(real_ip.c_str(), &connection->local_addr.sin_addr) == 0) {
        LOGE("Invalid real IP: %s", real_ip.c_str());
        return false;
    }
    connection->local_addr.sin_family = AF_INET;
    connection->local_addr.sin_port = 0; // Let system choose port
    
    // Create socket and bind to network
    connection->fd = socket(AF_INET, SOCK_DGRAM, 0);
    if (connection->fd < 0) {
        LOGE("Failed to create socket for %s (%s)", virtual_ip.c_str(), real_ip.c_str());
        return false;
    }
    
    // Bind to specific network interface (Android network handle)
    if (network_handle != 0) {
        net_handle_t net_handle = static_cast<net_handle_t>(network_handle);
        if (android_setsocknetwork(net_handle, connection->fd) != 0) {
            LOGD("Warning: Could not bind to network handle %ld for %s (%s): %s", 
                 network_handle, virtual_ip.c_str(), real_ip.c_str(), strerror(errno));
            // Continue anyway - socket can still work without network binding
        } else {
            LOGI("Successfully bound socket to network handle %ld for %s (%s)", 
                 network_handle, virtual_ip.c_str(), real_ip.c_str());
        }
    }
    
    // Bind to real local address
    if (bind(connection->fd, (struct sockaddr*)&connection->local_addr, 
             sizeof(connection->local_addr)) < 0) {
        LOGE("Failed to bind socket to real IP %s (virtual: %s): %s", 
             real_ip.c_str(), virtual_ip.c_str(), strerror(errno));
        close(connection->fd);
        return false;
    }
    
    // Copy server address
    connection->server_addr = session_->server_addr;
    
    session_->connections.push_back(std::move(connection));
    
    LOGI("Added SRTLA connection: virtual=%s, real=%s, type=%s (handle=%ld)", 
         virtual_ip.c_str(), real_ip.c_str(), network_type.c_str(), network_handle);
    
    return true;
}

void SRTLAAndroidWrapper::removeConnection(const std::string& virtual_ip) {
    std::lock_guard<std::mutex> lock(session_->connections_mutex);
    
    auto it = std::remove_if(session_->connections.begin(), session_->connections.end(),
        [&virtual_ip](const std::unique_ptr<srtla_android_connection>& conn) {
            if (conn->virtual_ip == virtual_ip) {
                if (conn->fd >= 0) {
                    close(conn->fd);
                }
                return true;
            }
            return false;
        });
    
    if (it != session_->connections.end()) {
        session_->connections.erase(it, session_->connections.end());
        LOGI("Removed SRTLA connection: %s", virtual_ip.c_str());
    }
}

void SRTLAAndroidWrapper::removeAllConnections() {
    std::lock_guard<std::mutex> lock(session_->connections_mutex);
    
    for (auto& conn : session_->connections) {
        if (conn->fd >= 0) {
            close(conn->fd);
        }
    }
    session_->connections.clear();
    
    LOGI("Removed all SRTLA connections");
}

int SRTLAAndroidWrapper::getActiveConnectionCount() const {
    std::lock_guard<std::mutex> lock(session_->connections_mutex);
    
    int count = 0;
    for (const auto& conn : session_->connections) {
        if (conn->active) {
            count++;
        }
    }
    return count;
}

std::vector<std::string> SRTLAAndroidWrapper::getConnectionStats() const {
    std::lock_guard<std::mutex> lock(session_->connections_mutex);
    
    std::vector<std::string> stats;
    for (const auto& conn : session_->connections) {
        char stat_line[256];
        snprintf(stat_line, sizeof(stat_line),
                "Connection: %s, Window: %d, InFlight: %d, Active: %s",
                conn->connection_id.c_str(), conn->window, 
                conn->in_flight_packets, conn->active ? "Yes" : "No");
        stats.push_back(std::string(stat_line));
    }
    return stats;
}

// Placeholder for packet processing - will implement SRTLA protocol logic
void SRTLAAndroidWrapper::processSRTPacket(const uint8_t* data, size_t length) {
    // TODO: Implement SRTLA packet forwarding logic using original SRTLA functions
    LOGD("Processing SRT packet of %zu bytes", length);
}

void SRTLAAndroidWrapper::processSRTLAResponse(const uint8_t* data, size_t length, const std::string& connection_id) {
    // TODO: Implement SRTLA response handling using original SRTLA functions  
    LOGD("Processing SRTLA response of %zu bytes for connection %s", length, connection_id.c_str());
}

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
                                                 jstring real_ip, jlong network_handle, jstring network_type) {
    auto* wrapper = reinterpret_cast<SRTLAAndroidWrapper*>(session_ptr);
    if (!wrapper) return JNI_FALSE;
    
    const char* ip_cstr = env->GetStringUTFChars(real_ip, nullptr);
    std::string ip(ip_cstr);
    env->ReleaseStringUTFChars(real_ip, ip_cstr);
    
    const char* type_cstr = env->GetStringUTFChars(network_type, nullptr);
    std::string type(type_cstr);
    env->ReleaseStringUTFChars(network_type, type_cstr);
    
    return wrapper->addConnection(ip, (long)network_handle, type) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_example_srtla_SRTLANative_removeConnection(JNIEnv *env, jobject thiz, jlong session_ptr,
                                                    jstring local_ip) {
    auto* wrapper = reinterpret_cast<SRTLAAndroidWrapper*>(session_ptr);
    if (!wrapper) return;
    
    const char* ip_cstr = env->GetStringUTFChars(local_ip, nullptr);
    std::string ip(ip_cstr);
    env->ReleaseStringUTFChars(local_ip, ip_cstr);
    
    wrapper->removeConnection(ip);
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