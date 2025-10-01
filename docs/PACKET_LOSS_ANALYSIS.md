# Packet Loss Analysis: Multiple Connections vs Single Connection

## Executive Summary

The Bond Bunny app experiences higher packet loss when using multiple connections (Wi-Fi + mobile data) simultaneously compared to using a single connection. This analysis identifies **five critical architectural issues** that contribute to this problem.

---

## Background

Bond Bunny implements the SRTLA (SRT Link Aggregation) protocol to bond multiple network connections for video streaming. The app:
- Listens for SRT packets on a local port
- Forwards packets through multiple connections (WiFi, Cellular, Ethernet)
- Uses dynamic connection selection based on quality scoring
- Implements congestion control via window management

---

## Root Causes of Increased Packet Loss

### 1. **NAK Attribution Problem** ⚠️ CRITICAL

**Issue:**
When using multiple connections, NAK (Negative Acknowledgment) packets - which indicate lost packets - can be received on a different connection than the one that sent the original packet. The code attempts to fix this but has limitations.

**Location:** `EnhancedSrtlaService.java` lines 667-706

```java
case SrtlaProtocol.SRT_TYPE_NAK:
    // Handle SRT NAK - CRITICAL FIX: Find the connection that sent the lost packet
    int[] nakSeqs = SrtlaProtocol.parseSrtNak(data, length);
    
    for (int nakSeq : nakSeqs) {
        SrtlaConnection senderConnection = findConnectionForSequence(nakSeq);
        if (senderConnection != null) {
            senderConnection.handleNak(nakSeq);
            correctAttributions++;
        } else {
            // Fallback: if we can't find the sender, apply to the receiver
            connection.handleNak(nakSeq);
            fallbackAttributions++;
        }
    }
```

**Problems:**

1. **Limited Tracking Window**: The sequence-to-connection mapping is limited to 10,000 entries (`MAX_SEQUENCE_TRACKING = 10000`). With high bitrate streams and multiple connections, this can be insufficient.

2. **Memory Management Issues**: When the tracking map exceeds 10,000 entries, 20% of entries are removed using an iterator, which:
   - Doesn't necessarily remove the oldest entries
   - Can remove mappings for packets still in flight
   - Creates gaps in tracking

3. **Fallback Attribution**: When mapping is lost, NAKs are applied to the receiving connection instead of the sender, causing:
   - Wrong connection's congestion window to be reduced
   - Good connections being penalized
   - Bad connections not being throttled

**Impact:** With a single connection, NAKs are always correctly attributed. With multiple connections, misattribution causes good connections to be unnecessarily throttled while problematic connections continue sending at high rates.

---

### 2. **Connection Switching Overhead**

**Issue:**
Dynamic connection switching creates packet reordering and timing issues.

**Location:** `EnhancedSrtlaService.java` lines 567-645

```java
private SrtlaConnection selectConnectionWeighted() {
    // 90% of the time: use best connection
    // 10% of the time: explore other connections
    boolean shouldExplore = explorationEnabled && (currentTime / 5000) % 10 == 0;
    
    if (shouldExplore && activeConnections.size() > 1) {
        // Switch to second-best connection for exploration
        return secondBest;
    }
}
```

**Problems:**

1. **Exploration Phase**: Every 5 seconds, the app switches to a second-best connection for ~500ms to "explore" its quality. This causes:
   - Forced packet reordering (packets sent on different paths arrive out of order)
   - Temporary increased latency during the switch
   - NAKs due to the switching itself

2. **Path Characteristics Mismatch**: Different connections have different:
   - RTT (Round Trip Time) 
   - Jitter
   - Packet reordering characteristics
   - When switching between them, the receiver may request retransmissions because packets arrive in unexpected order

3. **Stickiness Timing**: The minimum switch interval is 500ms (`MIN_SWITCH_INTERVAL_MS = 500`), which may still be too frequent for stable streaming.

**Impact:** Single connection maintains consistent packet ordering. Multiple connections with switching introduce reordering that triggers retransmission requests.

---

### 3. **Conservative Congestion Control Tuning**

**Issue:**
The congestion control algorithm is overly conservative when multiple connections compete.

