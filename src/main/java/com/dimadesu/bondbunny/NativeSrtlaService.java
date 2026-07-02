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

import com.dimadesu.bondbunny.moblink.MoblinkManager;
import com.dimadesu.bondbunny.moblink.ThermalState;

import java.net.InetAddress;
import java.util.LinkedHashMap;

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

    private SrtlaSender sender;

    // Optional Moblink manager: lets spare devices act as extra SRTLA bonding links.
    private MoblinkManager moblinkManager;

    // Live state of connected Moblink relays, keyed by relay id (for UI display).
    private final LinkedHashMap<String, RelayUi> moblinkRelays = new LinkedHashMap<>();

    private static class RelayUi {
        String name;
        String endpoint;
        Integer battery;
        String thermal;
    }

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        Log.i(TAG, "NativeSrtlaService created");
        // Create notification channel
        createNotificationChannel(this);
        sender = new SrtlaSender(this);
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

            final boolean moblinkEnabled = intent.getBooleanExtra("moblink_enabled", false);
            final String moblinkName = intent.getStringExtra("moblink_name");
            final String moblinkPassword = intent.getStringExtra("moblink_password");
            final int moblinkPort = intent.getIntExtra("moblink_port", MoblinkManager.DEFAULT_PORT);

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

                    sender.start(host, port, lPort, new SrtlaSender.Listener() {
                        @Override public void onStatus(String message) { updateNotification(message); }
                        @Override public void onError(String message)  {
                            Log.e(TAG, "Error starting native SRTLA: " + message);
                            updateNotification(message);
                            stopSelf();
                        }
                    });

                    if (NativeSrtlaJni.isRunningSrtlaNative()) {
                        isServiceRunning = true;
                        if (moblinkEnabled && moblinkPassword != null && !moblinkPassword.isEmpty()) {
                            startMoblinkStreamer(host, Integer.parseInt(port),
                                    moblinkName, moblinkPassword, moblinkPort);
                        }
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
        if (moblinkManager != null) {
            moblinkManager.stop();
            moblinkManager = null;
        }
        synchronized (moblinkRelays) {
            moblinkRelays.clear();
        }
        broadcastMoblinkStatus();
        if (sender != null) {
            sender.stop();
        }

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
    // Moblink streamer
    // -------------------------------------------------------------------------

    /**
     * Start the Moblink streamer so spare devices can join as extra SRTLA bonding links.
     * Each relay tunnels to the SRTLA receiver ({@code destHost}:{@code destPort}); ready relays
     * are registered with {@link SrtlaSender} as additional connections.
     */
    private void startMoblinkStreamer(String destHost, int destPort, String name,
                                      String password, int port) {
        try {
            String streamerName = (name != null && !name.isEmpty()) ? name : "Bond Bunny";
            moblinkManager = new MoblinkManager(this, streamerName, password, port);
            moblinkManager.start(new MoblinkManager.Listener() {
                @Override
                public void onRelayTunnelReady(String relayId, String relayName,
                                               String relayHost, int relayPort) {
                    if (sender != null) {
                        sender.addMoblinkRelay(relayId, relayHost, relayPort);
                    }
                    synchronized (moblinkRelays) {
                        RelayUi r = moblinkRelays.get(relayId);
                        if (r == null) {
                            r = new RelayUi();
                            moblinkRelays.put(relayId, r);
                        }
                        r.name = relayName;
                        r.endpoint = relayHost + ":" + relayPort;
                    }
                    broadcastMoblinkStatus();
                }

                @Override
                public void onRelayTunnelClosed(String relayId, String relayHost, int relayPort) {
                    if (sender != null) {
                        sender.removeMoblinkRelay(relayId);
                    }
                    synchronized (moblinkRelays) {
                        moblinkRelays.remove(relayId);
                    }
                    broadcastMoblinkStatus();
                }

                @Override
                public void onRelayStatus(String relayId, String relayName,
                                          Integer batteryPercentage, ThermalState thermalState) {
                    Log.i(TAG, "Moblink relay '" + relayName + "' battery=" + batteryPercentage
                            + " thermal=" + thermalState);
                    synchronized (moblinkRelays) {
                        RelayUi r = moblinkRelays.get(relayId);
                        if (r == null) {
                            r = new RelayUi();
                            r.name = relayName;
                            moblinkRelays.put(relayId, r);
                        }
                        r.battery = batteryPercentage;
                        r.thermal = thermalLabel(thermalState);
                    }
                    broadcastMoblinkStatus();
                }

                @Override
                public void onRelayDisconnected(String relayId) {
                    // Relay tracking is handled by onRelayTunnelClosed.
                }

                @Override
                public void onLog(String message) {
                    Log.i(TAG, "Moblink: " + message);
                }
            });
            // Bond Bunny: both phases fire immediately — no waiting-room delay for user.
            moblinkManager.connectToSrtla(destHost, destPort);
            Log.i(TAG, "Moblink manager started on port " + port + " (destination "
                    + destHost + ":" + destPort + ")");
        } catch (Exception e) {
            Log.e(TAG, "Failed to start Moblink manager", e);
        }
    }

    private static String thermalLabel(ThermalState t) {
        if (t == null) {
            return null;
        }
        switch (t) {
            case WHITE: return "\uD83D\uDFE2"; // green circle
            case YELLOW: return "\uD83D\uDFE1"; // yellow circle
            case RED: return "\uD83D\uDD34"; // red circle
            default: return null;
        }
    }

    /** Broadcast the current Moblink relay summary for the UI. */
    private void broadcastMoblinkStatus() {
        StringBuilder sb = new StringBuilder();
        int count;
        synchronized (moblinkRelays) {
            count = moblinkRelays.size();
            for (RelayUi r : moblinkRelays.values()) {
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append("\u2022 ").append(r.name == null || r.name.isEmpty() ? "Relay" : r.name);
                if (r.endpoint != null) {
                    sb.append(" (").append(r.endpoint).append(")");
                }
                if (r.battery != null) {
                    sb.append(" \u2014 ").append(r.battery).append("%");
                }
                if (r.thermal != null) {
                    sb.append(" ").append(r.thermal);
                }
            }
        }
        Intent intent = new Intent("moblink-status");
        intent.putExtra("count", count);
        intent.putExtra("summary", sb.toString());
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent);
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
        startService(context, srtlaHost, srtlaPort, listenPort,
                false, null, null, MoblinkManager.DEFAULT_PORT);
    }

    public static void startService(Context context, String srtlaHost, String srtlaPort, String listenPort,
                                    boolean moblinkEnabled, String moblinkName, String moblinkPassword,
                                    int moblinkPort) {
        Intent intent = new Intent(context, NativeSrtlaService.class);
        intent.putExtra("srtla_host", srtlaHost);
        intent.putExtra("srtla_port", srtlaPort);
        intent.putExtra("listen_port", listenPort);
        intent.putExtra("moblink_enabled", moblinkEnabled);
        intent.putExtra("moblink_name", moblinkName);
        intent.putExtra("moblink_password", moblinkPassword);
        intent.putExtra("moblink_port", moblinkPort);

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
