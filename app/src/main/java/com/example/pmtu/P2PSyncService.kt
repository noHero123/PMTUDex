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

    // Tracks IPs currently being connected to: Map<IP, TimestampMillis>
    private val pendingConnections = ConcurrentHashMap<String, Long>()
    private var isStartsPending: Boolean = false

    private var monitoringJob: Job? = null
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
        startConnectionMonitoring()
        if (serverSocket == null) {
            _localIp = getIPAddress()
            startListening()
            Log.d(TAG,"start heartbeat")
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
        // 1. Check if it's our own IP
        if (isSameIp(ip, localIp)) {
            Log.d(TAG, "Connect skipped: $ip is self")
            return
        }
        isStartsPending = true
        // 2. Check if already connected and if that connection is still alive
        val existingSocket = activePeers.entries.find { isSameIp(it.key, ip) }?.value
        if (existingSocket != null) {
            val isAlive = try {
                // Sending an empty message or checking connected status
                // isConnected && !isClosed is not enough for stale sockets
                !existingSocket.isClosed && existingSocket.isConnected &&
                        InetAddress.getByName(ip).isReachable(1000)
            } catch (e: Exception) {
                false
            }

            if (isAlive) {
                Log.d(TAG, "Connect skipped: $ip is already active and healthy")
                retryCountMap.remove(ip) // Reset retri
                isStartsPending = false
                return
            } else {
                Log.d(TAG, "Existing connection to $ip is stale. Cleaning up and reconnecting.")
                activePeers.remove(ip)
                try { existingSocket.close() } catch (e: Exception) {}
            }
        }

        // 3. Check if there is a pending connection attempt within the last 3 seconds
        val now = System.currentTimeMillis()
        val lastAttempt = pendingConnections[ip]
        if (lastAttempt != null && (now - lastAttempt) < 3000) {
            Log.d(TAG, "Connect skipped: Connection to $ip already in progress (started ${(now - lastAttempt)}ms ago)")
            isStartsPending = false
            return
        }

        // 4. Mark as pending
        pendingConnections[ip] = now

        if (!serviceScope.isActive) {
            serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        }

        serviceScope.launch {
            try {
                Log.d(TAG, "Connecting to peer: $ip")
                val socket = Socket()
                socket.keepAlive = true
                socket.tcpNoDelay = true
                // Connect timeout of 5 seconds
                socket.connect(InetSocketAddress(ip, PORT), 5000)
                retryCountMap.remove(ip)
                manageConnectedSocket(socket, true)
            } catch (e: Exception) {
                Log.e(TAG, "Connection error to $ip: ${e.message}")

                val currentRetries = retryCountMap[ip] ?: 0
                if (currentRetries < 3) {
                    retryCountMap[ip] = currentRetries + 1
                    Log.w(TAG, "Connection to $ip failed. Retrying in 2s... (${currentRetries + 1}/3)")
                    val randomDelay = (1000..4000).random().toLong()

                    delay(randomDelay)
                    connectToPeer(ip) // Trigger the next attempt
                } else {
                    Log.e(TAG, "Max retries reached for $ip. Giving up.")
                    retryCountMap.remove(ip) // Important: stop trying until next discovery
                }
            } finally {
                // 5. Remove from pending list once the attempt is finished
                pendingConnections.remove(ip)
            }
        }
        isStartsPending = false
    }

    private fun broadcastPing() {
        val pingMsg = SyncData(type = "PING", sourceIp = localIp)
        val json = Gson().toJson(pingMsg)

        // Use the existing serviceScope to perform IO
        serviceScope.launch {
            activePeers.forEach { (ip, socket) ->
                //Log.d(TAG, "Pinging $ip")
                try {
                    // We use a manual writer here to ensure we don't
                    // create 50 separate coroutines via sendDataToPeer
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    writer.println(json)
                } catch (e: Exception) {
                    Log.e(TAG, "Ping failed for $ip: ${e.message}")
                }
            }
        }
    }

    private fun startConnectionMonitoring() {
        monitoringJob?.cancel() // Ensure only one monitor runs
        Log.d(TAG, "heartbeat " + activePeers.size)
        val heartbeatInterval = 5000L // 5 seconds
        monitoringJob = serviceScope.launch {
            while (isActive) {
                Log.d(TAG,"heartbeat")
                delay(heartbeatInterval) // Wait 5 seconds
                val now = System.currentTimeMillis()
                val deadPeers = mutableListOf<String>()

                activePeers.forEach { (ip, socket) ->
                    // Check if we received ANY message (like a PING) from them recently
                    val lastSeen = lastPongReceived[ip] ?: now
                    val isStale = (now - lastSeen) > (heartbeatInterval + 2000) // 5s interval + 2s grace

                    if (isStale || socket.isClosed || !socket.isConnected) {
                        deadPeers.add(ip)
                    }
                }

                if (activePeers.isNotEmpty()) {
                    broadcastPing()
                }

                if (deadPeers.isNotEmpty()) {
                    deadPeers.forEach { ip ->
                        Log.d(TAG, "Monitor detected dead connection: $ip. Cleaning up.")
                        val socket = activePeers.remove(ip)
                        lastPongReceived.remove(ip)
                        try {
                            socket?.close()
                        } catch (e: Exception) {
                        }
                        onPeerDisconnected?.invoke(ip)
                    }
                    updateStatus()
                }
            }
        }
    }

    fun connectToPeers(ips: List<String>) {
        val sortedIps = ips.sorted()
        var ipsToConnect = mutableListOf<String>()
        run breaking@{
            sortedIps.forEach {
                if (isSameIp(it, localIp)) {
                    return@breaking
                }
                ipsToConnect.add(it)
            }
        }
        Log.d(TAG, "Connect to peers: $ipsToConnect")
        //only connect to the ones in list before us
        ipsToConnect.forEach {
            connectToPeer(it)
        }
    }

    private fun manageConnectedSocket(socket: Socket, isInitiator: Boolean) {
        val peerIp = socket.inetAddress.hostAddress ?: "unknown"
        if (isSameIp(peerIp, localIp)) {
            Log.d(TAG, "self connection from $peerIp, closing")
            socket.close()
            return
        }
        if (activePeers.containsKey(peerIp)) {
            Log.d(TAG, "Duplicate connection from $peerIp, close the old one.")
            activePeers[peerIp]!!.close()
        }
        
        activePeers[peerIp] = socket
        lastConnectedIp.add(peerIp)
        lastPongReceived[peerIp] = System.currentTimeMillis()
        Log.d(TAG, "New connection from $peerIp")
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
                    retryCountMap.remove(peerIp)
                    lastPongReceived[peerIp] = System.currentTimeMillis()
                    processIncomingInternalMessage(line, socket, isInitiator)
                    onDataReceived?.invoke(line, peerIp)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Read error from $peerIp", e)
            } finally {
                val wasConnected = activePeers.remove(peerIp) != null
                try { socket.close() } catch (e: Exception) {}
                updateStatus()
                onPeerDisconnected?.invoke(peerIp)

                // RECONNECT LOGIC:
                // If the connection dropped unexpectedly and the server is still enabled
                if (wasConnected && isServerEnabledByUser) {
                    Log.d(TAG, "Attempting to reconnect to $peerIp after read error...")
                    // We call connectToPeer which will handle the 3-retry logic
                    val randomDelay = (1000..4000).random().toLong()
                    delay(randomDelay)
                    connectToPeer(peerIp)
                }
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
        //val discoveryMsg = SyncData("PEER_DISCOVERY", null, null, peerIps = listOf(newPeerIp))
        val discoveryMsg = SyncData("PEER_DISCOVERY", null, null, peerIps = activePeers.keys().toList())

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
        Log.d(TAG, "stopp p2p service...")
        serviceScope.coroutineContext.cancelChildren()
        try {
            serverSocket?.close()
            activePeers.values.forEach { it.close() }
        } catch (e: Exception) {
            Log.e(TAG, "Close error", e)
        }
        Log.e(TAG, "STOP SERVICE")
        monitoringJob?.cancel()
        serverSocket?.close()
        serverSocket = null
        pendingConnections.clear()
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
        retryCountMap.clear()
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

    fun isPending(): Boolean {
        //returns true if pending connections are not empty and not to old
        //remove all old pendings first
        pendingConnections.entries.removeIf { (_, timestamp) -> System.currentTimeMillis() - timestamp > 3000 }
        return pendingConnections.isNotEmpty() || isStartsPending
    }

    data class SyncData(
        val type: String, // "PING", "PONG", "SYNC", "HANDSHAKE", "FIGHT_JOIN_REQUEST", et
        val ownPokemonJson: String? = null,
        val enemyPokemonJson: String? = null,
        var ownWeather: String? = null,
        var enemyWeather: String? = null,
        val peerIps: List<String>? = null,
        val targetIp: String? = null,
        val sourceIp: String? = null,
        val sourceName: String? = null
    )


    // Map to track when we last heard a PONG from an IP
    val lastPongReceived = ConcurrentHashMap<String, Long>()
    // Tracks retry attempts for each IP: Map<IP, AttemptCount>
    private val retryCountMap = ConcurrentHashMap<String, Int>()
}
