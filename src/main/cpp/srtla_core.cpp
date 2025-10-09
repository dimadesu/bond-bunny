#include "include/srtla_core.h"
#include "include/srtla_protocol.h"
#include "include/srtla_ip_manager.h"
#include <android/log.h>
#include <jni.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <unistd.h>
#include <cstring>
#include <algorithm>

// External reference to JNI globals
extern JavaVM* g_java_vm;
#include <vector>
#include <set>
#include <netdb.h>
#include <errno.h>
#include <cstdarg>
#include <sys/time.h>
#include <fcntl.h>

#define LOG_TAG "SrtlaCore"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Android Network binding helpers (similar to Moblin's requiredInterface approach)

namespace srtla {

SrtlaCore::SrtlaCore()
    : srt_listen_socket_(-1),
      srtla_server_socket_(-1),
      has_srt_client_(false),
      last_srt_client_activity_(std::chrono::steady_clock::now()),
      running_(false),
      connected_(false),
      stats_callback_(nullptr),
      get_conns_string_callback_(nullptr),
      get_last_update_callback_(nullptr),
      last_java_update_index_(0) {
    
    // Generate random SRTLA ID (256 bytes) immediately in constructor
    srand(time(nullptr));
    for (int i = 0; i < 256; i++) {
        srtla_id_[i] = rand() & 0xFF;
    }
    LOGI("Generated 256-byte SRTLA ID in constructor");
    
    std::memset(&srtla_server_addr_, 0, sizeof(srtla_server_addr_));
}

SrtlaCore::~SrtlaCore() {
    stop();
}

int SrtlaCore::start(int local_port, const std::string& server_host, const std::string& server_port) {
    LOGI("Starting SRTLA: local=%d, server=%s:%s", local_port, server_host.c_str(), server_port.c_str());
    
    // Create local SRT listening socket
    srt_listen_socket_ = socket(AF_INET, SOCK_DGRAM, 0);
    if (srt_listen_socket_ < 0) {
        LOGE("Failed to create SRT socket: %s", strerror(errno));
        return -1;
    }
    
    // Note: SO_REUSEADDR removed - it can cause multiple instances to bind to same port
    // If bind fails with EADDRINUSE, the previous instance hasn't fully cleaned up yet
    
    struct sockaddr_in local_addr;
    std::memset(&local_addr, 0, sizeof(local_addr));
    local_addr.sin_family = AF_INET;
    local_addr.sin_addr.s_addr = INADDR_ANY;
    local_addr.sin_port = htons(local_port);
    
    if (bind(srt_listen_socket_, (struct sockaddr*)&local_addr, sizeof(local_addr)) < 0) {
        LOGE("Failed to bind SRT socket to port %d: %s (EADDRINUSE means port is still in use)", 
             local_port, strerror(errno));
        close(srt_listen_socket_);
        srt_listen_socket_ = -1;
        return -1;
    }
    
    // Create SRTLA server socket
    srtla_server_socket_ = socket(AF_INET, SOCK_DGRAM, 0);
    if (srtla_server_socket_ < 0) {
        LOGE("Failed to create SRTLA socket: %s", strerror(errno));
        close(srt_listen_socket_);
        return -1;
    }
    
    // Resolve server address (supports both hostnames and IP addresses)
    struct addrinfo hints, *result = nullptr;
    std::memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_DGRAM;
    
    int ret = getaddrinfo(server_host.c_str(), server_port.c_str(), &hints, &result);
    if (ret != 0 || result == nullptr) {
        LOGE("Failed to resolve server address %s: %s", server_host.c_str(), gai_strerror(ret));
        close(srt_listen_socket_);
        close(srtla_server_socket_);
        return -1;
    }
    
    std::memcpy(&srtla_server_addr_, result->ai_addr, sizeof(srtla_server_addr_));
    freeaddrinfo(result);
    
    char ip_str[INET_ADDRSTRLEN];
    inet_ntop(AF_INET, &srtla_server_addr_.sin_addr, ip_str, sizeof(ip_str));
    LOGI("Resolved %s to %s:%s", server_host.c_str(), ip_str, server_port.c_str());
    
    running_ = true;
    
    // Reset client state and activity tracking
    has_srt_client_ = false;
    last_srt_client_activity_ = std::chrono::steady_clock::now();
    memset(&srt_client_addr_, 0, sizeof(srt_client_addr_));
    
    event_thread_ = std::thread(&SrtlaCore::event_loop, this);
    
    LOGI("SRTLA started successfully");
    return 0;
}

void SrtlaCore::stop() {
    if (!running_) return;
    
    LOGI("Stopping SRTLA");
    running_ = false;
    
    // Close listening socket first to interrupt select() in event loop
    if (srt_listen_socket_ >= 0) {
        shutdown(srt_listen_socket_, SHUT_RDWR);
        close(srt_listen_socket_);
        srt_listen_socket_ = -1;
    }
    
    // Wait for event loop to finish (with timeout)
    if (event_thread_.joinable()) {
        event_thread_.join();
        LOGI("Event thread terminated");
    }
    
    // Close all connection file descriptors
    for (auto& conn : connections_) {
        int fd = conn->get_fd();
        if (fd >= 0) {
            shutdown(fd, SHUT_RDWR);
            close(fd);
            LOGI("Closed connection fd=%d, ip=%s", fd, conn->get_virtual_ip().c_str());
        }
    }
    
    // Close server socket
    if (srtla_server_socket_ >= 0) {
        close(srtla_server_socket_);
        srtla_server_socket_ = -1;
    }
    
    // Clear all data structures
    connections_.clear();
    connections_by_ip_.clear();
    
    // Clear callback to prevent any late calls
    stats_callback_ = nullptr;
    
    LOGI("SRTLA stopped cleanly");
}

bool SrtlaCore::add_connection(int fd, const std::string& virtual_ip, int weight, const std::string& type) {
    LOGI("Adding connection: fd=%d, ip=%s, type=%s, weight=%d", fd, virtual_ip.c_str(), type.c_str(), weight);
    
    // Check if connection with same virtual_ip already exists
    {
        std::lock_guard<std::mutex> lock(connections_mutex_);
        auto it = connections_by_ip_.find(virtual_ip);
        if (it != connections_by_ip_.end()) {
            Connection* existing = it->second;
            if (!existing->is_zombie()) {
                LOGW("Connection %s already exists and is active, skipping", virtual_ip.c_str());
                return false;
            } else {
                LOGI("Replacing zombie connection %s with new connection", virtual_ip.c_str());
                // Remove the zombie connection first
                connections_.erase(
                    std::remove_if(connections_.begin(), connections_.end(),
                        [&virtual_ip](const std::unique_ptr<Connection>& conn) {
                            return conn->get_virtual_ip() == virtual_ip;
                        }),
                    connections_.end());
                connections_by_ip_.erase(it);
            }
        }
    }
    
    // Network binding already done in Java
    // The socket is already bound to the specific network interface
    LOGI("Socket fd=%d already bound to network in Java", fd);
    
    // SRTLA-style socket optimization
    // Set large send buffer (8MB like SRTLA reference implementation)
    int bufsize = 8 * 1024 * 1024;  // 8MB send buffer
    if (setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &bufsize, sizeof(bufsize)) < 0) {
        LOGW("Failed to set send buffer size to %d on fd=%d: %s", bufsize, fd, strerror(errno));
    }
    
