package com.example.srtla;

/**
 * SRTLA Protocol Constants and Packet Types
 * Based on the original SRTLA implementation
 */
public class SrtlaProtocol {
    
    // SRTLA packet types (matches common.h)
    public static final int SRTLA_TYPE_KEEPALIVE = 0x9000;
    public static final int SRTLA_TYPE_ACK = 0x9100;
    public static final int SRTLA_TYPE_REG1 = 0x9200;
    public static final int SRTLA_TYPE_REG2 = 0x9201;
    public static final int SRTLA_TYPE_REG3 = 0x9202;
    public static final int SRTLA_TYPE_REG_ERR = 0x9210;
    public static final int SRTLA_TYPE_REG_NGP = 0x9211;
    public static final int SRTLA_TYPE_REG_NAK = 0x9212;
    
    // SRT packet types (matches common.h)
    public static final int SRT_TYPE_HANDSHAKE = 0x8000;
    public static final int SRT_TYPE_ACK = 0x8002;
    public static final int SRT_TYPE_NAK = 0x8003;
    public static final int SRT_TYPE_SHUTDOWN = 0x8005;
    public static final int SRT_TYPE_DATA = 0x0000;
    public static final int SRT_MIN_LEN = 16;
    
    // Packet sizes (matches common.h)
    public static final int SRTLA_ID_LEN = 256;
    public static final int SRTLA_TYPE_REG1_LEN = 2 + SRTLA_ID_LEN;
    public static final int SRTLA_TYPE_REG2_LEN = 2 + SRTLA_ID_LEN;
    public static final int SRTLA_TYPE_REG3_LEN = 2;
    public static final int MTU = 1500;
    
    // Timeouts (in seconds)
    public static final int CONN_TIMEOUT = 4;
    public static final int REG2_TIMEOUT = 4;
    public static final int REG3_TIMEOUT = 4;
    public static final int GLOBAL_TIMEOUT = 10;
    public static final int IDLE_TIME = 1;
    
    // Window management
    public static final int WINDOW_MIN = 1;
    public static final int WINDOW_DEF = 20;
    public static final int WINDOW_MAX = 60;
    public static final int WINDOW_MULT = 1000;
    public static final int WINDOW_DECR = 100;
    public static final int WINDOW_INCR = 30;
    
    // Packet tracking
    public static final int PKT_LOG_SIZE = 256;
    
    /**
     * Extract packet type from buffer
     */
    public static int getPacketType(byte[] buffer, int length) {
        if (length < 2) return -1;
        
        return ((buffer[0] & 0xFF) << 8) | (buffer[1] & 0xFF);
    }
    
    /**
     * Extract SRT sequence number from packet
     * Based on the original get_srt_sn function in common.c
     */
    public static int getSrtSequenceNumber(byte[] buffer, int length) {
        if (length < 4) return -1;
        
        // Read 32-bit big-endian value from first 4 bytes (exactly like get_srt_sn)
        int sn = ((buffer[0] & 0xFF) << 24) |
                 ((buffer[1] & 0xFF) << 16) |
                 ((buffer[2] & 0xFF) << 8) |
                 (buffer[3] & 0xFF);
        
        // Check if bit 31 is NOT set (data packet vs control packet)
        if ((sn & 0x80000000) == 0) {
            return sn;
        }
        
        return -1; // Control packet, no sequence number
    }
    
    /**
     * Create SRTLA REG1 packet
     */
    public static byte[] createReg1Packet(byte[] srtlaId) {
        byte[] packet = new byte[SRTLA_TYPE_REG1_LEN];
        
        // Packet type (big-endian)
        packet[0] = (byte) ((SRTLA_TYPE_REG1 >> 8) & 0xFF);
        packet[1] = (byte) (SRTLA_TYPE_REG1 & 0xFF);
        
        // SRTLA ID
        System.arraycopy(srtlaId, 0, packet, 2, SRTLA_ID_LEN);
        
        return packet;
    }
    
