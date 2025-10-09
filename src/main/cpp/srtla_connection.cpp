#include "include/srtla_connection.h"
#include <android/log.h>
#include <sys/time.h>
#include <cstring>
#include <algorithm>
#include <vector>

#define LOG_TAG "SrtlaConnection"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace srtla {

Connection::Connection(int fd, const std::string& virtual_ip, int weight, const std::string& type)
    : fd_(fd),
      virtual_ip_(virtual_ip),
      type_(type),
      weight_(weight),
      state_(State::DISCONNECTED),
      zombie_time_(0),
      window_(WINDOW_DEF * WINDOW_MULT),
      last_received_(0),
      last_sent_(0),
      last_activity_(get_current_time_ms()),
      bytes_sent_(0),
      packets_sent_(0),
      nak_count_(0),
      ack_count_(0),
      smooth_rtt_(100.0),  // Start with 100ms default
      fast_rtt_(100.0),
      last_rtt_measurement_(0) {
    
    LOGI("Connection created: fd=%d, ip=%s, weight=%d, type=%s, initial_inflight=%zu, initial_window=%d", 
         fd, virtual_ip.c_str(), weight, type.c_str(), packets_in_flight_.size(), window_);
}

Connection::~Connection() {
    LOGI("Connection destroyed: fd=%d, ip=%s", fd_, virtual_ip_.c_str());
}

bool Connection::is_timed_out() const {
    int64_t now = get_current_time_ms();
    return (now - last_activity_) > 4000;  // 4 second timeout (SRTLA standard)
}

void Connection::mark_sent(uint32_t seq, size_t bytes) {
    packets_in_flight_.insert(seq);
    
    packets_sent_++;
    bytes_sent_ += bytes;
    last_sent_ = get_current_time_ms();
    last_activity_ = last_sent_;
}

bool Connection::sent_packet(uint32_t seq) const {
    return packets_in_flight_.count(seq) > 0;
}

// Moblin-style: SRT ACK just removes packets from inflight, no window change
// Note: This is called for ALL connections but only affects packets <= ack_sn
void Connection::handle_srt_ack_sn(uint32_t ack_sn) {
    // Remove all packets with sequence <= ack_sn
    // SRT ACK acknowledges all packets up to and including ack_sn
    std::vector<uint32_t> to_remove;
    for (uint32_t seq : packets_in_flight_) {
        // Check if seq is <= ack_sn (considering wraparound)
        int32_t diff = static_cast<int32_t>(ack_sn - seq);
        if (diff >= 0) {
            to_remove.push_back(seq);
        }
    }
    
    for (uint32_t seq : to_remove) {
        packets_in_flight_.erase(seq);
    }
    
    if (!to_remove.empty()) {
        last_activity_ = get_current_time_ms();
        ack_count_ += to_remove.size();
    }
}

// Moblin-style: SRT NAK decreases window if we sent the packet
// Note: This is broadcast to all connections, each checks its own inflight set
void Connection::handle_srt_nak_sn(uint32_t seq) {
    // Try to remove from inflight - if not found, we didn't send it
    auto it = packets_in_flight_.find(seq);
    if (it == packets_in_flight_.end()) {
        // We didn't send this packet, ignore NAK
        return;
    }
    
    // We sent it - remove from inflight and decrease window
    packets_in_flight_.erase(it);
    
    window_ = std::max(window_ - 100, WINDOW_MIN * WINDOW_MULT);
    
    nak_count_++;
    last_activity_ = get_current_time_ms();
    
    LOGD("NAK: seq=%u, ip=%s, window=%d, inflight=%zu", 
         seq, virtual_ip_.c_str(), window_, packets_in_flight_.size());
}

