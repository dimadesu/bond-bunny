package com.dimadesu.bondbunny

import android.content.Context
import android.util.Log
import com.dimadesu.bondbunny.moblink.MoblinkStreamer
import com.dimadesu.bondbunny.moblink.MoblinkStreamerListener
import com.dimadesu.bondbunny.moblink.ThermalState
import android.os.Build
import android.provider.Settings
import java.util.Locale

/**
 * Unified engine that owns both [SrtlaSender] (native SRTLA bonding) and
 * [MoblinkStreamer] (WebSocket relay server), with internal wiring between them.
 *
 * ### Why this class exists
 *
 * Every app that uses SRTLA + Moblink needs the same glue:
 * - `onRelayTunnelReady` → `sender.addMoblinkRelay()`
 * - `onRelayTunnelClosed` → `sender.removeMoblinkRelay()`
 * - `connectToSrtla()` when SRTLA starts, reset when SRTLA stops
 *
 * `SrtlaEngine` encapsulates this wiring so apps don't duplicate it.
 *
 * ### Lifecycle
 *
 * **Phase 1 — Moblink server (call early, before stream start):**
 * ```
 * engine.startMoblink("MyDevice", "password", 7777)
 * ```
 * Relays connect and authenticate. No video flows yet.
 *
 * **Phase 2 — SRTLA proxy (call when starting a stream):**
 * ```
 * engine.startSrtla(host, port, listenPort)
 * ```
 * If Moblink is active, tunnels are activated automatically.
 *
 * **Stream stop:**
 * ```
 * engine.stopSrtla()
 * ```
 * Parks Moblink relays (resets destination). WebSocket server stays alive.
 *
 * **Full teardown:**
 * ```
 * engine.stopAll()
 * ```
 */
class SrtlaEngine(private val context: Context) {

    companion object {
        private const val TAG = "SrtlaEngine"

        /** Default WebSocket port for the Moblink server. */
        @JvmField
        val DEFAULT_MOBLINK_PORT = MoblinkStreamer.DEFAULT_PORT
    }

    // -------------------------------------------------------------------------
    // Public types
    // -------------------------------------------------------------------------

    /** Snapshot of a connected Moblink relay's state. */
    data class RelayInfo(
        @JvmField val id: String,
        @JvmField val name: String,
        @JvmField val battery: Int?,
        @JvmField val thermal: ThermalState?,
        @JvmField val tunnelActive: Boolean,
    )

    /** Callback interface for status and relay events. */
    interface Listener {
        /** SRTLA native status message (e.g. "Service is running on port 6001"). */
        fun onSrtlaStatus(message: String) {}

        /** SRTLA native error. */
        fun onSrtlaError(message: String) {}

        /** Called whenever the relay list changes (connect, disconnect, tunnel, status). */
        fun onRelaysChanged(relays: List<RelayInfo>) {}
    }

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------

    private var sender: SrtlaSender? = null
    private var moblinkStreamer: MoblinkStreamer? = null
    private var externalListener: Listener? = null

    /** SRTLA receiver address — saved for Moblink tunnel activation. */
    private var srtlaHost: String = ""
    private var srtlaPort: Int = 0

    /** True when the native SRTLA thread is running. */
    val isRunning: Boolean get() = NativeSrtlaJni.isRunningSrtlaNative()

    /** Internal relay map keyed by relay ID. */
    private val relayMap = LinkedHashMap<String, RelayInfo>()

    /** Current snapshot of connected relays. Thread-safe read via copy-on-write. */
    @Volatile
    var relays: List<RelayInfo> = emptyList()
        private set

    private fun publishRelays() {
        val snapshot = relayMap.values.toList()
        relays = snapshot
        externalListener?.onRelaysChanged(snapshot)
    }

    /**
     * Register (or replace) the external listener for relay and SRTLA events.
     *
     * Useful when the host wants relay callbacks **before** calling [startSrtla],
     * e.g. Bond Bunny starting Moblink early in the Activity.
     */
    fun setListener(listener: Listener?) {
        externalListener = listener
    }

    // -------------------------------------------------------------------------
    // Phase 1: Moblink server lifecycle
    // -------------------------------------------------------------------------

    /**
     * Start (or restart) the Moblink WebSocket server.
     *
     * If called while already running, stops the old server first (matching Moblin's
     * `reloadMoblinkStreamer()` pattern for config changes).
     *
     * If SRTLA is already running, tunnels are activated immediately.
     */
    fun startMoblink(password: String, port: Int) {
        val name = getDeviceName(context)
        val current = moblinkStreamer
        if (current != null) {
            Log.i(TAG, "Restarting Moblink server with new config")
            current.stop()
            moblinkStreamer = null
            relayMap.clear()
            publishRelays()
        }

        Log.i(TAG, "Starting Moblink server (port=$port, name='$name')")
        val streamer = MoblinkStreamer(context, name, password, port)
        streamer.start(makeMoblinkListener())
        moblinkStreamer = streamer

        // If SRTLA is already running (mid-stream enable), activate tunnels immediately
        if (isRunning && srtlaPort != 0) {
            Log.i(TAG, "SRTLA already running — activating Moblink tunnels → $srtlaHost:$srtlaPort")
            streamer.connectToSrtla(srtlaHost, srtlaPort)
        }
    }

