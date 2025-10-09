#ifndef SRTLA_CORE_H
#define SRTLA_CORE_H

#include "srtla_connection.h"
#include "srtla_protocol.h"
#include "srtla_ip_manager.h"
#include <vector>
#include <memory>
#include <atomic>
#include <thread>
#include <mutex>
#include <unordered_map>
#include <sys/select.h>
#include <jni.h>

namespace srtla {

// Import SRTLA_ID_LEN from protocol
using Protocol::SRTLA_ID_LEN;

/**
 * Core SRTLA engine - manages multiple connections and packet routing
 * Based on srtla_send.c architecture
 */
class SrtlaCore {
public:
    SrtlaCore();
    ~SrtlaCore();

    // Main entry point (called from JNI)
    int start(int local_port, const std::string& server_host, const std::string& server_port);
    void stop();
    
    // Connection management (with Android Network binding support)
    bool add_connection(int fd, const std::string& virtual_ip, int weight, const std::string& type);
    std::string add_connection_auto_ip(int fd, int weight, const std::string& type);
    bool remove_connection(const std::string& virtual_ip);
    void update_connection_weight(const std::string& virtual_ip, int weight);
    
    // Connection refresh (reset all connections and re-register with SRTLA server)
    void refresh_all_connections();
    
    // Virtual IP management
    std::string allocate_virtual_ip();
    void release_virtual_ip(const std::string& virtual_ip);
    
    // Connection state queries
    int get_connected_connection_count() const;
    
    // Statistics callbacks (calls back to Java)
    void set_stats_callback(void (*callback)(const char* ip, int* stats, int stats_count));
    
    // Java callback integration
    void set_java_callbacks(
        std::string (*get_conns_string_cb)(),
        int (*get_last_update_cb)()
    );
    
private:
    // Main event loop (like C SRTLA)
    void event_loop();
    
    // Connection selection (weighted)
    Connection* select_connection();
    
    // Packet handling
    void handle_srt_packet(const uint8_t* data, size_t len);
    void handle_srtla_response(Connection* conn, const uint8_t* data, size_t len);
    void forward_to_srt_client(const uint8_t* data, size_t len);
    
    // NAK attribution (THE KEY FEATURE)
    Connection* find_connection_by_sequence(uint32_t seq);
    
    // Registration protocol
    void send_reg1(Connection* conn);
    void send_reg2_broadcast();
    void send_keepalives();
    bool handle_registration_packet(Connection* conn, const uint8_t* data, size_t len);
    
    // Housekeeping
    void connection_housekeeping();
    void cleanup_expired_zombies();  // Clean up zombie connections after 15 seconds
    void update_statistics();
    void report_connection_stats();
    
    // File descriptor management
    void setup_fd_set(fd_set& read_fds, fd_set& write_fds, int& max_fd);
    
private:
    // IP Management
    VirtualIPManager ip_manager_;
    
    // Connections
    std::vector<std::unique_ptr<Connection>> connections_;
    std::unordered_map<std::string, Connection*> connections_by_ip_;
    std::mutex connections_mutex_;  // Protects connections_ and connections_by_ip_
    
    // Fairness tracking for connection selection
    std::unordered_map<Connection*, uint64_t> packets_sent_per_connection_;
    
    // Sockets
    int srt_listen_socket_;         // Listen for SRT packets from encoder
    int srtla_server_socket_;       // Single socket to SRTLA server (for receiving)
    sockaddr_in srtla_server_addr_; // Server address for sending
    sockaddr_in srt_client_addr_;   // SRT encoder address (learned from first packet)
    bool has_srt_client_;           // True once we've seen a packet from encoder
    std::chrono::steady_clock::time_point last_srt_client_activity_; // Track client activity
    
    // SRTLA session (256-byte ID as per actual SRTLA protocol)
    uint8_t srtla_id_[256];
    
    // State
    std::atomic<bool> running_;
    std::atomic<bool> connected_;
    std::thread event_thread_;
    
    // Statistics callback
    void (*stats_callback_)(const char* ip, int* stats, int stats_count);
    
    // Java callback functions - for getting connection state from Java
    std::string (*get_conns_string_callback_)();
    int (*get_last_update_callback_)();
    int last_java_update_index_;  // Track changes in Java connection state
    
    // Helpers
    int64_t get_current_time_ms() const;
    void log_info(const char* format, ...);
    void log_error(const char* format, ...);
};

} // namespace srtla

#endif // SRTLA_CORE_H
