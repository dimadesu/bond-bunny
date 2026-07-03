package com.dimadesu.bondbunny.moblink

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer

/**
 * Callbacks emitted by [MoblinkStreamer] as relays connect, become ready, report status,
 * or disconnect. A "tunnel ready" relay is one that has authenticated and returned its local
 * UDP port — the SRTLA layer can then bond to `relayHost:relayPort` as an extra link.
 *
 * Callbacks are invoked on Java-WebSocket worker threads; implementations must be thread-safe.
 */
interface MoblinkStreamerListener {
    /**
     * Called after a relay successfully authenticates, but *before* a StartTunnel request
     * is sent. If [setDestination] has not been called yet, no tunnel will be started yet —
     * the session waits in the "identified" pool until [setDestination] is called.
     */
    fun onRelayIdentified(relayId: String, name: String) {}

    fun onRelayTunnelReady(relayId: String, name: String, relayHost: String, relayPort: Int)
    fun onRelayTunnelClosed(relayId: String, relayHost: String, relayPort: Int)
    fun onRelayStatus(
        relayId: String,
        name: String,
        batteryPercentage: Int?,
        thermalState: ThermalState?,
    )

    fun onLog(message: String) {}
}

/**
 * Moblink streamer: runs a WebSocket server that spare devices (Moblink relays) connect to,
 * authenticates them, and asks each to open a UDP tunnel to the SRTLA receiver. Also advertises
 * itself via mDNS (`_moblink._tcp`) so relays in automatic mode can discover it.
 *
 * Bond Bunny is the streamer; relays forward bonded traffic out over their own uplinks.
 *
 * ### Two-phase tunnel activation
 * If [setDestination] has not been called (or was called with port 0) when a relay authenticates,
 * the session is parked in the "identified" pool. Once [setDestination] is called with a valid
 * destination, all parked sessions immediately receive a StartTunnel request, and future relays
 * will receive it as soon as they authenticate.
 */
