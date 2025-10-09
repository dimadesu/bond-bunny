package com.example.srtla;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramSocket;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.ArrayList;

/**
 * Native SRTLA Service - Uses JNI to delegate to C++ implementation
 * Implements multi-connection bonding with file descriptor management
 */
public class NativeSrtlaService extends Service {
    private static final String TAG = "NativeSrtlaService";
    
    // Network types for connection management
    public enum NetworkType {
        WIFI,
        CELLULAR,
        ETHERNET
    }
    
    // Connection enable/disable state - industry standard pattern
    private static final Map<NetworkType, Boolean> connectionEnable = new ConcurrentHashMap<>();
    
    static {
        // Initialize all network types as enabled by default
        connectionEnable.put(NetworkType.WIFI, Boolean.TRUE);
        connectionEnable.put(NetworkType.CELLULAR, Boolean.TRUE);
        connectionEnable.put(NetworkType.ETHERNET, Boolean.TRUE);
    }
    
    // Service state tracking
    private static volatile NativeSrtlaService instance = null;
    private volatile boolean isRunning = false;
    private volatile boolean isStreaming = false;
    private volatile int connectionCount = 0;
    private volatile String serverInfo = "";
    // Keep server host/port for socket connect during creation so kernel assigns local address
    private String nativeServerHost = null;
    private int nativeServerPort = 0;
    
    // Connection data tracking for visualization
    private static class ConnectionData {
        String connId;
        String networkType;
        int version;  // Connection version for handling reconnections
        boolean needsReconnect;  // Flag for reconnection logic
        boolean needsDisable;  // Flag for disabled connections (standard disable pattern)
        int window;
        int inflight;
        int nak;
        long bytesSent;
        long packetsSent;
        long bytesReceived;
        long packetsReceived;
        double rtt;
        int score;
        boolean isActive;
        long lastUpdate;
        
        ConnectionData(String id, String type, int ver) {
            this.connId = id;
            this.networkType = type;
            this.version = ver;
            this.needsReconnect = false;
            this.needsDisable = false;
            this.lastUpdate = System.currentTimeMillis();
        }
    }
    private Map<String, ConnectionData> connectionDataMap = new ConcurrentHashMap<>();
    
    // Connection versioning for handling reconnections
    private static final AtomicInteger connectionVersionCounter = new AtomicInteger(0);
    private final Map<String, Integer> networkTypeToLatestVersion = new ConcurrentHashMap<>();
    
    static {
        System.loadLibrary("srtla_native");
    }
    
    // Native method declarations
    private native int initializeCore(int localPort, String serverHost, String serverPort);
    private native int initializeBonding(int localPort, String serverHost, String serverPort);
    private native void shutdownBonding();
    private native boolean addConnection(int fd, String connId, int weight, String type);
    private native boolean addConnectionWithNetworkHandle(long networkHandle, String virtualIp, int weight, String type, String serverHost, int serverPort);
    private native boolean removeConnection(String connId);
    private native void updateConnectionWeight(String connId, int weight);
    private native void refreshConnections();
    private native void forceRefreshConnections();
    private native int getConnectedConnectionCount();
    private native String[] getNativeConnectionList(); // Get actual connections from native layer
    
    /**
     * Get connection string for native code
     * This is called by native code to get current connection state
     * Format: connId|weight|enabled,connId|weight|enabled,...
     */
    @SuppressWarnings("unused")  // Called from native code
    public String getConnsString() {
        Log.i(TAG, "getConnsString() called by native - getting current connections");
        
        StringBuilder sb = new StringBuilder();
        ArrayList<ConnectionData> conns = getConns();
        
        Log.i(TAG, "getConnsString: Found " + conns.size() + " active connections");
        
        for (int i = 0; i < conns.size(); i++) {
            ConnectionData conn = conns.get(i);
            if (i > 0) {
                sb.append(",");
            }
            // Format: connId|weight|enabled
            sb.append(conn.connId).append("|").append(conn.score).append("|").append(conn.isActive ? 1 : 0);
        }
        
        String result = sb.toString();
        Log.i(TAG, "getConnsString() returning: '" + result + "'");
        return result;
    }
    
    /**
     * Get connection version index - for native to detect changes
     */
    @SuppressWarnings("unused")  // Called from native code
    public int getLastUpdate() {
        return connectionIndex.get();
    }