    /**
     * Stop the Moblink WebSocket server and disconnect all relays.
     * The SRTLA proxy is unaffected.
     */
    fun stopMoblink() {
        val streamer = moblinkStreamer ?: return
        Log.i(TAG, "Stopping Moblink server")
        streamer.stop()
        moblinkStreamer = null
        relayMap.clear()
        publishRelays()
    }

    // -------------------------------------------------------------------------
    // Phase 2: SRTLA proxy lifecycle
    // -------------------------------------------------------------------------

    /**
     * Start the native SRTLA bonding proxy.
     *
     * If the Moblink server is active, tunnels are activated automatically for all
     * waiting relays.
     *
     * @param listener Optional listener for SRTLA status/error events and relay changes.
     */
    fun startSrtla(
        host: String,
        port: String,
        listenPort: String,
        listener: Listener? = null,
    ) {
        if (isRunning) {
            Log.i(TAG, "SRTLA already running — skipping start")
            return
        }

        externalListener = listener

        Log.i(TAG, "Starting SRTLA: $host:$port, listen on $listenPort")

        val s = SrtlaSender(context)
        sender = s

        s.start(host, port, listenPort, object : SrtlaSender.Listener {
            override fun onStatus(message: String) {
                Log.i(TAG, "SrtlaSender: $message")
                externalListener?.onSrtlaStatus(message)
            }
            override fun onError(message: String) {
                Log.e(TAG, "SrtlaSender error: $message")
                externalListener?.onSrtlaError(message)
            }
        })

        // Save destination for Moblink tunnel activation
        srtlaHost = host
        srtlaPort = port.toIntOrNull() ?: 0

        // Activate Moblink tunnels if server is running
        val streamer = moblinkStreamer
        if (streamer != null && srtlaPort != 0) {
            Log.i(TAG, "Activating Moblink relay tunnels → $host:$port")
            streamer.connectToSrtla(srtlaHost, srtlaPort)
        }
    }

    /**
     * Stop the SRTLA proxy and release network resources.
     * Moblink relays are parked (destination reset) but stay WebSocket-connected.
     */
    fun stopSrtla() {
        Log.i(TAG, "Stopping SRTLA")

        // Park Moblink relays before stopping SRTLA so tunnel tracking is still valid
        val streamer = moblinkStreamer
        if (streamer != null) {
            Log.i(TAG, "Parking Moblink relays — they will wait for next stream")
            streamer.connectToSrtla("", 0)
        }

        sender?.stop()
        sender = null
        externalListener = null
        srtlaHost = ""
        srtlaPort = 0
    }

    /**
     * Stop everything — both SRTLA and Moblink.
     * Call this from `onDestroy()` or equivalent teardown.
     */
    fun stopAll() {
        stopSrtla()
        stopMoblink()
    }

    // -------------------------------------------------------------------------
    // Internal Moblink listener
    // -------------------------------------------------------------------------

    private fun makeMoblinkListener() = object : MoblinkStreamerListener {

        override fun onRelayConnected(relayId: String, name: String) {
            Log.i(TAG, "Moblink relay connected: '$name'")
            relayMap[relayId] = RelayInfo(relayId, name, null, null, tunnelActive = false)
            publishRelays()
        }

        override fun onRelayDisconnected(relayId: String) {
            Log.i(TAG, "Moblink relay disconnected: $relayId")
            relayMap.remove(relayId)
            publishRelays()
        }

        override fun onRelayTunnelReady(relayId: String, name: String, host: String, port: Int) {
            Log.i(TAG, "Moblink relay tunnel ready: '$name' @ $host:$port")
            sender?.addMoblinkRelay(relayId, name, host, port)
            val existing = relayMap[relayId]
            relayMap[relayId] = (existing ?: RelayInfo(relayId, name, null, null, false))
                .copy(tunnelActive = true)
            publishRelays()
        }

        override fun onRelayTunnelClosed(relayId: String, host: String, port: Int) {
            Log.i(TAG, "Moblink relay tunnel closed: $relayId @ $host:$port")
            sender?.removeMoblinkRelay(relayId)
            val existing = relayMap[relayId]
            if (existing != null) {
                relayMap[relayId] = existing.copy(tunnelActive = false)
                publishRelays()
            }
        }

        override fun onRelayStatus(
            relayId: String,
            name: String,
            batteryPercentage: Int?,
            thermalState: ThermalState?,
        ) {
            val existing = relayMap[relayId]
            relayMap[relayId] = (existing ?: RelayInfo(relayId, name, null, null, false))
                .copy(battery = batteryPercentage, thermal = thermalState)
            publishRelays()
        }

        override fun onLog(message: String) {
            Log.i(TAG, "Moblink: $message")
        }
    }
    // -------------------------------------------------------------------------
    // Utils
    // -------------------------------------------------------------------------

    private fun getDeviceName(context: Context): String {
        val deviceName = Settings.Global.getString(context.contentResolver, "device_name")
        if (!deviceName.isNullOrBlank()) {
            return deviceName
        }
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        if (model.startsWith(manufacturer)) {
            return model.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
        }
        return manufacturer.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() } + " " + model
    }
}
