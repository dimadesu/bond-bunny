package com.dimadesu.bondbunny;

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
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.net.InetAddress;

/**
 * Android foreground service for native SRTLA implementation.
 * All network management is delegated to {@link SrtlaSender}.
 */
public class NativeSrtlaService extends Service {
    private static final String TAG = "NativeSrtlaService";
    private static final int NOTIFICATION_ID = 1; // Same as startup notification - will update it
    public static final String CHANNEL_ID = "SRTLA_SERVICE_CHANNEL";

    // Service state
    private static boolean isServiceRunning = false;

    private SrtlaEngine engine;

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "NativeSrtlaService created");
        // Create notification channel
        createNotificationChannel(this);
        engine = new SrtlaEngine(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "NativeSrtlaService onStartCommand");

        if (intent != null) {
            String srtlaHost = intent.getStringExtra("srtla_host");
            String srtlaPort = intent.getStringExtra("srtla_port");
            String listenPort = intent.getStringExtra("listen_port");

            if (srtlaHost == null || srtlaPort == null || listenPort == null) {
                Log.e(TAG, "Missing required parameters");
                stopSelf();
                return START_NOT_STICKY;
            }

            // Start foreground service
            startForeground(NOTIFICATION_ID, createNotification("Starting native SRTLA..."));

            // Start native SRTLA in background thread
            final String host = srtlaHost;
            final String port = srtlaPort;
            final String lPort = listenPort;
            new Thread(() -> {
                try {
                    Log.i(TAG, "Starting native SRTLA process...");

                    // Validate inputs before starting
                    String validationError = validateSrtlaConfig(host, port);
                    if (validationError != null) {
                        handleStartupError(validationError);
                        return;
                    }

                    engine.startSrtla(host, port, lPort, new SrtlaEngine.Listener() {
                        @Override public void onSrtlaStatus(String message) { updateNotification(message); }
                        @Override public void onSrtlaError(String message)  {
                            Log.e(TAG, "Error starting native SRTLA: " + message);
                            updateNotification(message);
                            stopSelf();
                        }
                        @Override public void onRelaysChanged(java.util.List<SrtlaEngine.RelayInfo> relays) {}
                    });

                    if (NativeSrtlaJni.isRunningSrtlaNative()) {
                        isServiceRunning = true;
                    } else {
                        stopSelf();
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Error starting native SRTLA", e);
                    updateNotification("Error starting service: " + e.getMessage());
                    stopSelf();
                }
            }).start();
        }

        return START_STICKY; // Restart if killed by system
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "NativeSrtlaService onDestroy");
        engine.stopSrtla();

        // Wait a moment for the native process to actually stop
        new Thread(() -> {
            try {
                Thread.sleep(1000); // Wait 1 second

                // Check if native process actually stopped
                if (!NativeSrtlaJni.isRunningSrtlaNative()) {
                    Log.i(TAG, "Native SRTLA process confirmed stopped");
                    // Post a dismissible notification when service stops
                    postStoppedNotification("Service stopped");
                } else {
                    Log.w(TAG, "Native SRTLA process still running after stop signal");
                }
            } catch (InterruptedException e) {
                Log.w(TAG, "Stop monitoring interrupted", e);
            }
        }).start();

        isServiceRunning = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null; // Not a bound service
    }


    // -------------------------------------------------------------------------
    // Notifications
    // -------------------------------------------------------------------------

    /**
     * Create a notification with default settings for running service
     * (non-dismissible, does not auto-cancel)
     * @return A non-dismissible Notification configured for running service (ongoing=true, autoCancel=false)
     */
    private Notification createNotification(String contentText) {
        return createNotification(contentText, true, false);
    }

    /**
     * Create a notification with customizable dismissibility
     * @param contentText The text to display in the notification
     * @param ongoing If true, notification cannot be dismissed by user (for running service)
     * @param autoCancel If true, notification dismisses when tapped (for stopped service)
     * @return The configured Notification object
     */
    private Notification createNotification(String contentText, boolean ongoing, boolean autoCancel) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bond Bunny")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pendingIntent)
            .setOngoing(ongoing)
            .setAutoCancel(autoCancel)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }

    private void updateNotification(String contentText) {
        NotificationManager notificationManager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText));
    }

    private void postStoppedNotification(String contentText) {
        // Create a dismissible notification when service stops
        // Using same notification ID updates the existing notification in place
        NotificationManager notificationManager =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        notificationManager.notify(NOTIFICATION_ID, createNotification(contentText, false, true));
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    private void broadcastError(String errorMessage) {
        Intent intent = new Intent("srtla-error");
        intent.putExtra("error_message", errorMessage);
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
    }

    private void handleStartupError(String errorMessage) {
        Log.e(TAG, "Cannot start native SRTLA. Settings validation error: " + errorMessage);
        broadcastError(errorMessage);
        updateNotification("Settings validation error: " + errorMessage);
        stopSelf();
    }

    // -------------------------------------------------------------------------
    // Config validation
    // -------------------------------------------------------------------------

    private static String validateSrtlaConfig(String srtlaHost, String srtlaPort) {
        if (srtlaHost == null || srtlaHost.trim().isEmpty()) {
            return "Hostname is empty";
        }

        try {
            int port = Integer.parseInt(srtlaPort);

            // Actually resolve the hostname to test DNS
            Log.i(TAG, "Testing DNS resolution for: " + srtlaHost);
            InetAddress address = InetAddress.getByName(srtlaHost);
            if (address == null) {
                return "Cannot resolve hostname: " + srtlaHost;
            }
            Log.i(TAG, "DNS resolution successful: " + srtlaHost + " -> " + address.getHostAddress());

        } catch (NumberFormatException e) {
            return "Invalid port number: " + srtlaPort;
        } catch (java.net.UnknownHostException e) {
            return "Cannot resolve hostname: " + srtlaHost + " (DNS lookup failed)";
        } catch (Exception e) {
            return "Invalid hostname or port: " + srtlaHost + ":" + srtlaPort + " (" + e.getMessage() + ")";
        }

        return null; // No error
    }

    // -------------------------------------------------------------------------
    // Static API (used by MainActivity and SrtlaManager)
    // -------------------------------------------------------------------------

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
            // Log.i(TAG, "getNativeStats called, isServiceRunning=" + isServiceRunning);

            if (!isServiceRunning || !NativeSrtlaJni.isRunningSrtlaNative()) {
                // Log.i(TAG, "Native SRTLA not running, returning default message");
                return "No native SRTLA connections";
            }

            // Log.i(TAG, "Calling optimized native stats function...");
            // Single JNI call instead of 4 separate calls - much more efficient!
            String nativeStats = NativeSrtlaJni.getAllStats();

            // Add timestamp to see if values change over time
            // Log.i(TAG, "Stats timestamp: " + System.currentTimeMillis());
            // Log.i(TAG, "Native stats result: " + nativeStats);

            return nativeStats;

        } catch (Exception e) {
            Log.e(TAG, "Error getting native stats", e);
            return "Error getting native stats: " + e.getMessage();
        }
    }

    /**
     * Create notification channel (API 26+)
     */
    public static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "SRTLA Service";
            String description = "Notifications for SRTLA service status";
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);

            NotificationManager notificationManager = context.getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }
}