    // Set non-blocking mode (SRTLA uses SOCK_DGRAM | SOCK_NONBLOCK)
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags >= 0) {
        if (fcntl(fd, F_SETFL, flags | O_NONBLOCK) < 0) {
            LOGW("Failed to set O_NONBLOCK on fd=%d: %s", fd, strerror(errno));
        }
    } else {
        LOGW("Failed to get flags for fd=%d: %s", fd, strerror(errno));
    }
    
    // Log the local address of this socket - CRITICAL for verifying interface binding
    struct sockaddr_in local_addr;
    socklen_t local_addr_len = sizeof(local_addr);
    if (getsockname(fd, (struct sockaddr*)&local_addr, &local_addr_len) == 0) {
        char ip_str[INET_ADDRSTRLEN];
        inet_ntop(AF_INET, &local_addr.sin_addr, ip_str, sizeof(ip_str));
        LOGI("Final check: Connection fd=%d (%s) bound to local interface %s:%d", fd, type.c_str(), ip_str, ntohs(local_addr.sin_port));
    } else {
        LOGW("Could not get local address for fd=%d: %s", fd, strerror(errno));
    }
    
    // Socket is already connected to SRTLA server in Java
    // Java calls socket.connect() before passing FD to native code
    // Calling connect() again here would interfere with the existing connection
    LOGI("Connection fd=%d already connected to SRTLA server by Java layer", fd);
    
    auto conn = std::make_unique<Connection>(fd, virtual_ip, weight, type);
    Connection* conn_ptr = conn.get();
    
    // Lock mutex when adding to collections
    {
        std::lock_guard<std::mutex> lock(connections_mutex_);
        connections_.push_back(std::move(conn));
        connections_by_ip_[virtual_ip] = conn_ptr;
    }
    
    LOGI("Added connection: fd=%d, ip=%s, total=%d", fd, virtual_ip.c_str(), (int)connections_.size());
    
    // Send REG1 packet to register this connection
    send_reg1(conn_ptr);
    
    // Wait for SRTLA server registration handshake (REG1 → REG2 → REG3)
    // Connection will transition to CONNECTED state when REG3 is received from server
    LOGI("Connection %s waiting for SRTLA server registration handshake", virtual_ip.c_str());
    
    return true;
}

