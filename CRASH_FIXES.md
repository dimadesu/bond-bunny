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

## Testing Recommendation:

The fixed APK should now start the CleanSrtlaService without crashing and properly detect real local IP addresses for network binding.
