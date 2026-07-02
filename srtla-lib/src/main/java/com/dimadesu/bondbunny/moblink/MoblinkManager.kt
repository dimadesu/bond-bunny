package com.dimadesu.bondbunny.moblink

import android.content.Context
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * High-level manager for the Moblink relay server.
 *
 * Wraps [MoblinkStreamer] and adds:
 * - A 10-second periodic status poll (battery / thermal) matching the Moblin iOS cadence.
 * - Two-phase lifecycle so relays can pre-connect before the SRTLA stream starts.
 *
 * ### Usage
 *
 * **Phase 1 — start the server (call as early as possible):**
 * ```
 * manager.start(listener)
 * ```
 * Relays will connect, authenticate, and report status. No video traffic flows yet.
 *
 * **Phase 2 — activate tunnels (call when SRTLA is running):**
 * ```
 * manager.connectToSrtla(host, port)
 * ```
 * All waiting relays get a StartTunnel request and immediately start forwarding video.
 * Future relays connecting after this point also tunnel immediately.
 *
 * **Reset between streams (optional):**
 * ```
 * manager.connectToSrtla("", 0)
 * ```
 * Clears the destination so future relays park again. Existing WebSocket connections
 * (and their battery/thermal polling) continue unaffected.
 *
 * **Teardown:**
 * ```
 * manager.stop()
 * ```
 */
class MoblinkManager(
    private val context: Context,
    private val name: String,
    private val password: String,
    private val port: Int = MoblinkStreamer.DEFAULT_PORT,
) {
    companion object {
        private const val TAG = "MoblinkManager"
        private const val STATUS_POLL_INTERVAL_SECONDS = 10L

        /** Default WebSocket port for the Moblink server. */
        @JvmField
        val DEFAULT_PORT = MoblinkStreamer.DEFAULT_PORT
    }

    /**
     * Listener for relay lifecycle and status events.
     * All callbacks are invoked on Java-WebSocket worker threads — implementations must be
     * thread-safe and must not block.
     */
    abstract class Listener {
        /**
         * A relay authenticated successfully. It may not have an active tunnel yet if
         * [connectToSrtla] has not been called yet.
         */
        open fun onRelayConnected(relayId: String, name: String) {}

        /** A relay WebSocket connection closed (regardless of tunnel state). */
        open fun onRelayDisconnected(relayId: String) {}

        /** Battery / thermal update received from a relay. */
        open fun onRelayStatus(
            relayId: String,
            name: String,
            batteryPercentage: Int?,
            thermalState: ThermalState?,
        ) {}

        /** A relay tunnel is active and ready to carry SRTLA traffic. */
        abstract fun onRelayTunnelReady(relayId: String, name: String, host: String, port: Int)

        /** A relay tunnel has closed. */
        abstract fun onRelayTunnelClosed(relayId: String, host: String, port: Int)

        open fun onLog(message: String) {}
    }

    private var streamer: MoblinkStreamer? = null
    private var scheduler: ScheduledExecutorService? = null
    private var externalListener: Listener? = null

    /** True while the WebSocket server is running (Phase 1 active). */
    @Volatile var isStarted: Boolean = false
        private set

    /**
     * Start the Moblink WebSocket server and mDNS advertisement (Phase 1).
     * Idempotent: calling while already started is a no-op.
     */
    fun start(listener: Listener) {
        if (isStarted) {
            Log.i(TAG, "Already started — skipping")
            return
        }
        externalListener = listener
        Log.i(TAG, "Starting MoblinkManager (port=$port)")

        val s = MoblinkStreamer(context, name, password, port)
        streamer = s
        s.start(makeStreamerListener())
        isStarted = true

        // Start 10-second periodic status poll (mirrors Moblin iOS behaviour).
        val sched = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "MoblinkStatusPoller").also { it.isDaemon = true }
        }
        sched.scheduleAtFixedRate(
            { updateStatus() },
            STATUS_POLL_INTERVAL_SECONDS,
            STATUS_POLL_INTERVAL_SECONDS,
            TimeUnit.SECONDS,
        )
        scheduler = sched
        Log.i(TAG, "MoblinkManager started — waiting for relays")
    }

    /**
     * Set the SRTLA destination and activate tunnels for all waiting relays (Phase 2).
     * Call this immediately after [com.dimadesu.bondbunny.SrtlaSender] has started.
     *
     * Pass `host=""` / `port=0` to reset (e.g. when the stream stops) so that relays
     * return to the waiting room without disconnecting.
     */
    fun connectToSrtla(host: String, port: Int) {
        val s = streamer
        if (s == null) {
            Log.w(TAG, "connectToSrtla called before start() — ignoring")
            return
        }
        if (port != 0) {
            Log.i(TAG, "Connecting relays to SRTLA at $host:$port")
        } else {
            Log.i(TAG, "Resetting SRTLA destination — relays will park until next stream")
        }
        s.setDestination(host, port)
    }

    /**
     * Ask all connected relays for fresh battery / thermal data right now.
     * Also called automatically every [STATUS_POLL_INTERVAL_SECONDS] seconds.
     */
    fun updateStatus() {
        streamer?.updateStatus()
    }

    /**
     * Stop the Moblink server, disconnect all relays, and cancel the status poller.
     */
    fun stop() {
        if (!isStarted) return
        Log.i(TAG, "Stopping MoblinkManager")
        scheduler?.shutdownNow()
        scheduler = null
        streamer?.stop()
        streamer = null
        externalListener = null
        isStarted = false
    }

    // -------------------------------------------------------------------------
    // Internal listener bridge
    // -------------------------------------------------------------------------

    private fun makeStreamerListener() = object : MoblinkStreamerListener {

        override fun onRelayIdentified(relayId: String, name: String) {
            Log.i(TAG, "Relay identified: '$name' ($relayId)")
            externalListener?.onRelayConnected(relayId, name)
        }

        override fun onRelayTunnelReady(
            relayId: String,
            name: String,
            relayHost: String,
            relayPort: Int,
        ) {
            Log.i(TAG, "Relay tunnel ready: '$name' @ $relayHost:$relayPort")
            externalListener?.onRelayTunnelReady(relayId, name, relayHost, relayPort)
        }

        override fun onRelayTunnelClosed(relayId: String, relayHost: String, relayPort: Int) {
            Log.i(TAG, "Relay tunnel closed: $relayId @ $relayHost:$relayPort")
            externalListener?.onRelayDisconnected(relayId)
            externalListener?.onRelayTunnelClosed(relayId, relayHost, relayPort)
        }

        override fun onRelayStatus(
            relayId: String,
            name: String,
            batteryPercentage: Int?,
            thermalState: ThermalState?,
        ) {
            externalListener?.onRelayStatus(relayId, name, batteryPercentage, thermalState)
        }

        override fun onLog(message: String) {
            externalListener?.onLog(message)
        }
    }
}