bool SrtlaCore::remove_connection(const std::string& virtual_ip) {
    // Lock mutex to prevent concurrent access during removal
    std::lock_guard<std::mutex> lock(connections_mutex_);
    
    auto it = connections_by_ip_.find(virtual_ip);
    if (it == connections_by_ip_.end()) {
        LOGW("Cannot remove connection - not found: ip=%s", virtual_ip.c_str());
        return false;
    }
    
    Connection* conn = it->second;
    
    // Check if already zombie
    if (conn->is_zombie()) {
        LOGW("Connection %s already marked as zombie, skipping", virtual_ip.c_str());
        return false;
    }
    
    // CRITICAL SAFETY CHECK: Count active (non-zombie) connections
    // Do not remove the last active connection - this would break the stream
    size_t active_count = 0;
    for (const auto& c : connections_) {
        if (c->get_state() == Connection::State::CONNECTED && !c->is_zombie()) {
            active_count++;
        }
    }
    
    if (active_count <= 1) {
        LOGW("REFUSING to remove connection %s - would leave zero active connections (currently %d active)", 
             virtual_ip.c_str(), (int)active_count);
        LOGW("Keeping at least one connection alive to prevent stream failure");
        return false;  // Refuse removal
    }
    
    // Log connection state before removal
    LOGI("Removing connection: ip=%s, window=%d, inflight=%d, state=%d (will have %d active after removal)", 
         virtual_ip.c_str(), conn->get_window(), conn->get_inflight(), 
         static_cast<int>(conn->get_state()), (int)(active_count - 1));
    
    // CRITICAL CHANGE: Don't close socket immediately!
    // Mark as ZOMBIE instead. The socket stays open and monitored in select()
    // to receive any packets the server might still send to this address.
    // The server has this connection in its list for ~10 seconds after we stop using it.
    // During this time, the server might send SRT handshake responses or data packets
    // to this connection's address. We need to receive them and forward to SRT client.
    // After 15 seconds, the zombie will expire and be cleaned up.
    conn->mark_zombie();
    
    // CRITICAL: If only one ACTIVE (non-zombie) connection will remain, clear its inflight tracking
    // The SRTLA server still has the removed connection in its list for ~10 seconds until timeout.
    // During this time, the server will send SRTLA ACKs to the removed connection's address
    // for packets that connection sent. Those ACKs will never arrive at the remaining connection.
    // To prevent inflight from growing indefinitely, we clear it and start fresh.
    // This may cause some packet loss, but it's better than breaking the stream.
    size_t remaining_count = active_count - 1;  // After this removal
    Connection* last_conn = nullptr;
    if (remaining_count == 1) {
        for (auto& c : connections_) {
            if (c->get_state() == Connection::State::CONNECTED && !c->is_zombie() && c.get() != conn) {
                last_conn = c.get();
                break;
            }
        }
    }
    
    if (remaining_count == 1 && last_conn != nullptr) {
        size_t old_inflight = last_conn->get_inflight();
        if (old_inflight > 0) {
            last_conn->clear_inflight();
            // Reset window to default to allow faster recovery
            last_conn->reset_window();
            LOGI("Cleared inflight (%d packets) and reset window on last remaining connection %s",
                 (int)old_inflight, last_conn->get_virtual_ip().c_str());
        }
    }
    
    // CRITICAL: Send keepalive on remaining connections to wake up the server
    // When a connection is removed, the server might be waiting for packets that
    // were sent on the removed connection. Send a keepalive to tell the server
    // we're still alive and it should continue sending ACKs to remaining connections.
    for (auto& c : connections_) {
        if (c->get_state() == Connection::State::CONNECTED) {
            uint8_t keepalive[4];
            size_t len = Protocol::create_keepalive_packet(keepalive, sizeof(keepalive));
            int ret = send(c->get_fd(), keepalive, len, 0);
            if (ret > 0) {
                LOGI("Sent keepalive on %s after connection removal", 
                     c->get_virtual_ip().c_str());
            }
        }
    }
    
    // Log remaining connections with corrected counts
    int active_count_after = 0;
    int zombie_count_after = 0;
    for (const auto& c : connections_) {
        if (c->is_zombie()) {
            zombie_count_after++;
            LOGI("  - ZOMBIE %s: window=%d, inflight=%d (will expire in 15s)", 
                 c->get_virtual_ip().c_str(), c->get_window(), c->get_inflight());
        } else {
            active_count_after++;
            LOGI("  - ACTIVE %s: window=%d, inflight=%d, state=%d", 
                 c->get_virtual_ip().c_str(), c->get_window(), c->get_inflight(),
                 static_cast<int>(c->get_state()));
        }
    }
    LOGI("Connection removal completed. Total: %d active + %d zombie connections", active_count_after, zombie_count_after);
    
    if (active_count_after == 0) {
        LOGE("CRITICAL ERROR: No active connections remaining! Stream will fail!");
        LOGE("This should not happen due to safety checks - please investigate");
    }
    
    return true;
}

void SrtlaCore::update_connection_weight(const std::string& virtual_ip, int weight) {
    auto it = connections_by_ip_.find(virtual_ip);
    if (it != connections_by_ip_.end()) {
        // Weight is read-only in current implementation
        // Would need to add setter to Connection class
        LOGD("Update weight for %s to %d (not implemented)", virtual_ip.c_str(), weight);
    }
}

void SrtlaCore::refresh_all_connections() {
    std::lock_guard<std::mutex> lock(connections_mutex_);
    
    LOGI("Refreshing all SRTLA connections - resetting registration state");
    
    // Reset all connections to DISCONNECTED state to force re-registration
    for (auto& conn : connections_) {
        if (conn->get_state() != Connection::State::ZOMBIE) {
            LOGI("Resetting connection %s (%s) for re-registration", 
                 conn->get_virtual_ip().c_str(), conn->get_type().c_str());
                 
            // Reset connection state to force re-registration
            conn->set_state(Connection::State::DISCONNECTED);
            
            // Clear inflight packets and reset window for clean start
            conn->clear_inflight();
            conn->reset_window();
            
            // Reset activity time to prevent timeout during refresh
            conn->set_last_activity(std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count());
        }
    }
    
    // Clear any pending SRT client state to ensure clean handshake
    has_srt_client_ = false;
    std::memset(&srt_client_addr_, 0, sizeof(srt_client_addr_));
    
    LOGI("Connection refresh complete - %d connections reset for re-registration", 
         (int)connections_.size());
}

std::string SrtlaCore::allocate_virtual_ip() {
    return ip_manager_.allocate_ip();
}

void SrtlaCore::release_virtual_ip(const std::string& virtual_ip) {
    ip_manager_.release_ip(virtual_ip);
}

int SrtlaCore::get_connected_connection_count() const {
    // Use const_cast to access mutex in const method (safe since we're not modifying data)
    std::lock_guard<std::mutex> lock(const_cast<std::mutex&>(connections_mutex_));
    
    int connected_count = 0;
    int total_count = connections_.size();
    
    LOGI("Connection state check: %d total connections", total_count);
    
    for (const auto& conn : connections_) {
        const char* state_str = "UNKNOWN";
        switch (conn->get_state()) {
            case Connection::State::DISCONNECTED: state_str = "DISCONNECTED"; break;
            case Connection::State::REGISTERING_REG1: state_str = "REGISTERING_REG1"; break;
            case Connection::State::REGISTERING_REG2: state_str = "REGISTERING_REG2"; break;
            case Connection::State::CONNECTED: state_str = "CONNECTED"; break;
            case Connection::State::ZOMBIE: state_str = "ZOMBIE"; break;
            case Connection::State::FAILED: state_str = "FAILED"; break;
        }
        
        bool is_zombie = conn->is_zombie();
        LOGI("  Connection %s (fd=%d): state=%s, zombie=%s", 
             conn->get_virtual_ip().c_str(), conn->get_fd(), state_str, is_zombie ? "YES" : "NO");
        
        if (conn->get_state() == Connection::State::CONNECTED && !conn->is_zombie()) {
            connected_count++;
        }
    }
    
    LOGI("Result: %d connected out of %d total", connected_count, total_count);
    return connected_count;
}