    /**
     * Sync Java connection state with native layer reality
     * Remove connections that exist in Java but not in native
     */
    private void syncConnectionsWithNative() {
        try {
            // Get actual connections from native layer
            String[] nativeConnections = getNativeConnectionList();
            Set<String> nativeVirtualIps = new HashSet<>();
            
            if (nativeConnections != null) {
                for (String nativeConn : nativeConnections) {
                    nativeVirtualIps.add(nativeConn);
                    Log.d(TAG, "Native has connection: " + nativeConn);
                }
            }
            
            Log.i(TAG, "Sync check: Native has " + nativeVirtualIps.size() + " connections, Java has " + connectionDataMap.size());
            
            // Find Java connections that don't exist in native
            Iterator<Map.Entry<String, ConnectionData>> it = connectionDataMap.entrySet().iterator();
            int removedCount = 0;
            
            while (it.hasNext()) {
                Map.Entry<String, ConnectionData> entry = it.next();
                String connId = entry.getKey();
                String virtualIp = connIdToVirtualIp.get(connId);
                
                if (virtualIp != null && !nativeVirtualIps.contains(virtualIp)) {
                    Log.w(TAG, "Connection " + connId + " (virtualIp=" + virtualIp + ") exists in Java but not native - removing");
                    
                    // Clean up all mappings
                    Network network = connIdToNetwork.get(connId);
                    if (network != null) {
                        networkToConnId.remove(network);
                        connIdToNetwork.remove(connId);
                    }
                    connIdToVirtualIp.remove(connId);
                    virtualIpToConnId.remove(virtualIp);
                    
                    // Remove from Java connection map
                    it.remove();
                    removedCount++;
                }
            }
            
            if (removedCount > 0) {
                connectionCount = connectionDataMap.size();
                Log.i(TAG, "Sync complete: Removed " + removedCount + " stale connections, " + connectionCount + " remaining");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error syncing connections with native", e);
        }
    }

    // Get connections with full side effects - advanced connection management
    // This triggers connection filtering, cleanup, reconnection, and enable/disable management
    private ArrayList<ConnectionData> getConns() {
        // TODO: Temporarily disabled sync to test streaming stability
        // First sync with native layer to remove stale connections
        // syncConnectionsWithNative();
        
        // Update connection enable states from system before processing connections
        updateConnectionEnableFromSystemState();
        
        ArrayList<ConnectionData> activeConnections = new ArrayList<>();
        HashMap<String, Integer> latestVersions = new HashMap<>();
        
        // Phase 1: Find latest version for each network type (version tracking)
        Iterator<Map.Entry<String, ConnectionData>> it = connectionDataMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ConnectionData> entry = it.next();
            ConnectionData conn = entry.getValue();
            String networkType = conn.networkType;
            int currentLatest = latestVersions.getOrDefault(networkType, 0);
            if (conn.version > currentLatest) {
                latestVersions.put(networkType, conn.version);
            }
        }
        
        // Phase 2: Process connections with version filtering and enable/disable logic
        it = connectionDataMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ConnectionData> entry = it.next();
            ConnectionData conn = entry.getValue();
            String networkType = conn.networkType;
            
            // Remove outdated connections (standard version cleanup)
            Integer latestVersion = latestVersions.get(networkType);
            if (latestVersion == null || conn.version != latestVersion) {
                Log.i(TAG, "Removing outdated connection: " + conn.connId + " (version " + conn.version + 
                      " vs latest " + latestVersion + ")");
                
                // Clean up native connection
                String vIp = connIdToVirtualIp.get(conn.connId);
                if (vIp != null) {
                    removeConnection(vIp);
                }
                
                // Clean up mappings
                Network network = connIdToNetwork.get(conn.connId);
                if (network != null) {
                    networkToConnId.remove(network);
                    connIdToNetwork.remove(conn.connId);
                    String removedVIp = connIdToVirtualIp.remove(conn.connId);
                    if (removedVIp != null) virtualIpToConnId.remove(removedVIp);
                }
                
                it.remove(); // Actually remove from connectionDataMap
                connectionCount--;
                continue;
            }
            
            // Check if network type is enabled (connectionEnable filtering)
            NetworkType enumType = parseNetworkType(networkType);
            if (enumType != null && Boolean.TRUE.equals(connectionEnable.get(enumType))) {
                // Network is enabled - handle reconnection if needed (disable flag management)
                if (conn.needsReconnect) {
                    Log.i(TAG, "Attempting to reconnect connection: " + conn.connId);
                    try {
                        // Recreate connection similar to addNetworkConnection
                        Network network = connIdToNetwork.get(conn.connId);
                        if (network != null) {
                            DatagramSocket socket = new DatagramSocket();
                            network.bindSocket(socket);
                            
                            if (nativeServerHost != null && nativeServerPort > 0) {
                                socket.connect(new java.net.InetSocketAddress(nativeServerHost, nativeServerPort));
                            }
                            
                            int fd = ParcelFileDescriptor.fromDatagramSocket(socket).detachFd();
                            String virtualIp = connIdToVirtualIp.get(conn.connId);
                            if (virtualIp != null) {
                                boolean success = addConnection(fd, virtualIp, 100, networkType);
                                if (success) {
                                    Log.i(TAG, "Reconnected: " + conn.connId);
                                    conn.needsReconnect = false;
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to reconnect " + conn.connId, e);
                    }
                }
                
                // Re-enable the connection (clear disable flag)
                if (conn.needsDisable) {
                    Log.i(TAG, "Re-enabling connection: " + conn.connId);
                    conn.needsDisable = false;
                }
                
                // Update weight if changed (standard pattern)
                int targetWeight = 100; // Default weight
                if (targetWeight != conn.score) {
                    // Update weight via native if needed
                    updateConnectionWeight(conn.connId, targetWeight);
                    conn.score = targetWeight;
                }
                
                // Mark connection as active since it's enabled and being included
                conn.isActive = true;
                
                // Add to active connections
                activeConnections.add(conn);
            } else {
                // Network is disabled - mark for disable but don't remove (preserve for re-enable)
                if (!conn.needsDisable) {
                    Log.i(TAG, "Disabling connection: " + conn.connId + " (type: " + networkType + ")");
                    conn.needsDisable = true;
                }
                // Set reconnect flag (standard pattern for disabled connections)
                conn.needsReconnect = true;
                // Mark as inactive since it's disabled
                conn.isActive = false;
                // Don't add to activeConnections, but keep in connectionDataMap
            }
        }
        
        // Update connection count to reflect filtered results
        connectionCount = activeConnections.size();
        
        return activeConnections;
    }
    
    /**
     * Called from native code to update connection statistics
     * @param virtualIp The virtual IP address of the connection (from native)
     */
    @SuppressWarnings("unused")  // Called from native code
    public void updateConn(String virtualIp, int window, int inflight, int nak, int score, 
                          int weight, long bytesSent, long packetsSent,
                          long bytesReceived, long packetsReceived, int rtt, int isActive) {
        
        // Mark service as connected on first update
        if (!connected) {
            connected = true;
            Log.i(TAG, "SRTLA connection established (first updateConn received)");
        }
        
        // Convert virtual IP to our connection ID  
        String connId = virtualIpToConnId.get(virtualIp);
        if (connId == null) {
            Log.w(TAG, "updateConn called for unknown connection: " + virtualIp);
            return;
        }
        
        ConnectionData data = connectionDataMap.get(connId);
        if (data != null) {
            // Update all statistics
            data.window = window;
            data.inflight = inflight;
            data.nak = nak;
            data.score = score;
            data.bytesSent = bytesSent;
            data.packetsSent = packetsSent;
            data.bytesReceived = bytesReceived;
            data.packetsReceived = packetsReceived;
            data.rtt = (double) rtt;  // Convert int to double for storage
            data.isActive = (isActive != 0);
            data.lastUpdate = System.currentTimeMillis();
            
            // Increment update index so native knows state changed
            connectionIndex.incrementAndGet();
            
            Log.d(TAG, "Updated connection " + connId + " (virtualIp=" + virtualIp + "): window=" + window + 
                       ", inflight=" + inflight + ", nak=" + nak + ", score=" + score + 
                       ", rtt=" + rtt + ", active=" + data.isActive);
        } else {
            Log.w(TAG, "updateConn called for unknown connection: " + connId);
        }
    }
    
    /**
     * Enable/disable connection type
     */
    public static void setEnabled(NetworkType networkType, boolean enabled) {
        Log.d(TAG, "Setting enabled of " + networkType + " to " + enabled);
        connectionEnable.put(networkType, enabled);
        // Increment connection index to signal change to native
        if (instance != null) {
            instance.connectionIndex.incrementAndGet();
        }
    }
    
    /**
     * Set connection weight
     */
    public static void setWeight(NetworkType networkType, int weight) {
        Log.d(TAG, "Setting weight of " + networkType + " to " + weight);
        if (weight > 100 || weight < 0) {
            return;
        }
        // Note: In our implementation, weight is managed per connection, not per type
        // This would need to be expanded if per-type weights are needed
        if (instance != null) {
            instance.connectionIndex.incrementAndGet();
        }
    }

    // Simple housekeeping variables (inspired by srtla_send.c)
    private static final int HOUSEKEEPING_INTERVAL_MS = 2000; // 2 seconds
    private static final int CONNECTION_TIMEOUT_SEC = 5; // Connection timeout
    private long lastHousekeepingTime = 0;
    private Thread housekeepingThread = null;
    
    /**
     * Simple periodic housekeeping inspired by srtla_send.c connection_housekeeping()
     * This runs every 2 seconds and:
     * 1. Checks connection health 
     * 2. Attempts reconnection for failed connections
     * 3. Updates connection state
     */
    private void startSimpleHousekeeping() {
        if (housekeepingThread != null && housekeepingThread.isAlive()) {
            Log.i(TAG, "Housekeeping already running");
            return;
        }
        
        housekeepingThread = new Thread(() -> {
            Log.i(TAG, "Simple housekeeping thread started");
            
            while (isRunning && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(HOUSEKEEPING_INTERVAL_MS);
                    performHousekeeping();
                } catch (InterruptedException e) {
                    Log.i(TAG, "Housekeeping thread interrupted");
                    break;
                } catch (Exception e) {
                    Log.e(TAG, "Error in housekeeping", e);
                }
            }
            
            Log.i(TAG, "Simple housekeeping thread stopped");
        }, "SRTLA-Housekeeping");
        
        housekeepingThread.start();
    }
    
    /**
     * Core housekeeping logic inspired by srtla_send.c
     */
    private void performHousekeeping() {
        long currentTime = System.currentTimeMillis();
        
        // Throttle housekeeping execution
        if (currentTime - lastHousekeepingTime < HOUSEKEEPING_INTERVAL_MS) {
            return;
        }
        lastHousekeepingTime = currentTime;
        
        try {
            // Basic connection health check
            int totalConnections = connectionDataMap.size();
            int connectedCount = getConnectedConnectionCount();
            
            Log.d(TAG, "Housekeeping: Total=" + totalConnections + ", Connected=" + connectedCount);
            
            // If we have no connections but networks are available, try to set them up
            if (totalConnections == 0 && !availableNetworks.isEmpty()) {
                Log.i(TAG, "No connections but networks available - attempting registration");
                registerDetectedNetworks();
            }
            
            // If we have connections but none are connected, trigger maintenance
            if (totalConnections > 0 && connectedCount == 0) {
                Log.w(TAG, "Connections exist but none connected - triggering maintenance");
                // This will trigger the reconnection logic in getConns()
                getConns();
            }
            
            // Check if we have fewer connections than available networks (some may have been destroyed)
            int expectedConnections = availableNetworks.size();
            if (totalConnections < expectedConnections && expectedConnections > 0) {
                Log.w(TAG, "Have " + totalConnections + " connections but " + expectedConnections + " networks available - attempting re-registration");
                registerDetectedNetworks();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error in performHousekeeping", e);
        }
    }
    
    // Simple getter that just returns size - calls getConns() to trigger side effects
    private int getConnsSize() {
        return getConns().size();
    }
    
    /**
     * Called from native code after SRTLA engine starts to register all detected networks
     * This is called from initializeBonding() after the engine is running
     */
    @SuppressWarnings("unused")  // Called from native code
    public void registerDetectedNetworks() {
        Log.i(TAG, "registerDetectedNetworks: Registering " + availableNetworks.size() + " detected networks with running SRTLA engine");
        
        for (Network network : availableNetworks) {
            String networkType = networkTypes.get(network);
            if (networkType != null) {
                Log.i(TAG, "Registering " + networkType + " network: " + network);
                addNetworkConnection(networkType, network, 100);
            } else {
                Log.w(TAG, "No network type stored for network: " + network);
            }
        }
        
        Log.i(TAG, "Finished registering all detected networks");
    }
    private boolean areConnectionsReady() {
        // First check if we have any connections at all (this triggers getConns() side effects)
        if (getConnsSize() < 1) {
            Log.d(TAG, "areConnectionsReady: No connections exist yet");
            return false;
        }
        
        // Check how many connections are actually in CONNECTED state
        int connectedCount = getConnectedConnectionCount();
        Log.i(TAG, "areConnectionsReady: total=" + getConnsSize() + ", connected=" + connectedCount);
        
        // Log detailed connection states for debugging
        ArrayList<ConnectionData> conns = getConns();
        for (ConnectionData conn : conns) {
            Log.i(TAG, "Connection " + conn.connId + " (" + conn.networkType + "): version=" + conn.version + 
                  ", needsReconnect=" + conn.needsReconnect + ", needsDisable=" + conn.needsDisable);
        }
        
        // We need at least 1 connection in CONNECTED state to be ready
        boolean ready = connectedCount >= 1;
        Log.i(TAG, "areConnectionsReady result: " + ready + " (need 1+ connected, have " + connectedCount + ")");
        return ready;
    }
    
    // Thread control fields
    private boolean shouldKill = false;
    private boolean connected = false;
    
    // Connection management
    private ConnectivityManager connectivityManager;
    private AtomicInteger connectionIndex = new AtomicInteger(0);
    
    // Periodic connection management
    private Thread connectionManagerThread;
    
    // Track available networks (detected but not yet added to SRTLA)
    private Set<Network> availableNetworks = ConcurrentHashMap.newKeySet();
    private Map<Network, String> networkTypes = new ConcurrentHashMap<>();
    
    // Track which networks we've already added to avoid duplicates
    private Set<Network> addedNetworks = ConcurrentHashMap.newKeySet();
    
    // Bidirectional mapping between Network and connection ID for cleanup
    private Map<Network, String> networkToConnId = new ConcurrentHashMap<>();
    private Map<String, Network> connIdToNetwork = new ConcurrentHashMap<>();
    // Virtual IP mapping: virtual IP -> connId and connId -> virtual IP
    private Map<String, String> virtualIpToConnId = new ConcurrentHashMap<>();
    private Map<String, String> connIdToVirtualIp = new ConcurrentHashMap<>();
    
    // Connection ID generation for connection identification
    private static class ConnectionIdGenerator {
        private final Map<String, AtomicInteger> countersByType = new ConcurrentHashMap<>();
        
        public synchronized String getNextId(String networkType) {
            countersByType.putIfAbsent(networkType.toLowerCase(), new AtomicInteger(0));
            int num = countersByType.get(networkType.toLowerCase()).incrementAndGet();
            return String.format("%s-%d", networkType.toLowerCase(), num);
        }
    }
    
    private ConnectionIdGenerator idGenerator = new ConnectionIdGenerator();
    
    // Virtual IP allocator (10.255.0.x)
    private final AtomicInteger virtualIpCounter = new AtomicInteger(1);
    private String allocateVirtualIp() {
        int n = virtualIpCounter.getAndIncrement();
        // wrap at 250 to avoid special addresses
        n = ((n - 1) % 250) + 1;
        return String.format("10.255.0.%d", n);
    }
    
    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // If service is already running, don't restart
        if (isRunning) {
            Log.w(TAG, "Service already running, ignoring duplicate start");
            return START_STICKY;
        }
        
        // Start as foreground service
        createNotificationChannel();
        startForeground(1, createNotification());
        
        String serverHost = intent.getStringExtra("srtla_receiver_host");
        String serverPort = String.valueOf(intent.getIntExtra("srtla_receiver_port", 5000));
        int localPort = intent.getIntExtra("srt_listen_port", 6000);
        
        // store for socket creation/connect
        try {
            nativeServerHost = serverHost;
            nativeServerPort = Integer.parseInt(serverPort);
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse server host/port for socket connect", e);
            nativeServerHost = serverHost;
            nativeServerPort = 5000;
        }

        serverInfo = serverHost + ":" + serverPort + " (listen: " + localPort + ")";
        
        // Initialize SRTLA core first so connections can be added
        int result = initializeCore(localPort, serverHost, serverPort);
        
        if (result != 0) {
            Log.e(TAG, "Failed to initialize SRTLA core: " + result);
            isRunning = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        
        setupConnections();
        
        isRunning = true;

        startConnectionManagerThread(localPort, serverHost, serverPort);
        
        // Start simple housekeeping after a short delay
        new Thread(() -> {
            try {
                Thread.sleep(3000); // Wait 3 seconds for initial setup
                startSimpleHousekeeping();
            } catch (InterruptedException e) {
                Log.w(TAG, "Housekeeping startup interrupted", e);
            }
        }).start();
        
        return START_STICKY;
    }
    
    /**
     * Setup connections using ConnectivityManager.requestNetwork()
     * This requests specific network types and gets callbacks when they become available
     * 
     * Three-way bonding support:
     * - WiFi (TRANSPORT_WIFI) 
     * - Cellular (TRANSPORT_CELLULAR)
     * - Ethernet (TRANSPORT_ETHERNET) - USB tethering, wired connections, etc.
     */
    private void setupConnections() {
        Log.i(TAG, "setupConnections: Starting network requests...");

        // Request WiFi network
        try {
            NetworkRequest wifiRequest = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    .build();

            connectivityManager.requestNetwork(wifiRequest, new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    try {
                        Log.i(TAG, "WiFi network available via requestNetwork: " + network);
                        // Store network for later registration (after SRTLA engine starts)
                        availableNetworks.add(network);
                        networkTypes.put(network, "wifi");
                        Log.i(TAG, "Stored WiFi network for later registration");
                    } catch (Exception e) {
                        Log.e(TAG, "Error in WiFi onAvailable", e);
                    }
                }
            });
            Log.i(TAG, "setupConnections: WiFi network request sent");
        } catch (Exception e) {
            Log.e(TAG, "setupConnections: Failed to request WiFi network: " + e);
        }

        // Request Cellular network
        try {
            NetworkRequest cellularRequest = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    .build();

            connectivityManager.requestNetwork(cellularRequest, new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    try {
                        Log.i(TAG, "Cellular network available via requestNetwork: " + network);
                        // Store network for later registration (after SRTLA engine starts)
                        availableNetworks.add(network);
                        networkTypes.put(network, "cellular");
                        Log.i(TAG, "Stored Cellular network for later registration");
                    } catch (Exception e) {
                        Log.e(TAG, "Error in Cellular onAvailable", e);
                    }
                }
            });
            Log.i(TAG, "setupConnections: Cellular network request sent");
        } catch (Exception e) {
            Log.e(TAG, "setupConnections: Failed to request Cellular network: " + e);
        }

        // Request Ethernet network (USB tethering, wired connections, etc.)
        try {
            NetworkRequest ethernetRequest = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    .build();

            connectivityManager.requestNetwork(ethernetRequest, new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    try {
                        Log.i(TAG, "Ethernet network available via requestNetwork: " + network);
                        // Store network for later registration (after SRTLA engine starts)
                        availableNetworks.add(network);
                        networkTypes.put(network, "ethernet");
                        Log.i(TAG, "Stored Ethernet network for later registration");
                    } catch (Exception e) {
                        Log.e(TAG, "Error in Ethernet onAvailable", e);
                    }
                }
            });
            Log.i(TAG, "setupConnections: Ethernet network request sent");
        } catch (Exception e) {
            Log.e(TAG, "setupConnections: Failed to request Ethernet network: " + e);
        }

        Log.i(TAG, "setupConnections: Network requests completed");
    }
    
    /**
     * Add a network connection - called from NetworkCallback.onAvailable()
     * This passes network handle to native for binding
     */
    private void addNetworkConnection(String networkType, Network network, int weight) {
        Log.i(TAG, "addNetworkConnection: Adding " + networkType + " connection using native binding approach");
        
        try {
            // Get network handle for native binding
            long networkHandle = network.getNetworkHandle();
            Log.i(TAG, "Got network handle for " + networkType + ": " + networkHandle);
            
            // Allocate virtual IP for this connection
            String virtualIp = allocateVirtualIp();
            String connId = idGenerator.getNextId(networkType);
            
            // Store bidirectional mapping between virtual IP and connection ID
            virtualIpToConnId.put(virtualIp, connId);
            connIdToVirtualIp.put(connId, virtualIp);
            
            Log.i(TAG, "addNetworkConnection: Created connection with connId=" + connId + ", virtualIp=" + virtualIp + ", networkHandle=" + networkHandle);
            
            // Add to native SRTLA engine with network handle for native binding
            boolean success = addConnectionWithNetworkHandle(networkHandle, virtualIp, weight, networkType, 
                                                           nativeServerHost, nativeServerPort);
            
            if (success) {
                connectionCount++;
                addedNetworks.add(network);  // Track this network
                
                // Track bidirectional mapping
                networkToConnId.put(network, connId);
                connIdToNetwork.put(connId, network);
                
                // Track connection data with version
                int version = connectionVersionCounter.incrementAndGet();
                ConnectionData connData = new ConnectionData(connId, networkType, version);
                connectionDataMap.put(connId, connData);
                
                Log.i(TAG, "Successfully added " + networkType + " connection via native binding: " + 
                      "id=" + connId + ", virtualIp=" + virtualIp + ", version=" + version + ", weight=" + weight + 
                      ", networkHandle=" + networkHandle + ". Total connections: " + connectionCount);
                
                // Increment update index so native knows state changed  
                connectionIndex.incrementAndGet();
                
                // Check connection state immediately after adding
                int connectedCount = getConnectedConnectionCount();
                Log.i(TAG, "After adding " + networkType + ": " + connectedCount + " connections are in CONNECTED state");
            } else {
                Log.e(TAG, "Native addConnectionWithNetworkHandle failed for " + networkType + 
                      " (id=" + connId + ", virtualIp=" + virtualIp + ", networkHandle=" + networkHandle + ")");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to add connection for " + networkType + " in requestNetwork callback", e);
        }
    }

    /**
     * Start main connection management thread
     * This follows a standard pattern with while(!shouldKill) loop
     * Waits for connections, then calls initializeBonding outside the loop
     */
    private void startConnectionManagerThread(int localPort, String serverHost, String serverPort) {
        connectionManagerThread = new Thread(() -> {
            Log.i(TAG, "SRTLA Runner thread started");
            
            // Phase 1: Wait for networks to be available (detected by callbacks)
            while (!shouldKill) {
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException e) {
                    Log.i(TAG, "Thread interrupted during network wait");
                    break;
                }
                
                if (availableNetworks.size() < 1) {
                    Log.i(TAG, "Waiting for networks to be detected...");
                } else {
                    Log.i(TAG, "Ready to start SRTLA - " + availableNetworks.size() + " networks detected");
                    break; // Exit the waiting loop
                }
            }
            
            // Phase 2: Start SRTLA and register connections with running engine
            if (!shouldKill && availableNetworks.size() >= 1) {
                Log.i(TAG, "Starting SRTLA engine...");
                
                // EMERGENCY RECOVERY: If this is a restart (connections exist but may be stale),
                // force refresh all connections to reset their state
                if (connectionCount > 0) {
                    Log.i(TAG, "*** Service restart detected - forcing emergency connection refresh ***");
                    try {
                        // Give a small delay to ensure native core is ready
                        Thread.sleep(100);
                        forceRefreshConnections();
                        Log.i(TAG, "Emergency connection refresh completed");
                    } catch (Exception e) {
                        Log.w(TAG, "Emergency refresh failed, proceeding anyway: " + e.getMessage());
                    }
                }
                
                // Start the SRTLA engine - this call blocks until SRTLA stops
                // The native event loop runs inside this call
                int result = initializeBonding(localPort, serverHost, serverPort);
                if (result != 0) {
                    Log.w(TAG, "SRTLA returned with error: " + result);
                } else {
                    Log.i(TAG, "SRTLA ended normally");
                }
                
                // EMERGENCY RECOVERY: After SRTLA stops, force refresh connections
                // to prepare for potential restart
                if (connectionCount > 0) {
                    Log.i(TAG, "*** SRTLA stopped - triggering emergency connection refresh for potential restart ***");
                    try {
                        forceRefreshConnections();
                        Log.i(TAG, "Post-stop emergency refresh completed");
                    } catch (Exception e) {
                        Log.w(TAG, "Post-stop refresh failed: " + e.getMessage());
                    }
                }
            }
            
            Log.i(TAG, "SRTLA Runner thread stopped");
        }, "SRTLA Runner");
        
        connectionManagerThread.start();
    }
    
    /**
     * Check current system network state and update connectionEnable map accordingly
     * This replaces the onLost() callback approach
     */
    private void updateConnectionEnableFromSystemState() {
        if (connectivityManager == null) return;
        
        try {
            // Check WiFi availability
            boolean wifiAvailable = false;
            boolean cellularAvailable = false;
            boolean ethernetAvailable = false;
            
            // First check our stored available networks from requestNetwork callbacks
            for (Network network : availableNetworks) {
                String networkType = networkTypes.get(network);
                if ("wifi".equals(networkType)) {
                    wifiAvailable = true;
                } else if ("cellular".equals(networkType)) {
                    cellularAvailable = true;
                } else if ("ethernet".equals(networkType)) {
                    ethernetAvailable = true;
                }
            }
            
            // Also check getAllNetworks as a backup
            Network[] networks = connectivityManager.getAllNetworks();
            Log.d(TAG, "Checking " + networks.length + " networks for availability");
            
            for (Network network : networks) {
                NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(network);
                if (caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        wifiAvailable = true;
                        Log.d(TAG, "Found WiFi network: " + network);
                    } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                        cellularAvailable = true;
                        Log.d(TAG, "Found Cellular network: " + network);
                    } else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                        ethernetAvailable = true;
                        Log.d(TAG, "Found Ethernet network: " + network);
                    }
                }
            }
            
            Log.d(TAG, "Network availability: WiFi=" + wifiAvailable + 
                       ", Cellular=" + cellularAvailable + 
                       ", Ethernet=" + ethernetAvailable);
            
            // Update connectionEnable map based on actual system state
            updateConnectionEnableState(NetworkType.WIFI, wifiAvailable);
            updateConnectionEnableState(NetworkType.CELLULAR, cellularAvailable);
            updateConnectionEnableState(NetworkType.ETHERNET, ethernetAvailable);
            
        } catch (Exception e) {
            Log.e(TAG, "Error checking network state", e);
        }
    }
    
    /**
     * Update connection enable state if it changed
     */
    private void updateConnectionEnableState(NetworkType networkType, boolean available) {
        Boolean currentState = connectionEnable.get(networkType);
        if (currentState == null || currentState != available) {
            Log.i(TAG, "Network state changed: " + networkType + " -> " + available);
            connectionEnable.put(networkType, available);
        }
    }
    

    
    /**
     * Remove a network connection that was lost
     * Synchronized to prevent race conditions when multiple networks are lost simultaneously
     */
    private synchronized void removeNetworkConnection(Network network, String connId, String networkType) {
        try {
            Log.i(TAG, "Removing " + networkType + " connection: id=" + connId + " (remaining after removal: " + (connectionCount - 1) + ")");
            
            // Remove from native SRTLA engine (use virtual IP)
            String vIp = connIdToVirtualIp.get(connId);
            if (vIp == null) {
                Log.w(TAG, "No virtual IP found for connId=" + connId + "; attempting to remove by connId fallback");
                vIp = connId; // fallback (older behavior)
            }
            boolean success = removeConnection(vIp);
            
            if (success) {
                connectionCount--;
                
                // Clean up all tracking
                addedNetworks.remove(network);
                networkToConnId.remove(network);
                connIdToNetwork.remove(connId);
                // Remove virtual IP mappings
                String v = connIdToVirtualIp.remove(connId);
                if (v != null) virtualIpToConnId.remove(v);
                connectionDataMap.remove(connId);
                
                Log.i(TAG, "Successfully removed " + networkType + " connection. Active connections: " + connectionCount);
                
                // Update notification to show new connection count
                startForeground(1, createNotification());
                
                // Check for problematic states that need refresh
                checkConnectionHealth();
            } else {
                Log.e(TAG, "Failed to remove " + networkType + " connection from native code");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error removing connection: " + networkType, e);
        }
    }
    
    private void checkConnectionHealth() {
        // Update streaming state based on connection count
        isStreaming = (connectionCount > 0);
        
        // If we're in streaming state but have connection issues, auto-refresh
        if (isStreaming) {
            if (connectionCount == 0) {
                Log.w(TAG, "No connections while streaming - triggering emergency refresh");
                new Thread(() -> {
                    try {
                        Thread.sleep(500);
                        restartAllConnections();
                    } catch (Exception e) {
                        Log.e(TAG, "Error during emergency refresh", e);
                    }
                }).start();
            }
        }
    }
    
    /**
     * Refresh all SRTLA connections - reset state and re-register
     * This can be called when network conditions change or connections get stuck
     */
    public synchronized void refreshAllConnections() {
        Log.i(TAG, "Refreshing all SRTLA connections using advanced connection management...");
        
        // Use advanced connection filtering to clean up stale connections
        ArrayList<ConnectionData> activeConnections = getActiveConnections();
        
        Log.i(TAG, "Active connections after cleanup: " + activeConnections.size());
        
        try {
            // Reset native SRTLA state
            refreshConnections();
            Log.i(TAG, "Native connection refresh completed");
        } catch (Exception e) {
            Log.e(TAG, "Error during native refresh", e);
        }
        
        // Update connection count
        connectionCount = activeConnections.size();
        
        // Update notification
        if (connectionCount > 0) {
            startForeground(1, createNotification());
        }
        
        Log.i(TAG, "Connection refresh completed - active connections: " + connectionCount);
    }
    
    /**
     * Full connection restart - similar to service stop/start but for connections only
     * Removes all connections and recreates them from scratch
     */
    public synchronized void restartAllConnections() {
        Log.i(TAG, "Restarting all SRTLA connections - full reset...");
        
        try {
            // Save current connection count for comparison
            int oldCount = connectionCount;
            
            // Remove all current connections
            Set<Network> currentNetworks = new HashSet<>(addedNetworks);
            for (Network network : currentNetworks) {
                String connId = networkToConnId.get(network);
                if (connId != null) {
                    ConnectionData connData = connectionDataMap.get(connId);
                    String networkType = connData != null ? connData.networkType : "Unknown";
                    removeNetworkConnection(network, connId, networkType);
                }
            }
            
            // Clear all tracking
            addedNetworks.clear();
            networkToConnId.clear();
            connIdToNetwork.clear();
            virtualIpToConnId.clear();
            connIdToVirtualIp.clear();
            connectionDataMap.clear();
            connectionCount = 0;
            
            // Reset the native SRTLA state as well
            refreshConnections();
            
            // Re-setup connections
            setupConnections();
            
            Log.i(TAG, "Connection restart completed - had " + oldCount + " connections, recreating...");
        } catch (Exception e) {
            Log.e(TAG, "Error during connection restart", e);
        }
    }
    
    /**
     * Native callback for connection statistics updates
     * Parameters:
     * p1 = window, p2 = inflight, p3 = nak, p4-p6 = reserved
     * l1 = bytes_sent, l2 = packets_sent, l3 = bytes_received, l4 = packets_received
     * p7 = score
     */
    @SuppressWarnings("unused")  // Called from native code
    public void onConnectionStats(String idOrVirtualIp, int p1, int p2, int p3, int p4, int p5, int p6,
                          long l1, long l2, long l3, long l4, int p7) {
        // Native currently sends virtual IP as the identifier. Map it to our connId if present.
        String connId = idOrVirtualIp;
        String mapped = virtualIpToConnId.get(idOrVirtualIp);
        if (mapped != null) {
            connId = mapped;
        }

        // Debug log to trace native->Java stats callbacks and mapping
        Log.d(TAG, "onConnectionStats: idOrVirtualIp=" + idOrVirtualIp + " mapped=" + connId + " p1=" + p1 + " inflight=" + p2 + " bytesSent=" + l1);

        ConnectionData data = connectionDataMap.get(connId);
        if (data == null) {
            // If no mapping found, log for debugging. This can happen during startup/zombie cleanup.
            Log.w(TAG, "onConnectionStats: no ConnectionData for idOrVirtualIp=" + idOrVirtualIp + " mapped=" + connId);
            return;
        }

        data.window = p1;
        data.inflight = p2;
        data.nak = p3;
        data.bytesSent = l1;
        data.packetsSent = l2;
        data.bytesReceived = l3;
        data.packetsReceived = l4;
        data.score = p7;
        data.isActive = (p2 > 0); // Has inflight packets
        data.lastUpdate = System.currentTimeMillis();

        // Calculate RTT estimate from window/inflight ratio if available
        if (p2 > 0 && p1 > 0) {
            data.rtt = (double) p1 / p2;
        }
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "Service onDestroy() called");
        
        isRunning = false;
        shouldKill = true; // Signal main thread to stop
        
        // Stop housekeeping thread
        if (housekeepingThread != null && housekeepingThread.isAlive()) {
            try {
                housekeepingThread.interrupt();
                housekeepingThread.join(1000); // Wait up to 1 second
                Log.i(TAG, "Housekeeping thread stopped");
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for housekeeping thread to stop");
            }
        }
        
        // Stop main thread
        if (connectionManagerThread != null) {
            try {
                connectionManagerThread.interrupt();
                connectionManagerThread.join(1000); // Wait up to 1 second
                Log.i(TAG, "Main SRTLA thread stopped");
            } catch (InterruptedException e) {
                Log.w(TAG, "Interrupted while waiting for main thread to stop");
            }
        }
        
        // Stop native SRTLA (this will clean up threads, sockets, etc.)
        try {
            shutdownBonding();
            Log.i(TAG, "Native SRTLA stopped successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error stopping native SRTLA: " + e.getMessage(), e);
        }
        
        // Clear connection data
        connectionDataMap.clear();
        addedNetworks.clear();
        networkToConnId.clear();
        connIdToNetwork.clear();
        virtualIpToConnId.clear();
        connIdToVirtualIp.clear();
        connectionCount = 0;
        
        // Clear instance reference
        instance = null;
        
        Log.i(TAG, "Service destroyed cleanly");
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    /**
     * Check if the service is currently running
     * @return true if service is active
     */
    public static boolean isServiceRunning() {
        return instance != null && instance.isRunning;
    }
    
    /**
     * Check if a specific network type is enabled
     * @param networkType - WIFI, CELLULAR, or ETHERNET
     * @return true if enabled, false if disabled
     */
    public static boolean isEnabled(NetworkType networkType) {
        return Boolean.TRUE.equals(connectionEnable.get(networkType));
    }
    
    /**
     * Convert network type string to NetworkType enum
     * @param networkTypeString - "wifi", "cellular", "ethernet", etc.
     * @return corresponding NetworkType enum value, or null if not recognized
     */
    private static NetworkType parseNetworkType(String networkTypeString) {
        if (networkTypeString == null) return null;
        
        String lower = networkTypeString.toLowerCase();
        switch (lower) {
            case "wifi":
                return NetworkType.WIFI;
            case "cellular":
            case "mobile":
                return NetworkType.CELLULAR;
            case "ethernet":
                return NetworkType.ETHERNET;
            default:
                Log.w(TAG, "Unknown network type: " + networkTypeString);
                return null;
        }
    }
    
    /**
     * Get basic connection statistics - only shows active connections
     * @return String with connection stats
     */
    public static String getConnectionStatistics() {
        if (instance == null || !instance.isRunning) {
            return "Service not running";
        }
        
        StringBuilder stats = new StringBuilder();
        stats.append("Native SRTLA Service\n");
        stats.append("Server: ").append(instance.serverInfo).append("\n");
        
        // Get only active connections for UI display
        ArrayList<ConnectionData> activeConnections = instance.getActiveConnections();
        stats.append("Active connections: ").append(activeConnections.size()).append("\n\n");
        
        // Add per-connection stats
        long totalBytesSent = 0;
        long totalBytesReceived = 0;
        for (ConnectionData data : activeConnections) {
            totalBytesSent += data.bytesSent;
            totalBytesReceived += data.bytesReceived;
            
            stats.append(String.format("%s: %s\n", data.networkType, data.connId));
            stats.append(String.format("  Window: %d, Inflight: %d, NAK: %d\n", 
                data.window, data.inflight, data.nak));
            stats.append(String.format("  Sent: %.1f KB (%d pkts)\n", 
                data.bytesSent / 1024.0, data.packetsSent));
            stats.append(String.format("  Recv: %.1f KB (%d pkts)\n", 
                data.bytesReceived / 1024.0, data.packetsReceived));
            stats.append(String.format("  Score: %d, Active: %s\n\n", 
                data.score, data.isActive ? "Yes" : "No"));
        }
        
        stats.append(String.format("Total: ↑%.1f KB ↓%.1f KB", 
            totalBytesSent / 1024.0, totalBytesReceived / 1024.0));
        
        return stats.toString();
    }
    
    /**
     * Advanced connection management - standard connection filtering pattern
     * Returns only the valid, latest connections for each network type
     */
    private ArrayList<ConnectionData> getActiveConnections() {
        ArrayList<ConnectionData> activeConnections = new ArrayList<>();
        
        // Phase 1: Find latest version for each network type (version tracking)
        Map<String, Integer> latestVersions = new HashMap<>();
        for (ConnectionData conn : connectionDataMap.values()) {
            String networkType = conn.networkType;
            int currentLatest = latestVersions.getOrDefault(networkType, 0);
            if (conn.version > currentLatest) {
                latestVersions.put(networkType, conn.version);
            }
        }
        
        // Phase 2: Process connections with version filtering and enable/disable logic
        Iterator<Map.Entry<String, ConnectionData>> it = connectionDataMap.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ConnectionData> entry = it.next();
            ConnectionData conn = entry.getValue();
            String networkType = conn.networkType;
            
            // Remove outdated connections (standard version cleanup)
            Integer latestVersion = latestVersions.get(networkType);
            if (latestVersion == null || conn.version != latestVersion) {
                Log.i(TAG, "Removing outdated connection: " + conn.connId + " (version " + conn.version + 
                      " vs latest " + latestVersion + ")");
                
                // Clean up native connection
                removeConnection(conn.connId);
                
                // Clean up mappings
                Network network = connIdToNetwork.get(conn.connId);
                if (network != null) {
                    networkToConnId.remove(network);
                    connIdToNetwork.remove(conn.connId);
                    String vIp = connIdToVirtualIp.remove(conn.connId);
                    if (vIp != null) virtualIpToConnId.remove(vIp);
                }
                
                it.remove(); // Actually remove from connectionDataMap
                connectionCount--;
                continue;
            }
            
            // Check if network type is enabled (connectionEnable filtering)
            NetworkType enumType = parseNetworkType(networkType);
            if (enumType != null && Boolean.TRUE.equals(connectionEnable.get(enumType))) {
                // Network is enabled - handle reconnection if needed (disable flag management)
                if (conn.needsDisable) {
                    // Re-enable the connection (clear disable flag)
                    Log.i(TAG, "Re-enabling connection: " + conn.connId);
                    conn.needsDisable = false;
                }
                
                // Handle reconnection logic
                if (conn.needsReconnect) {
                    Log.i(TAG, "Attempting to reconnect connection: " + conn.connId);
                    conn.needsReconnect = false;
                }
                
                // Add to active connections
                activeConnections.add(conn);
            } else {
                // Network is disabled - mark for disable but don't remove (preserve for re-enable)
                if (!conn.needsDisable) {
                    Log.i(TAG, "Disabling connection: " + conn.connId + " (type: " + networkType + ")");
                    conn.needsDisable = true;
                }
                // Don't add to activeConnections, but keep in connectionDataMap
            }
        }
        
        return activeConnections;
    }
    
    /**
     * Get connection window data for visualization - only shows active connections
     * @return List of ConnectionWindowData for the UI
     */
    public static java.util.List<ConnectionWindowView.ConnectionWindowData> getConnectionWindowData() {
        java.util.List<ConnectionWindowView.ConnectionWindowData> windowData = new ArrayList<>();
        
        if (instance == null || !instance.isRunning) {
            return windowData;
        }
        
        // Get only active connections for UI display
        ArrayList<ConnectionData> activeConnections = instance.getActiveConnections();
        
        for (ConnectionData data : activeConnections) {
            // Calculate bitrate (simple estimate from recent activity)
            double bitrateBps = 0;
            long timeSinceUpdate = System.currentTimeMillis() - data.lastUpdate;
            if (timeSinceUpdate < 5000 && data.bytesSent > 0) {
                bitrateBps = (data.bytesSent * 8.0 / timeSinceUpdate) * 1000.0; // bits per second
            }
            
            String state = data.isActive ? "ACTIVE" : "IDLE";
            boolean isSelected = data.inflight > 0; // Currently sending data
            
            ConnectionWindowView.ConnectionWindowData windowDataItem = 
                new ConnectionWindowView.ConnectionWindowData(
                    data.networkType,
                    data.window,
                    data.inflight,
                    data.score,
                    data.isActive,
                    isSelected,
                    (long) data.rtt,
                    state,
                    bitrateBps
                );
            
            windowData.add(windowDataItem);
        }
        
        return windowData;
    }
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                "srtla_native_service",
                "Native SRTLA Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Native SRTLA bonding service");
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
    
    private Notification createNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        );
        
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, "srtla_native_service");
        } else {
            builder = new Notification.Builder(this);
        }
        
        return builder
            .setContentTitle("Native SRTLA service")
            .setContentText("started")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .build();
    }
}
