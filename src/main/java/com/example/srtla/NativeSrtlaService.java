package com.example.srtla;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

/**
 * Android foreground service for native SRTLA implementation
 */
public class NativeSrtlaService extends Service {
    private static final String TAG = "NativeSrtlaService";
    private static final int NOTIFICATION_ID = 1; // Same as startup notification - will update it
    
    // Service state
    private static boolean isServiceRunning = false;
    private String srtlaHost;
    private String srtlaPort;
    private String listenPort;
    
    // Native methods are accessed through NativeSrtlaJni wrapper
    
    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "NativeSrtlaService created");
        // Use the same notification channel as EnhancedSrtlaService
        EnhancedSrtlaService.createNotificationChannel(this);
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "NativeSrtlaService onStartCommand");
        
        if (intent != null) {
            srtlaHost = intent.getStringExtra("srtla_host");
            srtlaPort = intent.getStringExtra("srtla_port");
            listenPort = intent.getStringExtra("listen_port");
            
            if (srtlaHost == null || srtlaPort == null || listenPort == null) {
                Log.e(TAG, "Missing required parameters");
                stopSelf();
                return START_NOT_STICKY;
            }
            
            // Start foreground service
            startForeground(NOTIFICATION_ID, createNotification("Starting native SRTLA..."));
            
            // Start native SRTLA in background thread
            new Thread(this::startNativeSrtla).start();
        }
        
        return START_STICKY; // Restart if killed by system
    }
    
    @Override
    public void onDestroy() {
        Log.i(TAG, "NativeSrtlaService onDestroy");
        stopNativeSrtla();
        isServiceRunning = false;
        super.onDestroy();
    }
    
    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }
    
    private void startNativeSrtla() {
        try {
            Log.i(TAG, "Starting native SRTLA process...");
            
            // Create IPs file with real network interfaces
            File ipsFile = createNetworkIpsFile();
            
            // Update notification
            updateNotification("Starting native SRTLA...");
            
            // Start native SRTLA
            int result = NativeSrtlaJni.startSrtlaNative(listenPort, srtlaHost, srtlaPort, ipsFile.getAbsolutePath());
            
            if (result == 0) {
                Log.i(TAG, "Native SRTLA started successfully");
                // Verify native state before marking as running
                if (NativeSrtlaJni.isRunningSrtlaNative()) {
                    isServiceRunning = true;
                    updateNotification("Native SRTLA running on port " + listenPort);
                } else {
                    Log.w(TAG, "Native SRTLA start returned 0 but process is not running");
                    updateNotification("Native SRTLA failed to start");
                    stopSelf();
                }
            } else {
                Log.e(TAG, "Native SRTLA failed to start with code: " + result);
                updateNotification("Native SRTLA failed to start (code: " + result + ")");
                stopSelf();
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error starting native SRTLA", e);
            updateNotification("Native SRTLA error: " + e.getMessage());
            stopSelf();
        }
    }
    
    private void stopNativeSrtla() {
        try {
            Log.i(TAG, "Stopping native SRTLA process...");
            updateNotification("Stopping native SRTLA...");
            
            int result = NativeSrtlaJni.stopSrtlaNative();
            if (result == 0) {
                Log.i(TAG, "Native SRTLA stop signal sent successfully");
                
                // Wait a moment for the native process to actually stop
                new Thread(() -> {
                    try {
                        Thread.sleep(1000); // Wait 1 second
                        
                        // Check if native process actually stopped
                        if (!NativeSrtlaJni.isRunningSrtlaNative()) {
                            Log.i(TAG, "Native SRTLA process confirmed stopped");
                            updateNotification("Native SRTLA stopped");
                        } else {
                            Log.w(TAG, "Native SRTLA process still running after stop signal");
                            updateNotification("Native SRTLA stopping...");
                        }
                    } catch (InterruptedException e) {
                        Log.w(TAG, "Stop monitoring interrupted", e);
                    }
                }).start();
                
            } else {
                Log.w(TAG, "Native SRTLA stop returned code: " + result);
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Error stopping native SRTLA", e);
        } finally {
            isServiceRunning = false;
        }
    }
    
    private File createNetworkIpsFile() throws IOException {
        File ipsFile = new File(getFilesDir(), "native_srtla_ips.txt");
        
        // Delete existing file
        if (ipsFile.exists()) {
            ipsFile.delete();
            Log.i(TAG, "Deleted existing IPs file");
        }
        
        List<String> networkIps = getRealNetworkIPs();
        if (networkIps.isEmpty()) {
            throw new RuntimeException("No network interfaces found - device may not be connected to any networks");
        }
        
        try (FileWriter writer = new FileWriter(ipsFile, false)) {
            for (String ip : networkIps) {
                writer.write(ip + "\n");
                Log.i(TAG, "Writing IP to file: " + ip);
            }
            writer.flush();
        }
        
        Log.i(TAG, "Created IPs file: " + ipsFile.getAbsolutePath() + 
              " (size: " + ipsFile.length() + " bytes)");
        
        return ipsFile;
    }
    
    private List<String> getRealNetworkIPs() {
        List<String> ips = new ArrayList<>();
        
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                
                // Skip loopback and inactive interfaces
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
                
                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    
                    // Only IPv4, not loopback, not link-local
                    if (address instanceof Inet4Address && 
                        !address.isLoopbackAddress() && 
                        !address.isLinkLocalAddress()) {
                        
                        String ip = address.getHostAddress();
                        ips.add(ip);
                        Log.i(TAG, "Found network IP: " + ip + 
                              " on interface: " + networkInterface.getName());
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error getting network IPs", e);
        }
        
        return ips;
    }
    

    
    private Notification createNotification(String contentText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, 
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        
        return new NotificationCompat.Builder(this, EnhancedSrtlaService.CHANNEL_ID)
            .setContentTitle("Bond Bunny")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
    
    private void updateNotification(String contentText) {
        NotificationManager notificationManager = 
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText));
    }
    
    // Static methods for external access
    public static boolean isServiceRunning() {
        // Check both service state and native state for accuracy
        try {
            return isServiceRunning && NativeSrtlaJni.isRunningSrtlaNative();
        } catch (Exception e) {
            Log.w(TAG, "Error checking native SRTLA state", e);
            return isServiceRunning;
        }
    }
    
    public static void startService(Context context, String srtlaHost, String srtlaPort, String listenPort) {
        Intent intent = new Intent(context, NativeSrtlaService.class);
        intent.putExtra("srtla_host", srtlaHost);
        intent.putExtra("srtla_port", srtlaPort);
        intent.putExtra("listen_port", listenPort);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }
    
    public static void stopService(Context context) {
        Intent intent = new Intent(context, NativeSrtlaService.class);
        context.stopService(intent);
    }
    
    /**
     * Sync internal state with actual native state
     * Call this periodically to ensure consistency
     */
    public static void syncState() {
        try {
            boolean nativeRunning = NativeSrtlaJni.isRunningSrtlaNative();
            if (isServiceRunning && !nativeRunning) {
                Log.w(TAG, "State sync: Service thinks it's running but native is stopped");
                isServiceRunning = false;
            }
        } catch (Exception e) {
            Log.w(TAG, "Error syncing state", e);
        }
    }
    
    /**
     * Get simple statistics from native SRTLA
     * @return formatted statistics string
     */
    public static String getNativeStats() {
        try {
            Log.i(TAG, "getNativeStats called, isServiceRunning=" + isServiceRunning);
            
            if (!isServiceRunning || !NativeSrtlaJni.isRunningSrtlaNative()) {
                Log.i(TAG, "Native SRTLA not running, returning default message");
                return "No native SRTLA connections";
            }
            
            Log.i(TAG, "Calling native stats functions...");
            int totalConnections = NativeSrtlaJni.getConnectionCount();
            int activeConnections = NativeSrtlaJni.getActiveConnectionCount();
            int inFlightPackets = NativeSrtlaJni.getTotalInFlightPackets();
            int totalWindow = NativeSrtlaJni.getTotalWindowSize();
            
            Log.i(TAG, String.format("Native stats: total=%d, active=%d, inflight=%d, window=%d", 
                                   totalConnections, activeConnections, inFlightPackets, totalWindow));
            
            // Add timestamp to see if values change over time
            Log.i(TAG, "Stats timestamp: " + System.currentTimeMillis());
            
            StringBuilder stats = new StringBuilder();
            stats.append("📡 Native SRTLA Stats\n");
            stats.append(String.format("Connections: %d total, %d active\n", 
                                     totalConnections, activeConnections));
            stats.append(String.format("In-flight packets: %d\n", inFlightPackets));
            stats.append(String.format("Total window size: %d\n", totalWindow));
            
            if (activeConnections > 0) {
                int avgWindow = totalWindow / activeConnections;
                stats.append(String.format("Avg window per connection: %d", avgWindow));
            }
            
            return stats.toString();
            
        } catch (Exception e) {
            Log.e(TAG, "Error getting native stats", e);
            return "Error getting native stats: " + e.getMessage();
        }
    }
}