std::string SrtlaCore::add_connection_auto_ip(int fd, int weight, const std::string& type) {
    // Automatically allocate a virtual IP
    std::string virtual_ip = allocate_virtual_ip();
    if (virtual_ip.empty()) {
        LOGE("Failed to allocate virtual IP for new connection");
        return "";
    }
    
    LOGI("Auto-allocated virtual IP %s for %s connection", virtual_ip.c_str(), type.c_str());
    
    // Use the existing add_connection method
    bool result = add_connection(fd, virtual_ip, weight, type);
    
    // If connection failed, release the IP back to the pool
    if (!result) {
        release_virtual_ip(virtual_ip);
        LOGE("Failed to add connection with auto-allocated IP %s, releasing IP", virtual_ip.c_str());
        return "";
    }
    
    return virtual_ip;
}

void SrtlaCore::set_stats_callback(void (*callback)(const char* ip, int* stats, int stats_count)) {
    stats_callback_ = callback;
}

void SrtlaCore::event_loop() {
    LOGI("Event loop started");
    
    uint8_t buffer[65536];
    
    while (running_) {
        fd_set read_fds, write_fds;
        int max_fd = -1;
        
        setup_fd_set(read_fds, write_fds, max_fd);
        
        struct timeval tv;
        tv.tv_sec = 0;
        tv.tv_usec = 200000;  // 200ms timeout (matches SRTLA reference)
        
        int activity = select(max_fd + 1, &read_fds, &write_fds, nullptr, &tv);
        
        if (activity < 0) {
            if (errno == EINTR) continue;
            LOGE("select() error: %s", strerror(errno));
            break;
        }
        
        // Handle SRT packets from encoder
        if (srt_listen_socket_ >= 0 && FD_ISSET(srt_listen_socket_, &read_fds)) {
            struct sockaddr_in from_addr;
            socklen_t addr_len = sizeof(from_addr);
            
            ssize_t len = recvfrom(srt_listen_socket_, buffer, sizeof(buffer), 0,
                                  (struct sockaddr*)&from_addr, &addr_len);
            
            if (len > 0) {
                // Update SRT client address if it changes (supports reconnection)
                // This allows the encoder to disconnect and reconnect from a different port
                if (!has_srt_client_ || 
                    memcmp(&srt_client_addr_, &from_addr, sizeof(from_addr)) != 0) {
                    
                    bool is_new_client = !has_srt_client_;
                    srt_client_addr_ = from_addr;
                    has_srt_client_ = true;
                    last_srt_client_activity_ = std::chrono::steady_clock::now();
                    
                    char ip_str[INET_ADDRSTRLEN];
                    inet_ntop(AF_INET, &from_addr.sin_addr, ip_str, sizeof(ip_str));
                    
                    if (is_new_client) {
                        LOGI("SRT client connected from %s:%d", ip_str, ntohs(from_addr.sin_port));
                    } else {
                        LOGI("SRT client reconnected from %s:%d", ip_str, ntohs(from_addr.sin_port));
                    }
                } else {
                    // Update activity timestamp for existing client
                    last_srt_client_activity_ = std::chrono::steady_clock::now();
                }
                LOGD("Received %zd bytes from SRT encoder", len);
                handle_srt_packet(buffer, len);
            }
        }
        
        // Handle responses from SRTLA connections
        // Lock mutex to safely iterate connections
        {
            std::lock_guard<std::mutex> lock(connections_mutex_);
            for (const auto& conn : connections_) {
                int fd = conn->get_fd();
                // Skip invalid file descriptors (can happen during connection removal)
                if (fd < 0) {
                    continue;
                }
                if (FD_ISSET(fd, &read_fds)) {
                    // Use recv() instead of recvfrom() since socket is connected
                    ssize_t len = recv(fd, buffer, sizeof(buffer), 0);
                    
                    if (len > 0) {
                        handle_srtla_response(conn.get(), buffer, len);
                    } else if (len == 0) {
                        LOGW("Connection fd=%d closed by server", fd);
                    } else {
                        LOGE("recv error on fd=%d: %s", fd, strerror(errno));
                    }
                }
            }
        } // End of mutex lock scope
        
        // Periodic housekeeping
        connection_housekeeping();
        update_statistics();
        
        // Check Java for connection state changes
        // This allows Java to control connection enable/disable dynamically
        static auto last_java_check = std::chrono::steady_clock::now();
        if (get_conns_string_callback_ && get_last_update_callback_) {
            auto now = std::chrono::steady_clock::now();
            if (std::chrono::duration_cast<std::chrono::milliseconds>(now - last_java_check).count() > 500) {
                int current_update_index = get_last_update_callback_();
                if (current_update_index != last_java_update_index_) {
                    LOGI("Java connection state changed (index %d -> %d), querying connections",
                         last_java_update_index_, current_update_index);
                    
                    std::string conns_string = get_conns_string_callback_();
                    LOGI("Java provided connections: %s", conns_string.c_str());
                    
                    // Parse connection string and update native state
                    // Format: connId|weight|enabled,connId|weight|enabled,...
                    // For now, just log - actual parsing would go here
                    // TODO: Parse and apply enable/disable state to connections
                    
                    last_java_update_index_ = current_update_index;
                }
                last_java_check = now;
            }
        }
        
        // Check for and recover dead connections (SRTLA-style: aggressive recovery)
        // Lock mutex for connection iteration
        {
            std::lock_guard<std::mutex> lock(connections_mutex_);
            for (auto& conn : connections_) {
                if (conn->is_timed_out()) {
                    if (conn->get_state() == Connection::State::CONNECTED) {
                        LOGI("Connection %s timed out (4s), attempting recovery", 
                             conn->get_virtual_ip().c_str());
                        conn->set_state(Connection::State::REGISTERING_REG1);
                        send_reg1(conn.get());
                    } else if (conn->get_state() != Connection::State::ZOMBIE) {
                        // Connection in registration state that timed out
                        LOGI("Connection %s recovery timed out, retrying registration", 
                             conn->get_virtual_ip().c_str());
                        conn->set_state(Connection::State::REGISTERING_REG1);
                        send_reg1(conn.get());
                    }
                }
            }
        } // End of mutex lock scope"
        
        // Report connection stats to Java (for UI updates)
        static auto last_stats_report = std::chrono::steady_clock::now();
        auto now = std::chrono::steady_clock::now();
        if (std::chrono::duration_cast<std::chrono::milliseconds>(now - last_stats_report).count() > 1000) {
            report_connection_stats();
            last_stats_report = now;
        }
        
        // Send keepalives periodically (every 200ms as per SRTLA protocol)
        static auto last_keepalive = std::chrono::steady_clock::now();
        now = std::chrono::steady_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - last_keepalive).count();
        if (elapsed >= 200) {
            send_keepalives();
            last_keepalive = now;
        }
        
        // Clean up expired zombie connections periodically (every 5 seconds)
        static auto last_zombie_cleanup = std::chrono::steady_clock::now();
        now = std::chrono::steady_clock::now();
        elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(now - last_zombie_cleanup).count();
        if (elapsed >= 5000) {
            cleanup_expired_zombies();
            last_zombie_cleanup = now;
        }
        
        // Check for stale SRT client (timeout after 10 seconds of inactivity)
        if (has_srt_client_) {
            now = std::chrono::steady_clock::now();
            auto client_idle_time = std::chrono::duration_cast<std::chrono::milliseconds>(
                now - last_srt_client_activity_).count();
            if (client_idle_time > 10000) {  // 10 seconds timeout
                LOGI("SRT client timed out after %lld ms of inactivity - resetting client state", (long long)client_idle_time);
                has_srt_client_ = false;
                memset(&srt_client_addr_, 0, sizeof(srt_client_addr_));
            }
        }
    }
    
    LOGI("Event loop stopped");
}

