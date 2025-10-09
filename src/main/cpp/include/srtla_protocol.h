#ifndef SRTLA_PROTOCOL_H
#define SRTLA_PROTOCOL_H

#include <cstdint>
#include <cstddef>
#include <string>

namespace srtla {

/**
 * SRTLA protocol constants and packet parsing
 * Based on the SRTLA protocol specification
 */
namespace Protocol {

// Packet types (matches actual SRTLA protocol)
constexpr uint16_t SRTLA_TYPE_KEEPALIVE = 0x9000;
constexpr uint16_t SRTLA_TYPE_ACK = 0x9100;
constexpr uint16_t SRTLA_TYPE_REG1 = 0x9200;
constexpr uint16_t SRTLA_TYPE_REG2 = 0x9201;
constexpr uint16_t SRTLA_TYPE_REG3 = 0x9202;
constexpr uint16_t SRTLA_TYPE_REG_ERR = 0x9210;
constexpr uint16_t SRTLA_TYPE_REG_NGP = 0x9211;
constexpr uint16_t SRTLA_TYPE_DATA = 0x9300;  // Data packet with virtual IP

// SRT packet types
constexpr uint16_t SRT_TYPE_DATA = 0x8000;  // Data packet (bit 15 set)
constexpr uint16_t SRT_TYPE_CONTROL = 0x0000;
constexpr uint16_t SRT_TYPE_ACK = 0x0002;
constexpr uint16_t SRT_TYPE_NAK = 0x0003;
constexpr uint16_t SRT_TYPE_SHUTDOWN = 0x0005;

// Protocol constants
constexpr int SRTLA_ID_LEN = 256;  // Actual SRTLA uses 256-byte IDs
constexpr int REG_TIMEOUT_SEC = 5;
constexpr int KEEPALIVE_INTERVAL_MS = 200;

// Window constants
constexpr int WINDOW_DEF = 10;
constexpr int WINDOW_MULT = 1000;
constexpr int WINDOW_MIN = 5;
constexpr int WINDOW_MAX = 100;

// Packet parsing
uint16_t get_packet_type(const uint8_t* data, size_t len);
bool is_srt_data_packet(const uint8_t* data, size_t len);
bool is_srt_control_packet(const uint8_t* data, size_t len);
uint32_t parse_srt_sequence(const uint8_t* data, size_t len);
int parse_srt_ack(const uint8_t* data, size_t len, uint32_t& ack_seq);
int parse_srt_nak(const uint8_t* data, size_t len, uint32_t* nak_seqs, int max_seqs);

// Packet creation
size_t create_reg1_packet(uint8_t* buffer, size_t buffer_size, const uint8_t* srtla_id);
size_t create_reg2_packet(uint8_t* buffer, size_t buffer_size, const uint8_t* srtla_id);
size_t create_keepalive_packet(uint8_t* buffer, size_t buffer_size);

// SRTLA packet wrapping
size_t wrap_srt_packet(uint8_t* dest, size_t dest_size, 
                       const uint8_t* srt_packet, size_t srt_len,
                       uint32_t sequence);

// Enhanced SRTLA packet creation with virtual IP
size_t create_srtla_data_packet(uint8_t* dest, size_t dest_size,
                               const uint8_t* srt_packet, size_t srt_len,
                               const std::string& virtual_ip,
                               uint32_t sequence);

// Parse SRTLA data packet to extract virtual IP and SRT data
bool parse_srtla_data_packet(const uint8_t* data, size_t len,
                            std::string& virtual_ip,
                            uint32_t& sequence,
                            const uint8_t*& srt_data,
                            size_t& srt_len);

// Registration packet parsing
bool is_reg3_packet(const uint8_t* data, size_t len);
bool is_reg_error_packet(const uint8_t* data, size_t len);
bool is_keepalive_ack_packet(const uint8_t* data, size_t len);

} // namespace Protocol
} // namespace srtla

#endif // SRTLA_PROTOCOL_H
