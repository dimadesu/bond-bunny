# SRTLA Crash Fixes Applied

## Issue 1: NullPointerException in CleanSrtlaService.setupNetworkMonitoring()

**Problem**: The `activeRequests` ConcurrentHashMap was trying to accept a `null` key, which is not allowed.

**Root Cause**: Line 155 had `activeRequests.put(null, wifiBuilder)` which attempted to use `null` as a key in a ConcurrentHashMap.

**Fix Applied**:

1. Removed the problematic line that was causing the null key insertion
2. Removed the unused `activeRequests` field entirely since it wasn't serving any meaningful purpose
3. Simplified the network monitoring setup to focus only on tracking actual network connections

## Issue 2: Improved Local IP Detection

**Problem**: The original `getLocalIpForNetwork()` method was using hardcoded placeholder IP addresses, causing network binding failures.

**Root Cause**: Method returned static IPs like "192.168.1.100" instead of determining actual network interface IPs.

**Fix Applied**:

1. Implemented proper IP detection using Android's `LinkProperties` API
2. Added fallback method using socket binding to determine actual local IP
3. Added proper imports for networking classes (`LinkAddress`, `LinkProperties`, etc.)

## Files Modified:

- `/Users/dmytro.antonov/git/bond-bunny/src/main/java/com/example/srtla/CleanSrtlaService.java`
- `/Users/dmytro.antonov/git/bond-bunny/src/main/cpp/srtla_android_wrapper.cpp`

## Issue 3: Incorrect Network Binding in Native Code

**Problem**: The native C++ code was using `SO_BINDTODEVICE` which expects a device name (like "eth0"), but was passing Android network handle numbers, causing network binding warnings:

```
Warning: Could not bind to network handle -889270259 for 172.20.10.2
```

**Root Cause**: In `srtla_android_wrapper.cpp`, the code was incorrectly using:

```cpp
setsockopt(connection->fd, SOL_SOCKET, SO_BINDTODEVICE,
           &network_handle, sizeof(network_handle))
```

**Fix Applied**:

1. Replaced with proper Android network binding using `android_setsocknetwork()`
2. Added include for `<android/multinetwork.h>`
3. Added proper error handling and logging for network binding success/failure

```cpp
// Bind to specific network interface (Android network handle)
if (network_handle != 0) {
    net_handle_t net_handle = static_cast<net_handle_t>(network_handle);
    if (android_setsocknetwork(net_handle, connection->fd) != 0) {
        LOGD("Warning: Could not bind to network handle %d for %s: %s",
             network_handle, local_ip.c_str(), strerror(errno));
        // Continue anyway - socket can still work without network binding
    } else {
        LOGD("Successfully bound socket to network handle %d for %s",
             network_handle, local_ip.c_str());
    }
}
```

## Build Status:

✅ **Build Successful** - All fixes applied without compilation errors
✅ **APK Generated** - Debug APK ready for testing (8.5MB)
✅ **Lint Clean** - No new lint errors introduced

## Expected Results:

- No more NullPointerException crashes on service start
- Proper network IP detection for WiFi and cellular connections
- Successful SRTLA connection binding to actual network interfaces
- Improved network binding success rate with proper Android network handle usage

## Issue 4: Network Handle Data Type Truncation (RESOLVED)

**Problem**: Network handles were being truncated from 64-bit (`long`) to 32-bit (`int`), causing both WiFi and Cellular connections to receive the same invalid handle `-889270259`.

**Root Cause**:

- `network.getNetworkHandle()` returns a 64-bit `long` value
- Java code was casting to `(int)` before passing to native code
- JNI method signature expected `jint` instead of `jlong`
- This caused overflow and identical handles for different networks

**Log Evidence**:

```
Java: Added WiFi connection: 172.20.10.2 (handle=445787328525)
Java: Added Cellular connection: 192.0.0.2 (handle=432902426637)
Native: network_handle=-889270259 for 172.20.10.2  // Same for both!
Native: network_handle=-889270259 for 192.0.0.2   // Truncated!
```

**Fix Applied**:

1. **Updated Java interface** to accept `long` instead of `int`:

```java
public native boolean addConnection(long sessionPtr, String localIp, long networkHandle);
```

2. **Updated JNI signature** to use `jlong`:

```cpp
Java_com_example_srtla_SRTLANative_addConnection(JNIEnv *env, jobject thiz, jlong session_ptr,
                                                 jstring local_ip, jlong network_handle)
```

3. **Removed casting** in Java call:

```java
// Before: srtlaNative.addConnection(sessionPtr, localIp, (int) networkHandle);
// After:  srtlaNative.addConnection(sessionPtr, localIp, networkHandle);
```

4. **Updated C++ struct and logging** to use `long` and `%ld` format specifiers

## Issue 5: Virtual IP Implementation (ENHANCEMENT)

**Enhancement**: Added virtual IP support to improve network isolation and SRTLA protocol management.

**Benefits**:

- **Clean Separation**: Virtual IPs for SRTLA protocol (`10.0.1.1`, `10.0.2.1`) separate from real interface IPs
- **Network Isolation**: Each connection type gets predictable virtual address space
- **Better Debugging**: Clear distinction between protocol identifiers and actual network addresses
- **SRTLA Compatibility**: Consistent virtual IPs improve server-side connection tracking

**Implementation**:

1. **Enhanced Connection Structure**:

```cpp
struct srtla_android_connection {
    std::string virtual_ip;     // SRTLA protocol identifier (e.g. "10.0.1.1")
    std::string real_ip;        // Actual interface IP (e.g. "172.20.10.2")
    std::string network_type;   // "WiFi" or "Cellular"
    long network_handle;
    // ... other fields
};
```

2. **Virtual IP Assignment**:

```cpp
std::string generateVirtualIP(const std::string& network_type) {
    if (network_type == "WiFi") {
        return "10.0.1.1";
    } else if (network_type == "Cellular") {
        return "10.0.2.1";
    } else {
        return "10.0.9.1";  // Fallback for unknown types
    }
}
```

3. **Updated JNI Interface**:

```java
// Java interface now accepts network type
public native boolean addConnection(long sessionPtr, String realIp, long networkHandle, String networkType);

// Service passes network type from Android ConnectivityManager
boolean success = srtlaNative.addConnection(sessionPtr, localIp, networkHandle, networkType);
```

4. **Dual IP Binding Strategy**:

- **Virtual IP**: Used for SRTLA protocol identification and server communication
- **Real IP**: Used for actual socket binding to specific network interfaces
- **Network Handle**: Ensures traffic routes through correct Android network interface

**Expected Results**:

- ✅ **Predictable Virtual IPs**: WiFi=`10.0.1.1`, Cellular=`10.0.2.1`
- ✅ **Real Network Binding**: Sockets still bind to actual interface IPs (172.20.10.2, 192.0.0.2)
- ✅ **Improved Logging**: Clear virtual vs real IP distinction in debug output
- ✅ **Better SRTLA Server Compatibility**: Consistent virtual IP addresses for connection tracking

## Testing Recommendation:

The fixed APK should now start the CleanSrtlaService without crashing and properly detect real local IP addresses for network binding.
