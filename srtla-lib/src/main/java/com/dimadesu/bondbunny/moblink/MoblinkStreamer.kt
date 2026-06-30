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
     * Start the streamer. [destinationAddress]/[destinationPort] is the SRTLA receiver each relay
     * will tunnel to. Returns immediately; relays connect asynchronously.
     */
    fun start(destinationAddress: String, destinationPort: Int, listener: MoblinkStreamerListener) {
        stop()
        this.destinationAddress = destinationAddress
        this.destinationPort = destinationPort
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
        private var tunnelHost: String? = null
        private var tunnelPort: Int? = null

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
            startTunnel()
            requestStatus()
        }

        private fun handleResponse(response: Response) {
            val handler = pending.remove(response.id) ?: return
            handler(response)
        }

        private fun startTunnel() {
            if (!identified) return
            val address = destinationAddress
            val dstPort = destinationPort
            if (address.isEmpty() || dstPort == 0) {
                Log.w(TAG, "Cannot start tunnel: destination not set")
                return
            }
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
