#ifndef SRTLA_ANDROID_WRAPPER_H
#define SRTLA_ANDROID_WRAPPER_H

#include <jni.h>
#include <string>
#include <vector>
#include <memory>

// Forward declarations to avoid including SRTLA headers in public interface
struct srtla_android_connection;
struct srtla_android_session;

/**
 * Android wrapper for SRTLA functionality
 * Provides a clean C++ interface that can be called from JNI
 */
class SRTLAAndroidWrapper {
public:
    SRTLAAndroidWrapper();
    ~SRTLAAndroidWrapper();

    // Session management
    bool initialize(const std::string& server_host, int server_port, int local_port);
    void shutdown();
    bool isRunning() const { return running_; }

    // Connection management  
    bool addConnection(const std::string& local_ip, int network_handle);
    void removeConnection(const std::string& local_ip);
    void removeAllConnections();

    // Statistics
    int getActiveConnectionCount() const;
    std::vector<std::string> getConnectionStats() const;

    // Packet processing (called from separate threads)
    void processSRTPacket(const uint8_t* data, size_t length);
    void processSRTLAResponse(const uint8_t* data, size_t length, const std::string& connection_id);

private:
    bool running_;
    std::unique_ptr<srtla_android_session> session_;
    
    // No copy/assignment
    SRTLAAndroidWrapper(const SRTLAAndroidWrapper&) = delete;
    SRTLAAndroidWrapper& operator=(const SRTLAAndroidWrapper&) = delete;
};

// C-style interface for JNI
extern "C" {
    // Session management
    JNIEXPORT jlong JNICALL
    Java_com_example_srtla_SRTLANative_createSession(JNIEnv *env, jobject thiz);
    
    JNIEXPORT void JNICALL
    Java_com_example_srtla_SRTLANative_destroySession(JNIEnv *env, jobject thiz, jlong session_ptr);
    
    JNIEXPORT jboolean JNICALL
    Java_com_example_srtla_SRTLANative_initialize(JNIEnv *env, jobject thiz, jlong session_ptr,
                                                  jstring server_host, jint server_port, jint local_port);
    
    JNIEXPORT void JNICALL
    Java_com_example_srtla_SRTLANative_shutdown(JNIEnv *env, jobject thiz, jlong session_ptr);
    
    // Connection management
    JNIEXPORT jboolean JNICALL
    Java_com_example_srtla_SRTLANative_addConnection(JNIEnv *env, jobject thiz, jlong session_ptr,
                                                     jstring local_ip, jint network_handle);
                                                     
    JNIEXPORT void JNICALL
    Java_com_example_srtla_SRTLANative_removeConnection(JNIEnv *env, jobject thiz, jlong session_ptr,
                                                        jstring local_ip);
    
    // Statistics
    JNIEXPORT jint JNICALL
    Java_com_example_srtla_SRTLANative_getActiveConnectionCount(JNIEnv *env, jobject thiz, jlong session_ptr);
    
    JNIEXPORT jobjectArray JNICALL
    Java_com_example_srtla_SRTLANative_getConnectionStats(JNIEnv *env, jobject thiz, jlong session_ptr);
}

#endif // SRTLA_ANDROID_WRAPPER_H