    /**
     * Create SRTLA REG2 packet
     */
    public static byte[] createReg2Packet(byte[] srtlaId) {
        byte[] packet = new byte[SRTLA_TYPE_REG2_LEN];
        
        // Packet type (big-endian)
        packet[0] = (byte) ((SRTLA_TYPE_REG2 >> 8) & 0xFF);
        packet[1] = (byte) (SRTLA_TYPE_REG2 & 0xFF);
        
        // SRTLA ID
        System.arraycopy(srtlaId, 0, packet, 2, SRTLA_ID_LEN);
        
        return packet;
    }
    
    /**
     * Create SRTLA keepalive packet (Swift SRTLA style with timestamp for RTT measurement)
     */
    public static byte[] createKeepalivePacket() {
        byte[] packet = new byte[10]; // 2 bytes type + 8 bytes timestamp
        
        packet[0] = (byte) ((SRTLA_TYPE_KEEPALIVE >> 8) & 0xFF);
        packet[1] = (byte) (SRTLA_TYPE_KEEPALIVE & 0xFF);
        
        // Embed current timestamp for RTT measurement (like Swift SRTLA)
        long timestamp = System.currentTimeMillis();
        for (int i = 0; i < 8; i++) {
            packet[2 + i] = (byte) ((timestamp >> (56 - i * 8)) & 0xFF);
        }
        
        return packet;
    }
    
    /**
     * Extract timestamp from SRTLA keepalive packet
     */
    public static long extractKeepaliveTimestamp(byte[] buffer, int length) {
        if (length < 10 || getPacketType(buffer, length) != SRTLA_TYPE_KEEPALIVE) {
            return -1;
        }
        
        long timestamp = 0;
        for (int i = 0; i < 8; i++) {
            timestamp = (timestamp << 8) | (buffer[2 + i] & 0xFF);
        }
        
        return timestamp;
    }
    
    /**
     * Create SRTLA ACK packet
     */
    public static byte[] createAckPacket(int[] ackList) {
        byte[] packet = new byte[2 + (ackList.length * 4)];
        
        // Packet type (big-endian)
        packet[0] = (byte) ((SRTLA_TYPE_ACK >> 8) & 0xFF);
        packet[1] = (byte) (SRTLA_TYPE_ACK & 0xFF);
        
        // ACK list (big-endian 32-bit integers)
        for (int i = 0; i < ackList.length; i++) {
            int offset = 2 + (i * 4);
            int ack = ackList[i];
            
            packet[offset] = (byte) ((ack >> 24) & 0xFF);
            packet[offset + 1] = (byte) ((ack >> 16) & 0xFF);
            packet[offset + 2] = (byte) ((ack >> 8) & 0xFF);
            packet[offset + 3] = (byte) (ack & 0xFF);
        }
        
        return packet;
    }
    
    /**
     * Parse SRT ACK packet to extract acknowledged sequence number
     */
    public static int parseSrtAck(byte[] buffer, int length) {
        if (length < 20) return -1;
        
        int packetType = getPacketType(buffer, length);
        if (packetType != SRT_TYPE_ACK) return -1;
        
        // Extract ACK number from bytes 16-19 (big-endian)
        return ((buffer[16] & 0xFF) << 24) |
               ((buffer[17] & 0xFF) << 16) |
               ((buffer[18] & 0xFF) << 8) |
               (buffer[19] & 0xFF);
    }
    
    /**
     * Parse SRT NAK packet to extract lost sequence numbers
     */
    public static int[] parseSrtNak(byte[] buffer, int length) {
        if (length < 8) return new int[0];
        
        int packetType = getPacketType(buffer, length);
        if (packetType != SRT_TYPE_NAK) return new int[0];
        
        java.util.List<Integer> nakList = new java.util.ArrayList<>();
        
        // Start from byte 4, read 32-bit integers
        for (int i = 4; i < length; i += 4) {
            if (i + 3 >= length) break;
            
            int nakId = ((buffer[i] & 0xFF) << 24) |
                       ((buffer[i + 1] & 0xFF) << 16) |
                       ((buffer[i + 2] & 0xFF) << 8) |
                       (buffer[i + 3] & 0xFF);
            
            // Check if this is a range NAK (bit 31 set)
            if ((nakId & 0x80000000) != 0) {
                // Range NAK - next int is the end of range
                nakId = nakId & 0x7FFFFFFF;
                i += 4;
                if (i + 3 >= length) break;
                
                int endId = ((buffer[i] & 0xFF) << 24) |
                           ((buffer[i + 1] & 0xFF) << 16) |
                           ((buffer[i + 2] & 0xFF) << 8) |
                           (buffer[i + 3] & 0xFF);
                
                // Add all sequence numbers in the range
                for (int seq = nakId; seq <= endId && nakList.size() < 1000; seq++) {
                    nakList.add(seq);
                }
            } else {
                // Single NAK
                nakList.add(nakId);
            }
        }
        
        return nakList.stream().mapToInt(Integer::intValue).toArray();
    }
    
