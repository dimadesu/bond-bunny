#include "include/srtla_ip_manager.h"
#include <stdexcept>
#include <sstream>

namespace srtla {

VirtualIPManager::VirtualIPManager() {}

std::string VirtualIPManager::allocate_ip() {
    std::lock_guard<std::mutex> lock(mutex_);
    
    // Find first available IP
    for (int i = MIN_IP; i <= MAX_IP; i++) {
        if (used_ips_.find(i) == used_ips_.end()) {
            used_ips_.insert(i);
            return int_to_ip(i);
        }
    }
    
    // Return empty string if no IPs available (instead of throwing exception)
    return "";
}

void VirtualIPManager::release_ip(const std::string& ip) {
    std::lock_guard<std::mutex> lock(mutex_);
    int ip_num = ip_to_int(ip);
    if (ip_num >= MIN_IP && ip_num <= MAX_IP) {
        used_ips_.erase(ip_num);
    }
}

bool VirtualIPManager::is_available(const std::string& ip) const {
    std::lock_guard<std::mutex> lock(mutex_);
    int ip_num = ip_to_int(ip);
    return ip_num >= MIN_IP && ip_num <= MAX_IP && 
           used_ips_.find(ip_num) == used_ips_.end();
}

std::string VirtualIPManager::int_to_ip(int ip_num) {
    std::stringstream ss;
    ss << BASE_IP << ip_num;
    return ss.str();
}

int VirtualIPManager::ip_to_int(const std::string& ip) {
    size_t pos = ip.rfind('.');
    if (pos == std::string::npos) return -1;
    try {
        return std::stoi(ip.substr(pos + 1));
    } catch (...) {
        return -1;
    }
}

} // namespace srtla