**Location:** `SrtlaConnection.java` lines 85-93

```java
// DIALED DOWN for slower recovery
private static final long NORMAL_MIN_WAIT_TIME = 2000;             // Was 1000ms - now wait longer
private static final long FAST_MIN_WAIT_TIME = 500;                // Was 200ms - now wait longer
private static final long NORMAL_INCREMENT_WAIT = 1000;            // Was 500ms - now wait longer between increases
private static final long FAST_INCREMENT_WAIT = 300;               // Was 100ms - now wait longer
```

**Problems:**

1. **Slow Recovery After Misattributed NAKs**: When a connection gets incorrectly penalized due to NAK misattribution (Issue #1), it takes 2+ seconds before it can start recovering its window size.

2. **Window Reduction Impact**: 
   - Each NAK reduces window by 100 bytes (`WINDOW_DECR = 100`)
   - Minimum window is 1000 bytes (`WINDOW_MIN = 1`)
   - If a connection receives 10 misattributed NAKs, its window drops by 1000 bytes
   - It then takes multiple seconds to recover

3. **Compound Effect**: With multiple connections:
   - Connection A sends a packet that gets lost
   - NAK is misattributed to Connection B
   - Connection B's window shrinks
   - Connection A continues sending at high rate (no penalty)
   - Overall throughput decreases while packet loss doesn't

**Location:** `SrtlaConnection.java` lines 230-252

```java
public void handleNak(int sequenceNumber) {
    // Decrease window (congestion response)
    int oldWindow = window;
    window -= SrtlaProtocol.WINDOW_DECR;  // Reduce by 100
    window = Math.max(window, SrtlaProtocol.WINDOW_MIN * SrtlaProtocol.WINDOW_MULT);
    
    // Track lowest window and enable fast recovery if window gets very small
    lowestWindow = Math.min(lowestWindow, window);
    
    // Enable fast recovery mode if window drops below 2000
    if (window <= 2000 && !fastRecoveryMode) {
        fastRecoveryMode = true;
    }
}
```

**Impact:** Single connection's congestion control adapts correctly to network conditions. Multiple connections with misattributed NAKs enter a death spiral where good connections are throttled while bad ones aren't.

---

### 4. **Sequence Tracking Memory Management**

**Issue:**
The sequence tracking implementation has flawed memory management.

**Location:** `EnhancedSrtlaService.java` lines 744-762

```java
private void trackSequence(int sequenceNumber, SrtlaConnection connection) {
    if (sequenceNumber < 0) return;
    
    // Limit memory usage by removing old entries
    if (sequenceToConnectionMap.size() > MAX_SEQUENCE_TRACKING) {
        // Remove approximately 20% of entries
        Iterator<Map.Entry<Integer, SrtlaConnection>> it = sequenceToConnectionMap.entrySet().iterator();
        int removeCount = MAX_SEQUENCE_TRACKING / 5;
        while (it.hasNext() && removeCount > 0) {
            it.next();
            it.remove();  // ❌ Removes arbitrary entries, not oldest
            removeCount--;
        }
    }
    
    sequenceToConnectionMap.put(sequenceNumber, connection);
}
```

**Problems:**

1. **Non-LRU Eviction**: The code uses `ConcurrentHashMap` and removes entries via iterator order, which is NOT FIFO. This means:
   - Recent sequence numbers might be removed before old ones
   - Critical tracking data is lost unpredictably

2. **Wrong Data Structure**: `ConcurrentHashMap` doesn't maintain insertion order. Should use:
   - `LinkedHashMap` with access-order tracking
   - A proper LRU cache implementation
   - Or a circular buffer with sequence number indexing

3. **Timing Issue**: Entries are removed when the map size exceeds 10,000, but with high bitrate:
   - 4 Mbps stream ≈ 350 packets/second
   - With 2 connections ≈ 700 packets/second
   - 10,000 entries filled in ~14 seconds
   - But SRT can request retransmissions for packets up to 30+ seconds old

4. **No Cleanup**: Old entries are only removed when hitting the size limit, not based on age or when packets are acknowledged.

**Impact:** Critical sequence tracking information is lost, leading to increased NAK misattribution over time, especially during high bitrate streaming.

---

### 5. **Packet Reordering Amplification**

**Issue:**
Multiple connections naturally have different latency characteristics, causing packet reordering that looks like packet loss to SRT.

**Technical Details:**

When packets are sent through different paths:

```
WiFi Path:       Packet 100 → [RTT: 40ms]  → Arrives at 0ms
Cellular Path:   Packet 101 → [RTT: 150ms] → Arrives at 110ms
WiFi Path:       Packet 102 → [RTT: 40ms]  → Arrives at 40ms
```

Arrival order: 100, 102, 101 ❌ Out of order!

SRT sees packet 102 before 101 and may issue a NAK for 101, even though it's still in transit, not lost.

**Location:** This is inherent in the multi-path design but exacerbated by the connection selection algorithm.

**Problems:**

1. **No Reordering Buffer**: The code doesn't account for different RTTs between connections when selecting them.

2. **Jitter Ignorance**: Connection scoring doesn't consider:
   - Path RTT differences
   - Historical jitter
   - Reordering probability

3. **Aggressive Selection**: The weighted selection switches connections based on window size alone, not considering whether mixing paths will cause reordering.

**Location:** `EnhancedSrtlaService.java` lines 415-473

```java
private int calculateConnectionScore(SrtlaConnection connection) {
    // Base score calculation (same for all modes)
    int window = connection.getWindow();
    int inFlight = connection.getInFlightPackets();
    
    // Swift SRTLA pure score: window / (inFlightPackets + 1)
    int score = window / (inFlight + 1);
    
    // Apply quality scoring (NAK-based penalty)
    if (qualityScoringEnabled) {
        int nakCount = connection.getNakCount();
        long timeSinceLastNak = connection.getTimeSinceLastNak();
        
        // More aggressive NAK penalty (exponential)
        if (nakCount > 0) {
            double nakPenalty = Math.pow(0.5, Math.min(nakCount / 3.0, 5));
            score = (int)(score * nakPenalty);
        }
    }
    
    // ❌ No RTT or jitter consideration
    // ❌ No reordering probability calculation
    
    return Math.max(score, 1);
}
```

**Impact:** With a single connection, packet ordering is naturally maintained. With multiple connections, packets arrive out of order, triggering unnecessary retransmission requests that congest both the uplink and downlink.

---

## Why Single Connection Works Better

With a single connection:

1. ✅ **Perfect NAK Attribution**: NAKs are always for packets sent on that connection
2. ✅ **No Switching Overhead**: No exploration phases or connection switching
3. ✅ **Consistent Ordering**: Packets maintain send order
4. ✅ **Simple Congestion Control**: Window management directly reflects network conditions
5. ✅ **No Tracking Overhead**: No need for sequence-to-connection mapping

---

## Quantitative Impact Estimation

### Scenario: 4 Mbps video stream, WiFi (50ms RTT) + LTE (120ms RTT)

**Single Connection (WiFi only):**
- Packet loss: ~0.1% (network baseline)
- All NAKs correctly attributed
- Packets arrive in order

**Multiple Connections (Current Implementation):**
- Base packet loss: ~0.1% per connection = 0.2% combined
- NAK misattribution: ~20-30% (based on tracking window exhaustion)
  - Extra 0.05-0.08% packet loss from misattributed throttling
- Reordering NAKs: RTT difference causes ~10% of packets to appear out of order
  - Extra ~0.4% retransmission requests
- Switching overhead: 10% exploration time adds ~0.05% reordering
- **Total effective packet loss: 0.7-0.9%** (7-9x worse)

---

## Recommendations for Improvement

### High Priority Fixes

1. **Replace Sequence Tracking with LRU Cache**
   ```java
   // Use LinkedHashMap with access-order
   private final Map<Integer, SrtlaConnection> sequenceToConnectionMap = 
       Collections.synchronizedMap(new LinkedHashMap<Integer, SrtlaConnection>(
           10000, 0.75f, false) {
           protected boolean removeEldestEntry(Map.Entry eldest) {
               return size() > MAX_SEQUENCE_TRACKING;
           }
       });
   ```

2. **Increase Tracking Window Based on Bitrate**
   ```java
   // Calculate based on bitrate and expected latency
   int MAX_SEQUENCE_TRACKING = (estimatedBitrate / avgPacketSize) * maxExpectedLatency;
   // For 4Mbps, 1500 byte packets, 30s latency: 4,000,000 / 1500 * 30 = ~80,000
   ```

3. **Add RTT-Aware Connection Selection**
   ```java
   private int calculateConnectionScore(SrtlaConnection connection) {
       int baseScore = window / (inFlight + 1);
       
       // Penalize connections with very different RTT from current selection
       if (lastSelectedConnection != null) {
           double rttDifference = Math.abs(
               connection.getEstimatedRtt() - lastSelectedConnection.getEstimatedRtt()
           );
           if (rttDifference > 50) { // >50ms difference
               baseScore = (int)(baseScore * 0.7); // 30% penalty
           }
       }
       
       return baseScore;
   }
   ```

4. **Reduce Exploration Frequency**
   ```java
   // Change from 10% (5s cycle with 0.5s exploration) to 2% (once per 50 seconds)
   boolean shouldExplore = explorationEnabled && (currentTime / 50000) % 50 == 1;
   ```

5. **Implement Packet Age Tracking**
   ```java
   // Store timestamp with sequence mapping
   private final Map<Integer, PacketTrackingInfo> sequenceToConnectionMap = ...;
   
   class PacketTrackingInfo {
       SrtlaConnection connection;
       long timestamp;
       
       boolean isExpired() {
           return System.currentTimeMillis() - timestamp > 30000; // 30 seconds
       }
   }
   
   // Periodic cleanup in housekeeping
   sequenceToConnectionMap.entrySet().removeIf(
       entry -> entry.getValue().isExpired()
   );
   ```

### Medium Priority Improvements

6. **Add Jitter Tracking**
   - Track per-connection RTT variance
   - Penalize high-jitter connections in scoring
   - Avoid mixing high-jitter with low-jitter paths

7. **Implement Connection "Quality Classes"**
   - Group connections by RTT (e.g., <50ms, 50-100ms, >100ms)
   - Prefer using connections within same quality class
   - Only mix classes when capacity demands it

8. **Add Reordering Detection**
   - Track out-of-order packet arrival rates
   - Temporarily disable multi-connection when reordering rate exceeds threshold

### Low Priority Enhancements

9. **Adaptive Stickiness**
   - Increase stickiness time when NAK rate is high
   - Decrease when all connections are stable

10. **Per-Connection NAK Rate Monitoring**
    - Track actual vs misattributed NAKs
    - Adjust MAX_SEQUENCE_TRACKING dynamically
    - Alert when misattribution rate exceeds 10%

---

## Testing Recommendations

To validate these issues and fixes:

1. **Enable Detailed Logging**
   - Track NAK attribution accuracy
   - Monitor sequence map size over time
   - Log connection switches and resulting NAK rates

2. **Test Scenarios**
   - High bitrate (5+ Mbps) for extended duration (10+ minutes)
   - Connections with very different RTT (WiFi 30ms + LTE 200ms)
   - Deliberately introduce packet loss on one connection
   - Monitor which connection gets throttled

3. **Metrics to Collect**
   - NAK attribution accuracy percentage
   - Sequence map utilization over time
   - Per-connection NAK rate vs sent packet rate
   - Actual packet loss vs retransmission requests

---

## Conclusion

The increased packet loss when using multiple connections is primarily caused by:

1. **NAK misattribution** due to limited and poorly managed sequence tracking
2. **Connection switching overhead** causing unnecessary packet reordering
3. **Conservative congestion control** that compounds misattribution effects
4. **No consideration for path RTT differences** in connection selection
5. **Packet reordering** from mixed-latency paths appearing as loss

The single-connection mode avoids all these issues by maintaining perfect attribution, consistent ordering, and straightforward congestion control.

The recommended fixes focus on improving sequence tracking, reducing unnecessary switching, and making the connection selection algorithm aware of path characteristics to minimize reordering-induced "packet loss."

---

**Analysis Date:** October 1, 2025  
**Analyzed by:** AI Code Analysis  
**App Version:** Based on main branch source code