    /**
     * Check if packet is SRTLA REG1 (matches is_srtla_reg1 in common.c)
     */
    public static boolean isSrtlaReg1(byte[] buffer, int length) {
        if (length != SRTLA_TYPE_REG1_LEN) return false;
        return getPacketType(buffer, length) == SRTLA_TYPE_REG1;
    }
    
    /**
     * Check if packet is SRTLA REG2 (matches is_srtla_reg2 in common.c)  
     */
    public static boolean isSrtlaReg2(byte[] buffer, int length) {
        if (length != SRTLA_TYPE_REG2_LEN) return false;
        return getPacketType(buffer, length) == SRTLA_TYPE_REG2;
    }
    
    /**
     * Check if packet is SRTLA REG3 (matches is_srtla_reg3 in common.c)
     */
    public static boolean isSrtlaReg3(byte[] buffer, int length) {
        if (length != SRTLA_TYPE_REG3_LEN) return false;
        return getPacketType(buffer, length) == SRTLA_TYPE_REG3;
    }
    
    /**
     * Check if packet is SRTLA keepalive (matches is_srtla_keepalive in common.c)
     */
    public static boolean isSrtlaKeepalive(byte[] buffer, int length) {
        return getPacketType(buffer, length) == SRTLA_TYPE_KEEPALIVE;
    }
    
    /**
     * Check if packet is SRT ACK (matches is_srt_ack in common.c)
     */
    public static boolean isSrtAck(byte[] buffer, int length) {
        return getPacketType(buffer, length) == SRT_TYPE_ACK;
    }
    
    /**
     * Check if a packet is a SRT NAK packet
     */
    public static boolean isNakPacket(byte[] data, int dataSize) {
        if (dataSize < 16) return false; // Minimum SRT packet size
        // Check for SRT NAK type in packet header
        int type = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) | 
                   ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        return (type & 0x8000) != 0 && ((type & 0x7FFF) == 3); // SRT_TYPE_NAK
    }
    
    /**
     * Check if a packet is a SRT ACK packet
     */
    public static boolean isAckPacket(byte[] data, int dataSize) {
        if (dataSize < 16) return false; // Minimum SRT packet size
        // Check for SRT ACK type in packet header
        int type = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16) | 
                   ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
        return (type & 0x8000) != 0 && ((type & 0x7FFF) == 2); // SRT_TYPE_ACK
    }
    
    /**
     * Extract ACK sequence number from SRT ACK packet
     */
    public static int getAckSequenceNumber(byte[] data, int dataSize) {
        if (dataSize < 16 || !isAckPacket(data, dataSize)) return -1;
        // ACK sequence number is at offset 8-11
        return ((data[8] & 0xFF) << 24) | ((data[9] & 0xFF) << 16) | 
               ((data[10] & 0xFF) << 8) | (data[11] & 0xFF);
    }
    
    /**
     * Extract NAK sequence number from SRT NAK packet
     */
    public static int getNakSequenceNumber(byte[] data, int dataSize) {
        if (dataSize < 16 || !isNakPacket(data, dataSize)) return -1;
        // NAK sequence number is at offset 8-11
        return ((data[8] & 0xFF) << 24) | ((data[9] & 0xFF) << 16) | 
               ((data[10] & 0xFF) << 8) | (data[11] & 0xFF);
    }
}
