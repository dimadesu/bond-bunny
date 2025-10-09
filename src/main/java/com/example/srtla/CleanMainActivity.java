package com.example.srtla;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Simplified MainActivity for the clean SRTLA implementation
 */
public class CleanMainActivity extends Activity {
    private static final String TAG = "CleanMainActivity";
    private static final String PREFS_NAME = "CleanSrtlaPrefs";
    
    // UI Components
    private EditText editServerHost;
    private EditText editServerPort;
    private EditText editLocalPort;
    private Button buttonStart;
    private Button buttonStop;
    private TextView textStatus;
    private TextView textConnectionStats;
    
    // State
    private boolean serviceRunning = false;
    private Handler uiHandler = new Handler();
    private Runnable statsUpdateRunnable;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        Log.i(TAG, "CleanMainActivity created");
        
        // Run SRTLA tests on startup
        runSRTLATests();
        
        // Create simple UI
        createSimpleUI();
        
        // Load saved preferences
        loadPreferences();
        
        // Set up UI handlers
        setupUIHandlers();
        
        // Start stats update loop
        startStatsUpdateLoop();
    }
    
    private void runSRTLATests() {
        new Thread(() -> {
            Log.i(TAG, "Starting SRTLA tests...");
            
            boolean basicTest = SRTLATest.testBasicFunctionality();
            boolean connectionTest = SRTLATest.testConnectionManagement();
            
            if (basicTest && connectionTest) {
                Log.i(TAG, "✅ All SRTLA tests passed - integration working!");
            } else {
                Log.e(TAG, "❌ SRTLA tests failed - basic: " + basicTest + ", connection: " + connectionTest);
            }
        }).start();
    }
    
    private void createSimpleUI() {
        // Create a simple layout programmatically
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(32, 32, 32, 32);
        
        // Title
        TextView title = new TextView(this);
        title.setText("Bond Bunny - Clean SRTLA");
        title.setTextSize(24);
        title.setPadding(0, 0, 0, 32);
        layout.addView(title);
        
        // Server Host
        TextView labelHost = new TextView(this);
        labelHost.setText("SRTLA Server Host:");
        layout.addView(labelHost);
        
        editServerHost = new EditText(this);
        editServerHost.setText("srtla.belabox.net");
        editServerHost.setSingleLine(true);
        layout.addView(editServerHost);
        
        // Server Port
        TextView labelServerPort = new TextView(this);
        labelServerPort.setText("SRTLA Server Port:");
        labelServerPort.setPadding(0, 16, 0, 0);
        layout.addView(labelServerPort);
        
        editServerPort = new EditText(this);
        editServerPort.setText("5000");
        editServerPort.setSingleLine(true);
        editServerPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        layout.addView(editServerPort);
        
        // Local Port
        TextView labelLocalPort = new TextView(this);
        labelLocalPort.setText("Local SRT Port:");
        labelLocalPort.setPadding(0, 16, 0, 0);
        layout.addView(labelLocalPort);
        
        editLocalPort = new EditText(this);
        editLocalPort.setText("6000");
        editLocalPort.setSingleLine(true);
        editLocalPort.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        layout.addView(editLocalPort);
        
        // Control buttons
        android.widget.LinearLayout buttonLayout = new android.widget.LinearLayout(this);
        buttonLayout.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        buttonLayout.setPadding(0, 32, 0, 16);
        
        buttonStart = new Button(this);
        buttonStart.setText("Start SRTLA");
        buttonStart.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, 
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        buttonLayout.addView(buttonStart);
        
        buttonStop = new Button(this);
        buttonStop.setText("Stop SRTLA");
        buttonStop.setLayoutParams(new android.widget.LinearLayout.LayoutParams(0, 
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f));
        buttonStop.setEnabled(false);
        buttonLayout.addView(buttonStop);
        
        layout.addView(buttonLayout);
        
        // Status display
        textStatus = new TextView(this);
        textStatus.setText("Status: Stopped");
        textStatus.setPadding(0, 16, 0, 0);
        layout.addView(textStatus);
        
        // Connection stats
        textConnectionStats = new TextView(this);
        textConnectionStats.setText("Connections: 0");
        textConnectionStats.setPadding(0, 8, 0, 0);
        layout.addView(textConnectionStats);
        
        setContentView(layout);
    }
    
    private void loadPreferences() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        
        editServerHost.setText(prefs.getString("server_host", "srtla.belabox.net"));
        editServerPort.setText(String.valueOf(prefs.getInt("server_port", 5000)));
        editLocalPort.setText(String.valueOf(prefs.getInt("local_port", 6000)));
    }
    
    private void savePreferences() {
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        
        editor.putString("server_host", editServerHost.getText().toString());
        editor.putInt("server_port", Integer.parseInt(editServerPort.getText().toString()));
        editor.putInt("local_port", Integer.parseInt(editLocalPort.getText().toString()));
        
        editor.apply();
    }
    
    private void setupUIHandlers() {
        buttonStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSrtlaService();
            }
        });
        
        buttonStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopSrtlaService();
            }
        });
    }
    
    private void startSrtlaService() {
        try {
            String serverHost = editServerHost.getText().toString().trim();
            int serverPort = Integer.parseInt(editServerPort.getText().toString().trim());
            int localPort = Integer.parseInt(editLocalPort.getText().toString().trim());
            
            if (serverHost.isEmpty()) {
                Toast.makeText(this, "Please enter server host", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Save preferences
            savePreferences();
            
            // Start the clean SRTLA service
            Intent intent = new Intent(this, CleanSrtlaService.class);
            intent.setAction("START_SRTLA");
            intent.putExtra("server_host", serverHost);
            intent.putExtra("server_port", serverPort);
            intent.putExtra("local_port", localPort);
            
            startService(intent);
            
            serviceRunning = true;
            updateUIState();
            
            Log.i(TAG, "SRTLA service start requested: " + serverHost + ":" + serverPort + " -> " + localPort);
            Toast.makeText(this, "Starting SRTLA service...", Toast.LENGTH_SHORT).show();
            
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Please enter valid port numbers", Toast.LENGTH_SHORT).show();
        }
    }
    
    private void stopSrtlaService() {
        Intent intent = new Intent(this, CleanSrtlaService.class);
        intent.setAction("STOP_SRTLA");
        startService(intent);
        
        serviceRunning = false;
        updateUIState();
        
        Log.i(TAG, "SRTLA service stop requested");
        Toast.makeText(this, "Stopping SRTLA service...", Toast.LENGTH_SHORT).show();
    }
    
    private void updateUIState() {
        buttonStart.setEnabled(!serviceRunning);
        buttonStop.setEnabled(serviceRunning);
        
        if (serviceRunning) {
            textStatus.setText("Status: Running");
        } else {
            textStatus.setText("Status: Stopped");
            textConnectionStats.setText("Connections: 0");
        }
    }
    
    private void startStatsUpdateLoop() {
        statsUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                updateConnectionStats();
                uiHandler.postDelayed(this, 1000); // Update every second
            }
        };
        uiHandler.post(statsUpdateRunnable);
    }
    
    private void updateConnectionStats() {
        if (!serviceRunning) return;
        
        CleanSrtlaService service = CleanSrtlaService.getInstance();
        if (service != null && service.isRunning()) {
            int connectionCount = service.getActiveConnectionCount();
            String[] stats = service.getConnectionStats();
            
            textConnectionStats.setText("Connections: " + connectionCount);
            
            // Update service status based on actual service state
            if (!service.isRunning() && serviceRunning) {
                serviceRunning = false;
                updateUIState();
            }
        } else if (serviceRunning) {
            // Service stopped but UI thinks it's running
            serviceRunning = false;
            updateUIState();
        }
    }
    
    @Override
    protected void onDestroy() {
        if (statsUpdateRunnable != null) {
            uiHandler.removeCallbacks(statsUpdateRunnable);
        }
        super.onDestroy();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        
        // Check actual service state
        CleanSrtlaService service = CleanSrtlaService.getInstance();
        if (service != null) {
            serviceRunning = service.isRunning();
            updateUIState();
        }
    }
}