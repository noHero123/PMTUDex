package com.example.pmtu

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.util.*

object HttpSyncService {
    private const val TAG = "HttpSyncService"
    private const val PORT = 8888

    var isServer = false
    var onDataReceived: ((String) -> Unit)? = null
    
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
        DISCONNECTED, LISTENING, CONNECTING, CONNECTED, ERROR
    }

    var connectionStatus = Status.DISCONNECTED
        private set(value) {
            field = value
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
    private var clientSocket: Socket? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun getLocalIpAddress(context: Context): String? {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        val ip = wm.connectionInfo.ipAddress
        return if (ip == 0) null else String.format(
            Locale.US,
            "%d.%d.%d.%d",
            ip and 0xff,
            ip shr 8 and 0xff,
            ip shr 16 and 0xff,
            ip shr 24 and 0xff
        )
    }

    fun startServer() {
        isServer = true
        stopAll()
        connectionStatus = Status.LISTENING
        statusMessage = "Waiting for connection..."

        serviceScope.launch {
            try {
                serverSocket = ServerSocket(PORT)
                while (isActive) {
                    val socket = serverSocket?.accept()
                    if (socket != null) {
                        manageConnectedSocket(socket)
                        break
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Server error", e)
                    statusMessage = "Server error: ${e.message}"
                    connectionStatus = Status.ERROR
                }
            }
        }
    }

    fun startClient(ip: String) {
        isServer = false
        stopAll()
        connectionStatus = Status.CONNECTING
        statusMessage = "Connecting to $ip..."

        serviceScope.launch {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ip, PORT), 5000)
                manageConnectedSocket(socket)
            } catch (e: Exception) {
                Log.e(TAG, "Connection error", e)
                statusMessage = "Connection failed: ${e.message}"
                connectionStatus = Status.ERROR
            }
        }
    }

    private fun manageConnectedSocket(socket: Socket) {
        clientSocket = socket
        connectionStatus = Status.CONNECTED
        statusMessage = "Connected"

        serviceScope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                while (isActive) {
                    val line = reader.readLine() ?: break
                    onDataReceived?.invoke(line)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Read error", e)
            } finally {
                stopAll()
            }
        }
    }

    fun sendData(data: SyncData) {
        val json = Gson().toJson(data)
        serviceScope.launch {
            try {
                clientSocket?.getOutputStream()?.let {
                    val writer = PrintWriter(it, true)
                    writer.println(json)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Write error", e)
            }
        }
    }

    fun stopAll() {
        serviceScope.coroutineContext.cancelChildren()
        try {
            serverSocket?.close()
            clientSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Close error", e)
        }
        serverSocket = null
        clientSocket = null
        connectionStatus = Status.DISCONNECTED
        statusMessage = null
    }

    data class SyncData(
        val type: String,
        val ownPokemonJson: String?,
        val enemyPokemonJson: String?,
        var ownWeather: String? = null,
        var enemyWeather: String? = null
    )
}