Connection* SrtlaCore::select_connection() {
    // Lock mutex to prevent concurrent access to connections
    std::lock_guard<std::mutex> lock(connections_mutex_);
    
    if (connections_.empty()) {
        LOGE("No connections available!");
        return nullptr;
    }
    
    // SRTLA-style connection selection: score = window / (inflight + 1)
    // Pick connection with highest score (simple, proven algorithm)
    Connection* best = nullptr;
    int best_score = -1;
    
    for (const auto& conn : connections_) {
        // Must be connected
        if (conn->get_state() != Connection::State::CONNECTED) {
            continue;
        }
        
        // Skip timed out connections
        if (conn->is_timed_out()) {
            continue;
        }
        
        // SRTLA formula: score = window / (inflight + 1)
        int score = conn->get_window() / (conn->get_inflight() + 1);
        
        if (score > best_score) {
            best = conn.get();
            best_score = score;
        }
    }
    
    if (!best) {
        LOGW("No valid connection available! total=%d", (int)connections_.size());
        return nullptr;
    }
    
    return best;
}

void SrtlaCore::handle_srt_packet(const uint8_t* data, size_t len) {
    // Log the SRT packet type for debugging
    if (len >= 4) {
        uint32_t first_word = (data[0] << 24) | (data[1] << 16) | (data[2] << 8) | data[3];
        uint16_t pkt_type = Protocol::get_packet_type(data, len);
        LOGI("*** SRT PACKET FROM CLIENT: len=%d type=0x%04x first_word=0x%08x ***", (int)len, pkt_type, first_word);
    }

    // Extract SRT sequence number
    uint32_t sequence = Protocol::parse_srt_sequence(data, len);
    
    // Select best connection
    Connection* conn = select_connection();
    if (!conn) {
        LOGW("No available connections for outgoing packet (total connections: %d)", (int)connections_.size());
        // Log details of all connections to understand why none are available
        // Use separate scope to lock mutex for logging
        {
            std::lock_guard<std::mutex> lock(connections_mutex_);
            for (const auto& c : connections_) {
                LOGW("  Connection %s: state=%d (0=DISC,1=REG1,2=REG2,3=CONN), window=%d, inflight=%d, timeout=%d",
                     c->get_virtual_ip().c_str(),
                     static_cast<int>(c->get_state()),
                     c->get_window(),
                     (int)c->get_inflight(),
                     c->is_timed_out());
            }
        }
        return;
    }
    
    // TEST: Try sending raw SRT packets instead of wrapped SRTLA packets
    // This might be what the original SRTLA server expects
    LOGI("*** TESTING: Sending raw SRT packet (len=%d) instead of SRTLA wrapped ***", (int)len);
    
    // Mark packet as sent with byte count (connection tracks it in its own inflight set)
    conn->mark_sent(sequence, len);
    
    // Send raw SRT packet to server via selected connection
    ssize_t sent = send(conn->get_fd(), data, len, 0);
    
    if (sent < 0) {
        LOGE("send() error on connection %s fd=%d: %s", 
             conn->get_virtual_ip().c_str(), conn->get_fd(), strerror(errno));
        
        // SRTLA-style: disable connection on send failure
        conn->set_last_activity(1);
        LOGW("Connection %s disabled due to send failure, will attempt recovery",
             conn->get_virtual_ip().c_str());
    } else {
        LOGI("*** SENT RAW SRT: %d bytes via %s (fd=%d, seq=%u, win=%d, inflight=%d, score=%d) ***", 
             (int)sent, conn->get_virtual_ip().c_str(), conn->get_fd(), sequence,
             (int)conn->get_window(), (int)conn->get_inflight(), conn->get_score());
    }
}

