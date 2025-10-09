#ifndef SRTLA_IP_MANAGER_H
#define SRTLA_IP_MANAGER_H

#include <string>
#include <set>
#include <mutex>

namespace srtla {

class VirtualIPManager {
public:
    VirtualIPManager();
    
    // Get next available virtual IP
    std::string allocate_ip();
    
    // Release a virtual IP back to the pool
    void release_ip(const std::string& ip);
    
    // Check if IP is valid and available
    bool is_available(const std::string& ip) const;

private:
    static constexpr const char* BASE_IP = "10.0.0.";  // We'll use 10.0.0.x range
    static constexpr int MIN_IP = 2;  // Start from .2 (leave .1 for gateway)
    static constexpr int MAX_IP = 254;  // End at .254 (leave .255 for broadcast)
    
    std::set<int> used_ips_;
    mutable std::mutex mutex_;
    
    // Convert between string IP and integer
    static std::string int_to_ip(int ip_num);
    static int ip_to_int(const std::string& ip);
};

} // namespace srtla

#endif // SRTLA_IP_MANAGER_H
