#ifndef SRTLA_CONNECTION_H
#define SRTLA_CONNECTION_H

#include <string>
#include <cstdint>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unordered_set>

namespace srtla {

/**
 * Represents a single network connection (WiFi, Cellular, Ethernet)
 * Matches the conn_t structure from C SRTLA implementation
 */
class Connection {
public:
    enum class State {
        DISCONNECTED,
        REGISTERING_REG1,
        REGISTERING_REG2,
        CONNECTED,
        ZOMBIE,          // Connection removed but socket kept open to receive server packets
        FAILED
    };

    Connection(int fd, const std::string& virtual_ip, int weight, const std::string& type);
    ~Connection();

    // Getters
    int get_fd() const { return fd_; }
    const std::string& get_virtual_ip() const { return virtual_ip_; }
    const std::string& get_type() const { return type_; }
    int get_weight() const { return weight_; }
    State get_state() const { return state_; }
    int get_window() const { return window_; }
    int get_inflight() const { return packets_in_flight_.size(); }
    int64_t get_last_activity() const { return last_activity_; }
    bool is_timed_out() const;
    
    // Window management (moblin-style: each connection checks its own inflight set)
    void handle_srt_ack_sn(uint32_t ack_sn);      // Remove packets <= ack_sn from inflight (no window change)
    void handle_srt_nak_sn(uint32_t seq);         // If we sent it, remove & decrease window
    void handle_srtla_ack_sn(uint32_t seq);       // If we sent it, remove & increase window conditionally, always +1
    void increase_window();
    void decrease_window();
    void reset_window();
    
    // Packet tracking
    void mark_sent(uint32_t seq, size_t bytes);
    bool sent_packet(uint32_t seq) const;
    void clear_inflight();  // Clear inflight packet tracking (used when other connections are removed)
    
    // State management
    void set_state(State state);
    void mark_received();
    void mark_sent();
    void set_last_activity(int64_t timestamp);  // Set last activity timestamp (for disabling failed connections)
    void mark_zombie();  // Mark connection as zombie (removed but still monitored)
    bool is_zombie() const { return state_ == State::ZOMBIE; }
    bool is_zombie_expired() const;  // Check if zombie has expired (15s timeout)
    void invalidate_fd();  // Mark FD as -1 (for safe removal during select())
    
    // Statistics
    uint64_t get_bytes_sent() const { return bytes_sent_; }
    uint64_t get_packets_sent() const { return packets_sent_; }
    uint32_t get_nak_count() const { return nak_count_; }
    double get_rtt() const { return smooth_rtt_; }
    int get_score() const;
    
private:
    // Connection info
    int fd_;
    std::string virtual_ip_;
    std::string type_;
    int weight_;
    State state_;
    int64_t zombie_time_;  // Timestamp when connection was marked as zombie (ms)
    
    // Window management (like C SRTLA)
    int window_;
    static constexpr int WINDOW_DEF = 20;  // Moblin default
    static constexpr int WINDOW_MULT = 1000;
    static constexpr int WINDOW_MIN = 1;   // Moblin minimum (was 5, too high)
    static constexpr int WINDOW_MAX = 60;  // Moblin maximum
    
    // Packet tracking (moblin-style: unordered_set for O(1) lookups)
    // Note: inflight count is tracked by packets_in_flight_.size()
    std::unordered_set<uint32_t> packets_in_flight_;
    
    // Timing
    int64_t last_received_;
    int64_t last_sent_;
    int64_t last_activity_;
    
    // Statistics
    uint64_t bytes_sent_;
    uint64_t packets_sent_;
    uint32_t nak_count_;
    uint32_t ack_count_;
    
    // RTT tracking (dual-speed like your Java version)
    double smooth_rtt_;
    double fast_rtt_;
    int64_t last_rtt_measurement_;
    
    // Helper methods
    int64_t get_current_time_ms() const;
};

} // namespace srtla

#endif // SRTLA_CONNECTION_H