class MoblinkStreamer(
    private val context: Context,
    private val name: String,
    private val password: String,
    private val port: Int = DEFAULT_PORT,
) {
    companion object {
        private const val TAG = "MoblinkStreamer"
        const val DEFAULT_PORT = 7777
    }

    private val sessions = ConcurrentHashMap<WebSocket, RelaySession>()
    private val secureRandom = SecureRandom()

    private var server: WebSocketServer? = null
    private var listener: MoblinkStreamerListener? = null

    @Volatile private var destinationAddress: String = ""
    @Volatile private var destinationPort: Int = 0

    // mDNS
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    /**
     * Start the streamer WebSocket server and mDNS advertisement. Returns immediately;
     * relays connect asynchronously.
     *
     * The SRTLA destination is **not** required at this point. Call [setDestination] once the
     * SRTLA stack is running to activate tunnels for all waiting relays.
     */
    fun start(listener: MoblinkStreamerListener) {
        stop()
        this.listener = listener

        val server = object : WebSocketServer(InetSocketAddress(port)) {
            override fun onStart() {
                val boundPort = if (port != 0) port else getPort()
                Log.i(TAG, "Moblink streamer listening on port $boundPort")
                listener.onLog("Moblink streamer listening on port $boundPort")
                registerMdns(boundPort)
            }

            override fun onOpen(conn: WebSocket, handshake: ClientHandshake?) {
                handleOpen(conn)
            }

            override fun onMessage(conn: WebSocket, message: String) {
                handleMessage(conn, message)
            }

            override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
                handleClose(conn)
            }

            override fun onError(conn: WebSocket?, ex: Exception) {
                Log.w(TAG, "Moblink streamer error", ex)
                listener.onLog("Moblink streamer error: ${ex.message}")
            }
        }
        server.isReuseAddr = true
        // Built-in WebSocket ping/pong keepalive; drop dead relays after ~15s.
        server.connectionLostTimeout = 15
        server.start()
        this.server = server
    }

    /**
     * Legacy overload kept for backward compatibility. Immediately sets the destination and
     * starts tunnels for all subsequently-connecting relays.
     */
    fun start(destinationAddress: String, destinationPort: Int, listener: MoblinkStreamerListener) {
        start(listener)
        if (destinationPort != 0) {
            setDestination(destinationAddress, destinationPort)
        }
    }

    /**
     * Set (or update) the SRTLA receiver address that relays should tunnel to.
     *
     * - If called with a non-zero port, all currently-identified-but-not-yet-tunneled sessions
     *   immediately receive a StartTunnel request. Future relays will tunnel as soon as they
     *   authenticate.
     * - If called with port == 0 (stream stopped), the destination is cleared so that future
     *   relays are parked again. Existing tunnels are **not** torn down here; the WebSocket
     *   keep-alive will detect dead tunnels naturally.
     */
    fun setDestination(address: String, port: Int) {
        destinationAddress = address
        destinationPort = port
        if (port != 0) {
            Log.i(TAG, "Destination set to $address:$port — activating waiting relay sessions")
            for (session in sessions.values) {
                session.startTunnelIfNeeded()
            }
        } else {
            Log.i(TAG, "Destination cleared — resetting relay tunnel state for next stream")
            for (session in sessions.values) {
                session.resetTunnel()
            }
        }
    }

    /** Stop the streamer, disconnect all relays, and withdraw the mDNS advertisement. */
    fun stop() {
        unregisterMdns()
        val server = this.server
        this.server = null
        if (server != null) {
            try {
                server.stop(1000)
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping Moblink server", e)
            }
        }
        sessions.clear()
        listener = null
        destinationAddress = ""
        destinationPort = 0
    }

    /** Ask every connected relay to report its current status (battery / thermal). */
    fun updateStatus() {
        for (session in sessions.values) {
            session.requestStatus()
        }
    }

    // -------------------------------------------------------------------------
    // Connection handling
    // -------------------------------------------------------------------------

    private fun handleOpen(conn: WebSocket) {
        val session = RelaySession(conn)
        sessions[conn] = session
        Log.i(TAG, "Relay connected: ${conn.remoteSocketAddress}")
        session.start()
    }

    private fun handleMessage(conn: WebSocket, message: String) {
        val session = sessions[conn] ?: return
        try {
            session.handleMessage(message)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle relay message", e)
            conn.close()
        }
    }

    private fun handleClose(conn: WebSocket) {
        val session = sessions.remove(conn) ?: return
        Log.i(TAG, "Relay disconnected: ${conn.remoteSocketAddress}")
        session.reportTunnelRemoved()
    }

    private fun randomString(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    }

    // -------------------------------------------------------------------------
    // mDNS / NSD
    // -------------------------------------------------------------------------

    private fun registerMdns(boundPort: Int) {
        try {
            val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
            val displayName = if (name.isNotEmpty()) name else "Bond Bunny"
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = displayName
                serviceType = MOBLINK_SERVICE_TYPE
                setPort(boundPort)
                setAttribute("name", displayName)
            }
            val regListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    Log.i(TAG, "mDNS registered: ${info.serviceName}")
                }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS registration failed: $errorCode")
                }

                override fun onServiceUnregistered(info: NsdServiceInfo) {
                    Log.i(TAG, "mDNS unregistered")
                }

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    Log.w(TAG, "mDNS unregistration failed: $errorCode")
                }
            }
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, regListener)
            nsdManager = manager
            registrationListener = regListener
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register mDNS service", e)
        }
    }

    private fun unregisterMdns() {
        val manager = nsdManager
        val regListener = registrationListener
        if (manager != null && regListener != null) {
            try {
                manager.unregisterService(regListener)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering mDNS", e)
            }
        }
        nsdManager = null
        registrationListener = null
    }

    // -------------------------------------------------------------------------
    // Per-relay session
    // -------------------------------------------------------------------------

    private inner class RelaySession(private val conn: WebSocket) {
        private val nextId = AtomicInteger(0)
        private val pending = ConcurrentHashMap<Int, (Response) -> Unit>()

        private var challenge = ""
        private var salt = ""
        private var identified = false

        private var relayId = ""
        private var relayName = ""
        private var batteryPercentage: Int? = null
        private var thermalState: ThermalState? = null

        // Tunnel endpoint, set once the relay returns its local UDP port.
        @Volatile private var tunnelHost: String? = null
        @Volatile private var tunnelPort: Int? = null

        fun start() {
            challenge = randomString()
            salt = randomString()
            identified = false
            send(
                MessageToRelay(
                    hello = Hello(
                        apiVersion = MOBLINK_API_VERSION,
                        authentication = Authentication(challenge = challenge, salt = salt),
                    )
                )
            )
        }

        fun handleMessage(text: String) {
            val message = MessageToStreamer.fromJson(text)
            when {
                message.identify != null -> handleIdentify(message.identify)
                message.response != null -> handleResponse(message.response)
            }
        }

        private fun handleIdentify(identify: Identify) {
            val expected = moblinkCalculateAuthentication(password, salt, challenge)
            if (identify.authentication != expected) {
                send(MessageToRelay(identified = Identified(result = Result(wrongPassword = Present()))))
                conn.close()
                return
            }
            identified = true
            relayId = identify.id
            relayName = identify.name.substringBefore('\n').trim().take(30)
            send(MessageToRelay(identified = Identified(result = Result(ok = Present()))))
            listener?.onRelayIdentified(relayId, relayName)
            startTunnelIfNeeded()
            requestStatus()
        }

        private fun handleResponse(response: Response) {
            val handler = pending.remove(response.id) ?: return
            handler(response)
        }

        /**
         * Start the tunnel if this session is identified and a valid destination is known.
         * Safe to call multiple times — it will only send a StartTunnel request once per
         * destination (tracked by [tunnelPort]).
         */
        fun startTunnelIfNeeded() {
            if (!identified) return
            val address = destinationAddress
            val dstPort = destinationPort
            if (address.isEmpty() || dstPort == 0) {
                Log.d(TAG, "Relay '$relayName' waiting for destination to be set")
                return
            }
            // Already tunneled to this destination — skip.
            if (tunnelPort != null) {
                Log.d(TAG, "Relay '$relayName' already has an active tunnel")
                return
            }
            Log.i(TAG, "Starting tunnel for relay '$relayName' → $address:$dstPort")
            performRequest(RequestData(startTunnel = StartTunnelRequest(address, dstPort))) { response ->
                val tunnel = response.data?.startTunnel ?: return@performRequest
                val host = (conn.remoteSocketAddress as? InetSocketAddress)
                    ?.address?.hostAddress ?: return@performRequest
                tunnelHost = host
                tunnelPort = tunnel.port
                Log.i(TAG, "Relay '$relayName' tunnel ready: $host:${tunnel.port}")
                listener?.onRelayTunnelReady(relayId, relayName, host, tunnel.port)
            }
        }

        fun requestStatus() {
            if (!identified) return
            performRequest(RequestData(status = Present())) { response ->
                val status = response.data?.status ?: return@performRequest
                batteryPercentage = status.batteryPercentage
                thermalState = status.thermalState
                listener?.onRelayStatus(relayId, relayName, batteryPercentage, thermalState)
            }
        }

        fun reportTunnelRemoved() {
            val host = tunnelHost
            val port = tunnelPort
            tunnelHost = null
            tunnelPort = null
            pending.clear()
            if (host != null && port != null) {
                listener?.onRelayTunnelClosed(relayId, host, port)
            }
        }

        /**
         * Reset tunnel state so this session is ready to re-tunnel on the next stream.
         * Called when the SRTLA stream stops ([setDestination] with port=0).
         * Does NOT close the WebSocket connection — the relay stays pre-connected.
         */
        fun resetTunnel() {
            val host = tunnelHost
            val port = tunnelPort
            tunnelHost = null
            tunnelPort = null
            pending.values.forEach { /* discard pending tunnel responses */ }
            pending.clear()
            if (host != null && port != null) {
                Log.i(TAG, "Relay '$relayName' tunnel reset — will re-tunnel on next stream")
                listener?.onRelayTunnelClosed(relayId, host, port)
            }
        }

        private fun performRequest(data: RequestData, onSuccess: (Response) -> Unit) {
            val id = nextId.incrementAndGet()
            pending[id] = onSuccess
            send(MessageToRelay(request = Request(id = id, data = data)))
        }

        private fun send(message: MessageToRelay) {
            try {
                if (conn.isOpen) {
                    conn.send(message.toJson())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send to relay", e)
            }
        }
    }
}
