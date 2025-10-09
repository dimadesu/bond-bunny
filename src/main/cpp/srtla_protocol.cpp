#include "include/srtla_protocol.h"
#include <cstring>
#include <chrono>
#include <arpa/inet.h>

namespace srtla {
namespace Protocol {

uint16_t get_packet_type(const uint8_t* data, size_t len) {
    if (len < 2) return 0;
    
    // Read first 2 bytes as packet type (big-endian)
    uint16_t type;
    std::memcpy(&type, data, 2);
    type = ntohs(type);
    
    // Check if it's an SRTLA packet (0x9xxx)
    if ((type & 0x9000) == 0x9000) {
        return type;
    }
    
    // Otherwise check if it's an SRT packet
    if (len < 4) return 0;
    uint32_t header;
    std::memcpy(&header, data, 4);
    header = ntohl(header);
    
    // Bit 31 indicates control packet in SRT
    if (header & 0x80000000) {
        uint16_t srt_type = (header >> 16) & 0x7FFF;
        if (srt_type == 2) return SRT_TYPE_ACK;
        if (srt_type == 3) return SRT_TYPE_NAK;
        return SRT_TYPE_CONTROL;
    }
    
    // Assume SRT data packet
    return SRT_TYPE_DATA;
}

bool is_srt_data_packet(const uint8_t* data, size_t len) {
    return get_packet_type(data, len) == SRT_TYPE_DATA;
}

bool is_srt_control_packet(const uint8_t* data, size_t len) {
    uint16_t type = get_packet_type(data, len);
    return type == SRT_TYPE_ACK || type == SRT_TYPE_NAK || type == SRT_TYPE_CONTROL;
}

uint32_t parse_srt_sequence(const uint8_t* data, size_t len) {
    if (len < 4) return 0;
    
    uint32_t seq;
    std::memcpy(&seq, data, 4);
    seq = ntohl(seq);
    
    // Mask off control bit
    return seq & 0x7FFFFFFF;
}

int parse_srt_ack(const uint8_t* data, size_t len, uint32_t& ack_seq) {
    if (len < 16) return -1;
    
    uint32_t seq;
    std::memcpy(&seq, data, 4);
    seq = ntohl(seq);
    ack_seq = seq & 0x7FFFFFFF;
    
    return 0;
}

int parse_srt_nak(const uint8_t* data, size_t len, uint32_t* nak_seqs, int max_seqs) {
    if (len < 16 || max_seqs < 1) return -1;
    
    // SRT NAK format: header (16 bytes) + sequence numbers (4 bytes each)
    // Each sequence can be:
    // - Individual: just the sequence number
    // - Range: if bit 31 is set, next number is end of range
    
    int count = 0;
    const uint32_t* ids = reinterpret_cast<const uint32_t*>(data);
    int num_ids = len / 4;
    
    // Start from index 4 (skip 16-byte header)
    for (int i = 4; i < num_ids && count < max_seqs; i++) {
        uint32_t id = ntohl(ids[i]);
        
        if (id & (1U << 31)) {
            // Range: bit 31 set means this is start of range
            id = id & 0x7FFFFFFF;  // Clear bit 31
            
            if (i + 1 < num_ids) {
                uint32_t last_id = ntohl(ids[i + 1]);
                // Add all sequences in range
                for (uint32_t lost = id; lost <= last_id && count < max_seqs; lost++) {
                    nak_seqs[count++] = lost;
                }
                i++;  // Skip next element (end of range)
            } else {
                // Malformed - range start without end
                nak_seqs[count++] = id;
            }
        } else {
            // Individual sequence
            nak_seqs[count++] = id;
        }
    }
    
    return count;
}


size_t create_reg1_packet(uint8_t* buffer, size_t buffer_size, const uint8_t* srtla_id) {
    if (buffer_size < 2 + SRTLA_ID_LEN) return 0;
    
    // Packet type (big-endian)
    uint16_t type = htons(SRTLA_TYPE_REG1);
    std::memcpy(buffer, &type, 2);
    
    // SRTLA ID (256 bytes)
    std::memcpy(buffer + 2, srtla_id, SRTLA_ID_LEN);
    
    return 2 + SRTLA_ID_LEN;
}

size_t create_reg2_packet(uint8_t* buffer, size_t buffer_size, const uint8_t* srtla_id) {
    if (buffer_size < 2 + SRTLA_ID_LEN) return 0;
    
    // Packet type (big-endian)
    uint16_t type = htons(SRTLA_TYPE_REG2);
    std::memcpy(buffer, &type, 2);
    
    // SRTLA ID (256 bytes)
    std::memcpy(buffer + 2, srtla_id, SRTLA_ID_LEN);
    
    return 2 + SRTLA_ID_LEN;
}

size_t create_keepalive_packet(uint8_t* buffer, size_t buffer_size) {
    if (buffer_size < 10) return 0;
    
    // Packet type (big-endian)
    uint16_t type = htons(SRTLA_TYPE_KEEPALIVE);
    std::memcpy(buffer, &type, 2);
    
    // Timestamp for RTT measurement (8 bytes)
    uint64_t timestamp = static_cast<uint64_t>(std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
    timestamp = htobe64(timestamp);
    std::memcpy(buffer + 2, &timestamp, 8);
    
    return 10;
}

size_t wrap_srt_packet(uint8_t* dest, size_t dest_size, 
                      const uint8_t* srt_packet, size_t srt_len,
                      uint32_t sequence) {
    (void)sequence;  // Unused in current implementation
    
    if (dest_size < srt_len) return 0;
    
    std::memcpy(dest, srt_packet, srt_len);
    return srt_len;
}

size_t create_srtla_data_packet(uint8_t* dest, size_t dest_size,
                               const uint8_t* srt_packet, size_t srt_len,
                               const std::string& virtual_ip,
                               uint32_t sequence) {
    // SRTLA data packet format:
    // [2 bytes: type] [4 bytes: virtual IP] [4 bytes: sequence] [SRT data...]
    
    size_t header_size = 2 + 4 + 4; // type + IP + sequence
    if (dest_size < header_size + srt_len) return 0;
    
    uint8_t* ptr = dest;
    
    // Packet type (big-endian)
    uint16_t type = htons(SRTLA_TYPE_DATA);
    std::memcpy(ptr, &type, 2);
    ptr += 2;
    
    // Virtual IP (convert string to 32-bit integer)
    struct in_addr addr;
    if (inet_pton(AF_INET, virtual_ip.c_str(), &addr) != 1) {
        return 0; // Invalid IP
    }
    std::memcpy(ptr, &addr.s_addr, 4); // Already in network byte order
    ptr += 4;
    
    // Sequence number (big-endian)
    uint32_t seq = htonl(sequence);
    std::memcpy(ptr, &seq, 4);
    ptr += 4;
    
    // SRT packet data
    std::memcpy(ptr, srt_packet, srt_len);
    
    return header_size + srt_len;
}

bool parse_srtla_data_packet(const uint8_t* data, size_t len,
                            std::string& virtual_ip,
                            uint32_t& sequence,
                            const uint8_t*& srt_data,
                            size_t& srt_len) {
    // SRTLA data packet format:
    // [2 bytes: type] [4 bytes: virtual IP] [4 bytes: sequence] [SRT data...]
    
    size_t header_size = 2 + 4 + 4;
    if (len < header_size) return false;
    
    const uint8_t* ptr = data;
    
    // Check packet type
    uint16_t type;
    std::memcpy(&type, ptr, 2);
    type = ntohs(type);
    if (type != SRTLA_TYPE_DATA) return false;
    ptr += 2;
    
    // Extract virtual IP
    struct in_addr addr;
    std::memcpy(&addr.s_addr, ptr, 4);
    char ip_str[INET_ADDRSTRLEN];
    if (inet_ntop(AF_INET, &addr, ip_str, sizeof(ip_str)) == nullptr) return false;
    virtual_ip = ip_str;
    ptr += 4;
    
    // Extract sequence number
    std::memcpy(&sequence, ptr, 4);
    sequence = ntohl(sequence);
    ptr += 4;
    
    // Extract SRT data
    srt_data = ptr;
    srt_len = len - header_size;
    
    return true;
}

bool is_reg3_packet(const uint8_t* data, size_t len) {
    return get_packet_type(data, len) == SRTLA_TYPE_REG3;
}

bool is_reg_error_packet(const uint8_t* data, size_t len) {
    return get_packet_type(data, len) == SRTLA_TYPE_REG_ERR;
}

bool is_keepalive_ack_packet(const uint8_t* data, size_t len) {
    return get_packet_type(data, len) == SRTLA_TYPE_KEEPALIVE;
}

} // namespace Protocol
} // namespace srtla