void SrtlaCore::handle_srtla_response(Connection* conn, const uint8_t* data, size_t len) {
    // Mark connection as active
    conn->mark_received();
    
    LOGI("*** RECEIVED RESPONSE: %d bytes from server via %s (fd=%d) ***", (int)len, conn->get_virtual_ip().c_str(), conn->get_fd());
    
    // Log packet type for debugging
    uint16_t pkt_type = Protocol::get_packet_type(data, len);
    LOGD("Packet type: 0x%04x", pkt_type);
    
    // Handle SRTLA data packets with virtual IP information
    if (pkt_type == Protocol::SRTLA_TYPE_DATA) {
        std::string virtual_ip;
        uint32_t sequence;
        const uint8_t* srt_data;
        size_t srt_len;
        
        if (Protocol::parse_srtla_data_packet(data, len, virtual_ip, sequence, srt_data, srt_len)) {
            LOGD("SRTLA data packet: vip=%s seq=%u srt_len=%d", virtual_ip.c_str(), sequence, (int)srt_len);
            
            // Verify this packet is for the correct virtual IP
            if (virtual_ip != conn->get_virtual_ip()) {
                LOGW("Virtual IP mismatch: expected %s, got %s", conn->get_virtual_ip().c_str(), virtual_ip.c_str());
                // Still forward it - might be valid
            }
            
            // Forward the extracted SRT data to client
            forward_to_srt_client(srt_data, srt_len);
            return;
        } else {
            LOGE("Failed to parse SRTLA data packet");
            return;
        }
    }
    
    // TEST: Handle raw SRT responses (if server sends them directly)
    if (pkt_type == Protocol::SRT_TYPE_DATA || 
        pkt_type == Protocol::SRT_TYPE_CONTROL ||
        pkt_type == Protocol::SRT_TYPE_ACK) {
        LOGI("*** RECEIVED RAW SRT RESPONSE: %d bytes type=0x%04x ***", (int)len, pkt_type);
        forward_to_srt_client(data, len);
        return;
    }
    
    // Check for SRT shutdown packet - reset client state
    if (pkt_type == Protocol::SRT_TYPE_SHUTDOWN) {
        LOGI("Received SRT SHUTDOWN - resetting client state");
        has_srt_client_ = false;
        memset(&srt_client_addr_, 0, sizeof(srt_client_addr_));
        // Don't forward shutdown to client since they initiated it
        return;
    }
    
    // Handle SRT ACK packets (Moblin-style: broadcast to all connections, just clean inflight)
    if (pkt_type == Protocol::SRT_TYPE_ACK) {
        // SRT ACK is at offset 16-20 (4 bytes) in the packet
        if (len >= 20) {
            uint32_t ack_sn = (data[16] << 24) | (data[17] << 16) | (data[18] << 8) | data[19];
            
            // Moblin-style: Broadcast to all connections - each removes packets <= ack_sn from inflight
            for (const auto& c : connections_) {
                if (c->get_state() == Connection::State::CONNECTED) {
                    c->handle_srt_ack_sn(ack_sn);
                }
            }
        }
        // Don't return - still forward ACK to SRT client
    }
    
    // Handle SRT NAK packets (Moblin-style: broadcast to ALL connections)
    if (pkt_type == Protocol::SRT_TYPE_NAK) {
        // Parse NAK sequences from packet
        constexpr int MAX_NAK_SEQS = 100;
        uint32_t nak_seqs[MAX_NAK_SEQS];
        int nak_count = Protocol::parse_srt_nak(data, len, nak_seqs, MAX_NAK_SEQS);
        
        if (nak_count > 0) {
            LOGD("Received SRT NAK with %d sequences", nak_count);
            
            // Moblin-style: Broadcast NAK to ALL connections
            // Each connection checks if it sent the packet (via its inflight set)
            for (int i = 0; i < nak_count; i++) {
                uint32_t seq = nak_seqs[i];
                for (const auto& c : connections_) {
                    if (c->get_state() == Connection::State::CONNECTED) {
                        c->handle_srt_nak_sn(seq);
                    }
                }
            }
        }
        // Don't return - still forward NAK to SRT client
    }
    
    // Check if this is a registration packet (REG3, REG_ERR, etc.)
    if (handle_registration_packet(conn, data, len)) {
        // Registration packet was handled
        return;
    }
    
    // Forward SRT packet back to encoder (if we know where it is)
    if (has_srt_client_) {
        forward_to_srt_client(data, len);
    } else {
        LOGW("Received server packet but don't know SRT client address yet");
    }
}

void SrtlaCore::forward_to_srt_client(const uint8_t* data, size_t len) {
    // Forward packet back to SRT encoder
    LOGI("*** FORWARDING to SRT client: %d bytes ***", (int)len);
    ssize_t sent = sendto(srt_listen_socket_, data, len, 0,
                         (struct sockaddr*)&srt_client_addr_, sizeof(srt_client_addr_));
    
    if (sent < 0) {
        LOGE("Failed to forward packet to SRT client: %s", strerror(errno));
    } else {
        LOGI("*** Successfully forwarded %d bytes to SRT client ***", (int)len);
    }
}

void SrtlaCore::send_reg1(Connection* conn) {
    uint8_t packet[258];  // 2 bytes type + 256 bytes ID
    size_t len = Protocol::create_reg1_packet(packet, sizeof(packet), srtla_id_);
    
    if (len > 0) {
        // Debug: Log first 16 bytes of ID we're sending
        LOGI("DEBUG: Sending REG1 with ID first 16 bytes: %02x%02x%02x%02x %02x%02x%02x%02x %02x%02x%02x%02x %02x%02x%02x%02x",
            srtla_id_[0], srtla_id_[1], srtla_id_[2], srtla_id_[3], srtla_id_[4], srtla_id_[5], srtla_id_[6], srtla_id_[7],
            srtla_id_[8], srtla_id_[9], srtla_id_[10], srtla_id_[11], srtla_id_[12], srtla_id_[13], srtla_id_[14], srtla_id_[15]);
        
        LOGI("Sending REG1 (%d bytes) to %s (fd=%d, state=%d)", (int)len,
             conn->get_virtual_ip().c_str(), conn->get_fd(), static_cast<int>(conn->get_state()));
        
        ssize_t sent = send(conn->get_fd(), packet, len, 0);
        
        if (sent < 0) {
            LOGE("Failed to send REG1 on fd=%d: %s", conn->get_fd(), strerror(errno));
        } else {
            LOGI("Sent REG1 packet (%zd bytes) via %s (fd=%d)", sent, 
                 conn->get_virtual_ip().c_str(), conn->get_fd());
        }
    }
}