// Moblin-style: SRTLA ACK increases window (conditionally +29 if we sent it, always +1)
void Connection::handle_srtla_ack_sn(uint32_t seq) {
    bool found = false;
    
    // Try to remove from inflight
    auto it = packets_in_flight_.find(seq);
    if (it != packets_in_flight_.end()) {
        found = true;
        // We sent this packet - remove from inflight
        packets_in_flight_.erase(it);
        
        // Measure RTT
        int64_t now = get_current_time_ms();
        if (last_sent_ > 0) {
            double rtt = static_cast<double>(now - last_sent_);
            smooth_rtt_ = smooth_rtt_ * 0.875 + rtt * 0.125;
            fast_rtt_ = fast_rtt_ * 0.75 + rtt * 0.25;
            last_rtt_measurement_ = now;
        }
        
        // If congested (inflight * MULT > window), add 29 (Moblin: windowIncrement - 1)
        if (packets_in_flight_.size() * WINDOW_MULT > static_cast<size_t>(window_)) {
            window_ += 29;
        }
        
        ack_count_++;
        last_activity_ = now;
    }
    
    // CRITICAL: ALWAYS increase window by +1, even if packet not in our inflight
    // This is the Moblin behavior - connection is healthy if receiving ACKs
    window_ = std::min(window_ + 1, WINDOW_MAX * WINDOW_MULT);
    
    // Log every 100th ACK
    static int ack_log_counter = 0;
    if (++ack_log_counter % 100 == 0) {
        LOGD("SRTLA ACK: seq=%u, ip=%s, found=%d, window=%d, inflight=%d",
            seq, virtual_ip_.c_str(), found, window_, (int)packets_in_flight_.size());
    }
}

void Connection::increase_window() {
    window_ = std::min(window_ + 1, WINDOW_MAX * WINDOW_MULT);
}

void Connection::decrease_window() {
    window_ = window_ * 3 / 4;  // Decrease to 75%
    if (window_ < WINDOW_MIN * WINDOW_MULT) window_ = WINDOW_MIN * WINDOW_MULT;
}

void Connection::reset_window() {
    window_ = WINDOW_DEF * WINDOW_MULT;
    packets_in_flight_.clear();
}

void Connection::set_state(State state) {
    state_ = state;
}

void Connection::mark_received() {
    last_received_ = get_current_time_ms();
    last_activity_ = last_received_;
}

void Connection::mark_sent() {
    last_sent_ = get_current_time_ms();
    last_activity_ = last_sent_;
}

void Connection::set_last_activity(int64_t timestamp) {
    last_activity_ = timestamp;
}

void Connection::invalidate_fd() {
    fd_ = -1;
}

void Connection::clear_inflight() {
    // Clear all inflight packet tracking
    // This is used when another connection is removed and its packets are lost
    packets_in_flight_.clear();
}

int Connection::get_score() const {
    // Score formula from inspiration/srtla: window / (in_flight_pkts + 1)
    // This gives connections with more available capacity a higher score
    // The +1 prevents division by zero and gives new connections a chance
    
    // Check if connection is healthy
    if (state_ != State::CONNECTED) {
        LOGD("Connection %s score=0: state=%d (not CONNECTED)", virtual_ip_.c_str(), static_cast<int>(state_));
        return 0;  // Not connected, can't use
    }
    
    // Check if connection is timed out
    bool timed_out = is_timed_out();
    if (timed_out) {
        int64_t now = get_current_time_ms();
        LOGD("Connection %s score=0: timed out (now=%lld, last_activity=%lld, diff=%lld ms)", 
             virtual_ip_.c_str(), (long long)now, (long long)last_activity_, (long long)(now - last_activity_));
        return 0;  // Timed out, don't use
    }
    
    // Calculate score: higher window and lower inflight = better
    // Window is already stored scaled (window * WINDOW_MULT)
    // Use actual set size for inflight count
    int score = window_ / (packets_in_flight_.size() + 1);
    
    LOGD("Connection %s score=%d: window=%d, inflight=%zu", 
         virtual_ip_.c_str(), score, window_, packets_in_flight_.size());
    
    return score;
}

int64_t Connection::get_current_time_ms() const {
    struct timeval tv;
    gettimeofday(&tv, nullptr);
    return static_cast<int64_t>(tv.tv_sec) * 1000 + tv.tv_usec / 1000;
}

void Connection::mark_zombie() {
    state_ = State::ZOMBIE;
    zombie_time_ = get_current_time_ms();
    LOGI("Connection %s marked as ZOMBIE, will close after 15 seconds", virtual_ip_.c_str());
}

bool Connection::is_zombie_expired() const {
    if (state_ != State::ZOMBIE) return false;
    int64_t now = get_current_time_ms();
    return (now - zombie_time_) > 15000;  // 15 second timeout
}

}  // namespace srtla
