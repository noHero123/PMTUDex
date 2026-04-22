package com.example.pmtu

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object P2PSyncService {
    private const val TAG = "P2PSyncService"
    private const val PORT = 8888

    var onDataReceived: ((String, String) -> Unit)? = null // json, senderIp
    var onPeerDisconnected: ((String) -> Unit)? = null

    private val activePeers = ConcurrentHashMap<String, Socket>()
    private var _localIp: String? = null
    val localIp: String? get() {
        if (_localIp == null) {
            _localIp = getIPAddress()
        }
        return _localIp
    }
    
    var isServerEnabledByUser: Boolean = false
        private set

    val activeConnections: Int
        get() = activePeers.size
    
    private val statusListeners = mutableSetOf<(Status, String?) -> Unit>()

    var onStatusChanged: ((Status, String?) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(connectionStatus, statusMessage)
        }

    fun addStatusListener(listener: (Status, String?) -> Unit) {
        statusListeners.add(listener)
        listener(connectionStatus, statusMessage)
    }

    fun removeStatusListener(listener: (Status, String?) -> Unit) {
        statusListeners.remove(listener)
    }

    enum class Status {
        DISCONNECTED, LISTENING, CONNECTED, ERROR
    }

    var connectionStatus = Status.DISCONNECTED
        private set(value) {
            field = value
            Log.d(TAG, "connection status changed to $value")
            statusListeners.forEach { it(value, statusMessage) }
            onStatusChanged?.invoke(value, statusMessage)
        }

    var statusMessage: String? = null
        private set(value) {
            field = value
            statusListeners.forEach { it(connectionStatus, value) }
            onStatusChanged?.invoke(connectionStatus, value)
        }

    private var serverSocket: ServerSocket? = null
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun getIPAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces.asSequence()) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses.asSequence()) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP", e)
        }
        return null
    }

    fun getLocalIpAddress(context: Context): String? {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ip = wm.connectionInfo.ipAddress
        if (ip != 0) {
            _localIp = String.format(
                Locale.US,
                "%d.%d.%d.%d",
                ip and 0xff,
                ip shr 8 and 0xff,
                ip shr 16 and 0xff,
                ip shr 24 and 0xff
            )
        } else {
            _localIp = getIPAddress()
        }
        return _localIp
    }

    fun startService() {
        isServerEnabledByUser = true
        if (serverSocket == null) {
            _localIp = getIPAddress()
            startListening()
        }
    }

    private fun startListening() {
        connectionStatus = Status.LISTENING
        statusMessage = "Listening for peers..."

        if (!serviceScope.isActive) {
            serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }

        serviceScope.launch {
            try {
                serverSocket = ServerSocket(PORT)
                while (isActive) {
                    val socket = serverSocket?.accept()
                    if (socket != null) {
                        manageConnectedSocket(socket, false)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Server error", e)
                    statusMessage = "Server error: ${e.message}"
                    connectionStatus = Status.ERROR
                }
            } finally {
                serverSocket = null
            }
        }
    }

    fun connectToPeer(ip: String) {
        if (isSameIp(ip, localIp) || activePeers.keys().asSequence().any { isSameIp(it, ip) }) {
            Log.d(TAG, "Connect skipped: $ip is self or already connected")
            return
        }
        
        if (!serviceScope.isActive) {
            serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }

        serviceScope.launch {
            try {
                Log.d(TAG, "Connecting to peer: $ip")
                val socket = Socket()
                socket.keepAlive = true
                socket.tcpNoDelay = true
                socket.connect(InetSocketAddress(ip, PORT), 5000)
                manageConnectedSocket(socket, true)
            } catch (e: Exception) {
                Log.e(TAG, "Connection error to $ip", e)
            }
        }
    }

    fun connectToPeers(ips: List<String>) {
        ips.forEach { connectToPeer(it) }
    }

    private fun manageConnectedSocket(socket: Socket, isInitiator: Boolean) {
        val peerIp = socket.inetAddress.hostAddress ?: "unknown"
        if (activePeers.containsKey(peerIp) || isSameIp(peerIp, localIp)) {
            Log.d(TAG, "Duplicate or self connection from $peerIp, closing")
            socket.close()
            return
        }
        
        activePeers[peerIp] = socket
        lastConnectedIp.add(peerIp)
        updateStatus()

        // Handshake: Server sends its known peer list to the new client
        if (!isInitiator) {
            val peerList = activePeers.keys().toList()
            sendDataToSocket(socket, SyncData("HANDSHAKE", null, null, peerIps = peerList))
        }

        // Broadcast discovery of this new peer to all existing peers
        broadcastPeerDiscovery(peerIp)

        serviceScope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                while (isActive) {
                    val line = reader.readLine() ?: break
                    processIncomingInternalMessage(line, socket, isInitiator)
                    onDataReceived?.invoke(line, peerIp)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Read error from $peerIp", e)
            } finally {
                activePeers.remove(peerIp)
                socket.close()
                updateStatus()
                onPeerDisconnected?.invoke(peerIp)
            }
        }
    }

    private fun processIncomingInternalMessage(json: String, sourceSocket: Socket, wasInitiator: Boolean) {
        try {
            val data = Gson().fromJson(json, SyncData::class.java)
            when (data.type) {
                "HANDSHAKE" -> {
                    Log.d(TAG, "Received HANDSHAKE from ${sourceSocket.inetAddress.hostAddress}. Known peers: ${data.peerIps}")
                    data.peerIps?.let { connectToPeers(it) }
                    if (wasInitiator) {
                        sendDataToSocket(sourceSocket, SyncData("PEER_LIST", null, null, peerIps = activePeers.keys().toList()))
                    }
                }
                "PEER_LIST" -> {
                    Log.d(TAG, "Received PEER_LIST from ${sourceSocket.inetAddress.hostAddress}. IPs: ${data.peerIps}")
                    data.peerIps?.let { connectToPeers(it) }
                }
                "PEER_DISCOVERY" -> {
                    Log.d(TAG, "Received PEER_DISCOVERY. New peer at: ${data.peerIps}")
                    data.peerIps?.let { connectToPeers(it) }
                }
            }
        } catch (e: Exception) {
            // Ignore malformed or non-P2P messages
        }
    }

    private fun broadcastPeerDiscovery(newPeerIp: String) {
        val discoveryMsg = SyncData("PEER_DISCOVERY", null, null, peerIps = listOf(newPeerIp))
        val json = Gson().toJson(discoveryMsg)
        serviceScope.launch {
            activePeers.forEach { (ip, socket) ->
                if (!isSameIp(ip, newPeerIp)) {
                    try {
                        val writer = PrintWriter(socket.getOutputStream(), true)
                        writer.println(json)
                    } catch (e: Exception) {
                        Log.e(TAG, "Peer discovery broadcast failed for $ip", e)
                    }
                }
            }
        }
    }

    private fun sendDataToSocket(socket: Socket, data: SyncData) {
        val json = Gson().toJson(data)
        serviceScope.launch {
            try {
                val writer = PrintWriter(socket.getOutputStream(), true)
                writer.println(json)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send message to ${socket.inetAddress.hostAddress}", e)
            }
        }
    }

    fun sendDataToPeer(ip: String, data: SyncData) {
        // Try to find the socket by matching IP (normalized)
        val entry = activePeers.entries.find { isSameIp(it.key, ip) }
        entry?.value?.let { socket ->
            sendDataToSocket(socket, data)
        }
    }

    private fun updateStatus() {
        if (activePeers.isEmpty()) {
            if (isServerEnabledByUser) {
                connectionStatus = Status.LISTENING
                statusMessage = "Listening for peers..."
            } else {
                connectionStatus = Status.DISCONNECTED
                statusMessage = null
            }
        } else {
            connectionStatus = Status.CONNECTED
            statusMessage = "Connected to ${activePeers.size} peer(s)"
        }
    }

    fun broadcastData(data: SyncData) {
        val json = Gson().toJson(data)
        serviceScope.launch {
            activePeers.values.forEach { socket ->
                try {
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    writer.println(json)
                } catch (e: Exception) {
                    Log.e(TAG, "Write error", e)
                }
            }
        }
    }


    fun stopService() {
        serviceScope.coroutineContext.cancelChildren()
        try {
            serverSocket?.close()
            activePeers.values.forEach { it.close() }
        } catch (e: Exception) {
            Log.e(TAG, "Close error", e)
        }
        serverSocket = null
        activePeers.clear()
        updateStatus()
    }

    // Compatibility methods
    fun startServer() = startService()
    fun startClient(ip: String) {
        startService()
        connectToPeer(ip)
    }
    fun stopByUser(){
        isServerEnabledByUser = false
        lastConnectedIp.clear()
        stopService()
    }

    fun reconnect()
    {
        stopService()
        startService() // Ensure listener is restarted
        Log.d(TAG, "reconnecting to peers ")
        for (ip in lastConnectedIp) {
            Log.d(TAG, "log to peer $ip")
            connectToPeer(ip)
        }
    }

    fun sendData(data: SyncData) = broadcastData(data)
    fun sendOK() = broadcastData(SyncData("OK", null, null))
    
    var lastConnectedIp: MutableSet<String> = mutableSetOf<String>()
    var isServer: Boolean = true

    fun isSameIp(ip1: String?, ip2: String?): Boolean {
        if (ip1 == null || ip2 == null) return false
        if (ip1 == ip2) return true
        val n1 = ip1.substringAfterLast(":")
        val n2 = ip2.substringAfterLast(":")
        return n1 == n2
    }

    data class SyncData(
        val type: String,
        val ownPokemonJson: String? = null,
        val enemyPokemonJson: String? = null,
        var ownWeather: String? = null,
        var enemyWeather: String? = null,
        val peerIps: List<String>? = null,
        val targetIp: String? = null,
        val sourceIp: String? = null,
        val sourceName: String? = null
    )
}
