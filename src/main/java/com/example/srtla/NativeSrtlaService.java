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
    public static final String CHANNEL_ID = "NativeSrtlaServiceChannel";
    private static final int NOTIFICATION_ID = 2; // Different from EnhancedSrtlaService
    
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
        createNotificationChannel();
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
                isServiceRunning = true;
                updateNotification("Native SRTLA running on port " + listenPort);
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
                Log.i(TAG, "Native SRTLA stopped successfully");
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
    
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Native SRTLA Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Native SRTLA background service");
            channel.setShowBadge(false);
            
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
    
    private Notification createNotification(String contentText) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent, 
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );
        
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Native SRTLA Service")
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
        return isServiceRunning;
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
    
    public static String getServiceStatus() {
        if (isServiceRunning) {
            return "Native SRTLA service is running";
        } else {
            return "Native SRTLA service is stopped";
        }
    }
}