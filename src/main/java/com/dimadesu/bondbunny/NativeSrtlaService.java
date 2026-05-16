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

/**
 * Foreground service shell for native SRTLA.
 * All network management is delegated to {@link SrtlaSender}.
 */
public class NativeSrtlaService extends Service {
    private static final String TAG = "NativeSrtlaService";
    private static final int NOTIFICATION_ID = 1;
    public static final String CHANNEL_ID = "SRTLA_SERVICE_CHANNEL";

    private static boolean isServiceRunning = false;

    private SrtlaSender sender;

    // -------------------------------------------------------------------------
    // Service lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel(this);
        sender = new SrtlaSender(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;

        String srtlaHost  = intent.getStringExtra("srtla_host");
        String srtlaPort  = intent.getStringExtra("srtla_port");
        String listenPort = intent.getStringExtra("listen_port");

        if (srtlaHost == null || srtlaPort == null || listenPort == null) {
            Log.e(TAG, "Missing parameters; stopping self");
            stopSelf();
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID, createNotification("Starting native SRTLA..."));

        // Validate config (DNS) and start on a background thread
        final String host  = srtlaHost;
        final String port  = srtlaPort;
        final String lPort = listenPort;
        new Thread(() -> {
            // Quick DNS / port validation
            String error = validateConfig(host, port);
            if (error != null) {
                Log.e(TAG, "Config error: " + error);
                broadcastError(error);
                updateNotification("Error: " + error);
                stopSelf();
                return;
            }

            sender.start(host, port, lPort, new SrtlaSender.Listener() {
                @Override public void onStatus(String message) { updateNotification(message); }
                @Override public void onError(String message)  {
                    Log.e(TAG, "SrtlaSender error: " + message);
                    broadcastError(message);
                    updateNotification("Error: " + message);
                    stopSelf();
                }
            });

            if (NativeSrtlaJni.isRunningSrtlaNative()) {
                isServiceRunning = true;
            } else {
                stopSelf();
            }
        }).start();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (sender != null) {
            sender.stop();
        }
        new Thread(() -> {
            try { Thread.sleep(1000); } catch (InterruptedException ignored) { }
            if (!NativeSrtlaJni.isRunningSrtlaNative()) {
                postStoppedNotification("Service stopped");
            }
        }).start();
        isServiceRunning = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // -------------------------------------------------------------------------
    // Notifications
    // -------------------------------------------------------------------------

    private Notification createNotification(String text) {
        return createNotification(text, true, false);
    }

    private Notification createNotification(String text, boolean ongoing, boolean autoCancel) {
        Intent tap = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, tap,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Bond Bunny")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(pi)
                .setOngoing(ongoing)
                .setAutoCancel(autoCancel)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, createNotification(text));
    }

    private void postStoppedNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(NOTIFICATION_ID, createNotification(text, false, true));
    }

    // -------------------------------------------------------------------------
    // Error broadcast
    // -------------------------------------------------------------------------

    private void broadcastError(String message) {
        Intent i = new Intent("srtla-error");
        i.putExtra("error_message", message);
        LocalBroadcastManager.getInstance(this).sendBroadcast(i);
    }

    // -------------------------------------------------------------------------
    // Config validation (DNS + port sanity)
    // -------------------------------------------------------------------------

    private static String validateConfig(String host, String port) {
        if (host == null || host.trim().isEmpty()) return "Hostname is empty";
        try {
            Integer.parseInt(port);
            java.net.InetAddress.getByName(host);
        } catch (NumberFormatException e) {
            return "Invalid port: " + port;
        } catch (java.net.UnknownHostException e) {
            return "Cannot resolve hostname: " + host;
        }
        return null;
    }

    // -------------------------------------------------------------------------
    // Static API (used by MainActivity and SrtlaManager)
    // -------------------------------------------------------------------------

    public static boolean isServiceRunning() {
        try {
            return isServiceRunning && NativeSrtlaJni.isRunningSrtlaNative();
        } catch (Exception e) {
            return isServiceRunning;
        }
    }

    public static void startService(Context ctx, String host, String port, String listenPort) {
        Intent i = new Intent(ctx, NativeSrtlaService.class);
        i.putExtra("srtla_host",  host);
        i.putExtra("srtla_port",  port);
        i.putExtra("listen_port", listenPort);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stopService(Context ctx) {
        ctx.stopService(new Intent(ctx, NativeSrtlaService.class));
    }

    public static void syncState() {
        if (isServiceRunning && !NativeSrtlaJni.isRunningSrtlaNative()) {
            Log.w(TAG, "State sync: service flag reset (native stopped)");
            isServiceRunning = false;
        }
    }

    public static String getNativeStats() {
        if (!isServiceRunning || !NativeSrtlaJni.isRunningSrtlaNative()) {
            return "No native SRTLA connections";
        }
        try {
            return NativeSrtlaJni.getAllStats();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    public static void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "SRTLA Service", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Notifications for SRTLA service status");
            context.getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }
}