void SrtlaCore::send_reg2_broadcast() {
    uint8_t packet[258];  // 2 bytes type + 256 bytes ID
    size_t len = Protocol::create_reg2_packet(packet, sizeof(packet), srtla_id_);
    
    if (len > 0) {
        for (const auto& conn : connections_) {
            // Skip zombie connections (don't send REG2 to removed connections)
            if (conn->is_zombie()) {
                continue;
            }
            
            ssize_t sent = send(conn->get_fd(), packet, len, 0);
            
            if (sent < 0) {
                LOGE("Failed to send REG2 on fd=%d: %s", conn->get_fd(), strerror(errno));
            } else {
                LOGI("Sent REG2 packet (%zd bytes) via %s (fd=%d)", sent,
                     conn->get_virtual_ip().c_str(), conn->get_fd());
            }
        }
    }
}

void SrtlaCore::send_keepalives() {
    uint8_t packet[10];  // 2 bytes type + 8 bytes timestamp
    size_t len = Protocol::create_keepalive_packet(packet, sizeof(packet));
    
    if (len > 0) {
        for (const auto& conn : connections_) {
            // Skip zombie connections (don't send keepalives to removed connections)
            if (conn->is_zombie()) {
                continue;
            }
            
            ssize_t sent = send(conn->get_fd(), packet, len, 0);
            
            if (sent < 0) {
                // Don't log every keepalive failure - too spammy
                static int error_count = 0;
                if (++error_count % 50 == 0) {
                    LOGW("Keepalive send errors on fd=%d: %d times", conn->get_fd(), error_count);
                }
            } else {
                // CRITICAL FIX: Update connection activity when successfully sending keepalives
                // This prevents connections from timing out due to lack of activity
                conn->mark_sent();
            }
        }
    }
}

bool SrtlaCore::handle_registration_packet(Connection* conn, const uint8_t* data, size_t len) {
    uint16_t pkt_type = Protocol::get_packet_type(data, len);
    
    // Handle REG2 response from server
    if (pkt_type == Protocol::SRTLA_TYPE_REG2) {
        LOGI("Received REG2 response from server via %s (fd=%d)", 
             conn->get_virtual_ip().c_str(), conn->get_fd());
        
        // Server sends back REG2 with the full SRTLA ID, but we validate only first half
        if (len >= 2 + SRTLA_ID_LEN) {
            // Debug: Log first 16 bytes of both IDs for comparison
            LOGI("DEBUG: Received ID first 16 bytes: %02x%02x%02x%02x %02x%02x%02x%02x %02x%02x%02x%02x %02x%02x%02x%02x",
                data[2], data[3], data[4], data[5], data[6], data[7], data[8], data[9],
                data[10], data[11], data[12], data[13], data[14], data[15], data[16], data[17]);
            LOGI("DEBUG: Local ID first 16 bytes: %02x%02x%02x%02x %02x%02x%02x%02x %02x%02x%02x%02x %02x%02x%02x%02x",
                srtla_id_[0], srtla_id_[1], srtla_id_[2], srtla_id_[3], srtla_id_[4], srtla_id_[5], srtla_id_[6], srtla_id_[7],
                srtla_id_[8], srtla_id_[9], srtla_id_[10], srtla_id_[11], srtla_id_[12], srtla_id_[13], srtla_id_[14], srtla_id_[15]);
            
            // Verify first half of SRTLA ID matches what we sent (SRTLA protocol standard)
            if (memcmp(data + 2, srtla_id_, SRTLA_ID_LEN / 2) == 0) {
                // Copy full ID from server response (server completes the ID)
                memcpy(srtla_id_, data + 2, SRTLA_ID_LEN);
                LOGI("Connection group registered, broadcasting REG2 to all connections");
                
                // Broadcast REG2 to all connections
                send_reg2_broadcast();
            } else {
                LOGE("Received REG2 with mismatching ID!");
            }
        }
        
        conn->mark_received();
        return true;
    }
    
    // Handle REG3 - connection fully established
    if (pkt_type == Protocol::SRTLA_TYPE_REG3) {
        LOGI("Received REG3 - connection established via %s (fd=%d)", 
             conn->get_virtual_ip().c_str(), conn->get_fd());
        
        // Set connection state to CONNECTED
        conn->set_state(Connection::State::CONNECTED);
        conn->mark_received();
        return true;
    }
    
    // Handle SRTLA ACK packets (for congestion control)
    if (pkt_type == Protocol::SRTLA_TYPE_ACK) {
        // SRTLA ACK format from inspiration/srtla:
        // 4 bytes: type (SRTLA_TYPE_ACK << 16 in upper 16 bits)
        // 40 bytes: 10 sequence numbers (each 4 bytes, network byte order)
        // Total: 44 bytes
        const int RECV_ACK_INT = 10;
        const int expected_len = 4 + (RECV_ACK_INT * 4);  // 44 bytes
        
        if (len >= expected_len) {
            LOGD("Received SRTLA ACK from %s (fd=%d) with %d sequence numbers", 
                 conn->get_virtual_ip().c_str(), conn->get_fd(), RECV_ACK_INT);
            
            // Parse all 10 sequence numbers (starting at offset 4)
            // Moblin-style: Broadcast to ALL connections
            for (int i = 0; i < RECV_ACK_INT; i++) {
                int offset = 4 + (i * 4);
                uint32_t seq = (data[offset] << 24) | (data[offset+1] << 16) | 
                              (data[offset+2] << 8) | data[offset+3];
                
                // Broadcast this ACK to ALL connected connections
                // Each connection checks if it sent the packet and handles accordingly
                for (const auto& c : connections_) {
                    if (c->get_state() == Connection::State::CONNECTED) {
                        c->handle_srtla_ack_sn(seq);
                    }
                }
            }
            
            conn->mark_received();
        } else {
            LOGW("Received malformed SRTLA ACK from %s (fd=%d) - expected %d bytes, got %d", 
                 conn->get_virtual_ip().c_str(), conn->get_fd(), expected_len, (int)len);
            conn->mark_received();
        }
        return true;
    }
    
    // Handle keepalive responses
    if (pkt_type == Protocol::SRTLA_TYPE_KEEPALIVE) {
        LOGD("Received keepalive ACK from %s (fd=%d)",
             conn->get_virtual_ip().c_str(), conn->get_fd());
        
        conn->mark_received();
        return true;
    }
    else if (pkt_type == Protocol::SRTLA_TYPE_REG_ERR) {
        LOGE("Received REG_ERR (registration error) from %s (fd=%d)",
             conn->get_virtual_ip().c_str(), conn->get_fd());
        return true;
    }
    
    return false;
}

void SrtlaCore::connection_housekeeping() {
    // Remove timed out connections
    connections_.erase(
        std::remove_if(connections_.begin(), connections_.end(),
                      [](const std::unique_ptr<Connection>& conn) {
                          return conn->is_timed_out();
                      }),
        connections_.end());
}

void SrtlaCore::update_statistics() {
    if (!stats_callback_) return;
    
    for (const auto& conn : connections_) {
        int stats[12] = {0};
        stats[0] = conn->get_window();
        stats[1] = conn->get_inflight();
        stats[2] = static_cast<int>(conn->get_rtt());
        stats[3] = static_cast<int>(conn->get_rtt());
        stats[6] = static_cast<int>(conn->get_bytes_sent());
        stats[8] = static_cast<int>(conn->get_packets_sent());
        stats[10] = static_cast<int>(conn->get_nak_count());
        
        stats_callback_(conn->get_virtual_ip().c_str(), stats, 12);
    }
}

void SrtlaCore::setup_fd_set(fd_set& read_fds, fd_set& write_fds, int& max_fd) {
    FD_ZERO(&read_fds);
    FD_ZERO(&write_fds);
    
    if (srt_listen_socket_ >= 0) {
        FD_SET(srt_listen_socket_, &read_fds);
        max_fd = srt_listen_socket_;
    }
    
    // Lock mutex while iterating connections
    std::lock_guard<std::mutex> lock(connections_mutex_);
    for (const auto& conn : connections_) {
        int fd = conn->get_fd();
        // Skip invalid file descriptors (can happen during connection removal)
        if (fd < 0) {
            continue;
        }
        // Monitor ALL connections including zombies (to receive server packets)
        FD_SET(fd, &read_fds);
        if (fd > max_fd) max_fd = fd;
    }
}

void SrtlaCore::cleanup_expired_zombies() {
    // Lock mutex to prevent concurrent access
    std::lock_guard<std::mutex> lock(connections_mutex_);
    
    // Find expired zombies
    std::vector<std::string> expired_ips;
    for (const auto& conn : connections_) {
        if (conn->is_zombie_expired()) {
            expired_ips.push_back(conn->get_virtual_ip());
        }
    }
    
    // Clean up expired zombies
    for (const std::string& ip : expired_ips) {
        auto it = connections_by_ip_.find(ip);
        if (it == connections_by_ip_.end()) {
            continue;
        }
        
        Connection* conn = it->second;
        LOGI("Zombie connection %s expired after 15 seconds, cleaning up", ip.c_str());
        
        // Close socket
        int fd = conn->get_fd();
        if (fd >= 0) {
            close(fd);
        }
        
        // Release virtual IP back to the pool
        release_virtual_ip(ip);
        LOGI("Released virtual IP %s back to pool", ip.c_str());
        
        // Remove from vector
        connections_.erase(
            std::remove_if(connections_.begin(), connections_.end(),
                          [conn](const std::unique_ptr<Connection>& c) {
                              return c.get() == conn;
                          }),
            connections_.end());
        
        // Remove from map
        connections_by_ip_.erase(it);
        
        LOGI("Zombie connection %s removed. Remaining connections: %d", ip.c_str(), (int)connections_.size());
    }
}

void SrtlaCore::report_connection_stats() {
    if (!stats_callback_) {
        return;
    }
    
    // Report stats for each connection
    for (const auto& conn : connections_) {
        // Use a larger array to properly store long values
        // Layout: p1-p6 (6 ints), l1-l4 (4 longs as 8 ints), p7 (1 int)
        int stats[15];
        
        // p1-p6: int parameters
        stats[0] = conn->get_window();
        stats[1] = conn->get_inflight();
        stats[2] = static_cast<int>(conn->get_nak_count());
        stats[3] = 0; // reserved
        stats[4] = 0; // reserved
        stats[5] = 0; // reserved
        
        // l1-l4: long parameters (properly stored as 64-bit values split into ints)
        uint64_t bytes_sent = conn->get_bytes_sent();
        uint64_t packets_sent = conn->get_packets_sent();
        
        stats[6] = static_cast<int>(bytes_sent & 0xFFFFFFFF);        // low 32 bits
        stats[7] = static_cast<int>((bytes_sent >> 32) & 0xFFFFFFFF); // high 32 bits
        stats[8] = static_cast<int>(packets_sent & 0xFFFFFFFF);
        stats[9] = static_cast<int>((packets_sent >> 32) & 0xFFFFFFFF);
        stats[10] = 0; // bytes received (not tracked yet)
        stats[11] = 0;
        stats[12] = 0; // packets received (not tracked yet)
        stats[13] = 0;
        
        // p7: score
        stats[14] = conn->get_score();
        
        stats_callback_(conn->get_virtual_ip().c_str(), stats, 15);
    }
}

void SrtlaCore::set_java_callbacks(
    std::string (*get_conns_string_cb)(),
    int (*get_last_update_cb)()) {
    
    get_conns_string_callback_ = get_conns_string_cb;
    get_last_update_callback_ = get_last_update_cb;
    last_java_update_index_ = 0;
    
    LOGI("Java callbacks set for connection state management");
}

int64_t SrtlaCore::get_current_time_ms() const {
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    return static_cast<int64_t>(tv.tv_sec) * 1000 + tv.tv_usec / 1000;
}

void SrtlaCore::log_info(const char* format, ...) {
    va_list args;
    va_start(args, format);
    __android_log_vprint(ANDROID_LOG_INFO, LOG_TAG, format, args);
    va_end(args);
}

void SrtlaCore::log_error(const char* format, ...) {
    va_list args;
    va_start(args, format);
    __android_log_vprint(ANDROID_LOG_ERROR, LOG_TAG, format, args);
    va_end(args);
}

}  // namespace srtla
