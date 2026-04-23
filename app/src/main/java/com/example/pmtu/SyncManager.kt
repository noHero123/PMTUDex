package com.example.pmtu

import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.gson.Gson

class SyncManager(
    private val context: Context,
    private val viewModel: ResultViewModel,
    private val onFightStarted: (String) -> Unit,
    private val onFightEnded: () -> Unit,
    private val onSyncReceived: (PokemonInfo?, String?) -> Unit
) {
    var isFightOngoing = false
        private set
    var fightOpponentIp: String? = null
        private set
    private var fightInvitationDialog: AlertDialog? = null

    init {
        setupP2PSync()
    }

    private fun setupP2PSync() {
        P2PSyncService.onDataReceived = { json, senderIp ->
            try {
                val data = Gson().fromJson(json, P2PSyncService.SyncData::class.java)
                if (data.type != "PING") {
                    Log.d("SyncManager", "get ${data.type} from $senderIp...")
                }
                when (data.type) {
                    "HANDSHAKE" -> {
                        P2PSyncService.lastPongReceived[senderIp] = System.currentTimeMillis()
                    }
                    "PING" -> {
                        P2PSyncService.lastPongReceived[senderIp] = System.currentTimeMillis()
                    }
                    "SYNC" -> {
                        if (isFightOngoing && P2PSyncService.isSameIp(senderIp, fightOpponentIp)) {
                            val receivedOwn = data.ownPokemonJson?.let { Gson().fromJson(it, PokemonInfo::class.java) }
                            onSyncReceived(receivedOwn, data.ownWeather)
                        }
                    }
                    "FIGHT_REQUEST" -> {
                        if (!isFightOngoing) {
                            showFightInvitation(senderIp, data.sourceName ?: senderIp)
                        }
                    }
                    "FIGHT_JOIN_REQUEST" -> {
                        if (!isFightOngoing) {
                            (context as? android.app.Activity)?.runOnUiThread {
                                Toast.makeText(context, "${data.sourceName ?: senderIp} wants to fight!", Toast.LENGTH_SHORT).show()
                            }
                            acceptFight(senderIp)
                        }
                    }
                    "FIGHT_START" -> {
                        (context as? android.app.Activity)?.runOnUiThread {
                            fightInvitationDialog?.dismiss()
                            fightInvitationDialog = null
                        }

                        if (P2PSyncService.isSameIp(data.targetIp, P2PSyncService.localIp)) {
                            isFightOngoing = true
                            fightOpponentIp = data.sourceIp
                            onFightStarted(data.sourceIp!!)
                        } else if (P2PSyncService.isSameIp(data.sourceIp, P2PSyncService.localIp)) {
                            isFightOngoing = true
                            fightOpponentIp = data.targetIp
                            onFightStarted(data.targetIp!!)
                        }
                    }
                    "FIGHT_END" -> {
                        if (P2PSyncService.isSameIp(data.targetIp, P2PSyncService.localIp) ||
                            P2PSyncService.isSameIp(data.sourceIp, P2PSyncService.localIp)) {
                            resetFightState()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("SYNC", "Error parsing received data", e)
            }
        }

        P2PSyncService.onPeerDisconnected = { ip ->
            if (isFightOngoing && P2PSyncService.isSameIp(ip, fightOpponentIp)) {
                (context as? android.app.Activity)?.runOnUiThread {
                    Toast.makeText(context, "Opponent disconnected", Toast.LENGTH_SHORT).show()
                    resetFightState()
                }
            }
        }
    }

    private fun showFightInvitation(senderIp: String, senderName: String) {
        (context as? android.app.Activity)?.runOnUiThread {
            fightInvitationDialog?.dismiss()
            fightInvitationDialog = AlertDialog.Builder(context)
                .setTitle("Fight Request")
                .setMessage("$senderName wants to fight. Join?")
                .setPositiveButton("Yes") { _, _ ->
                    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                    val myName = prefs.getString("trainer_name", "Trainer")
                    P2PSyncService.sendDataToPeer(senderIp, P2PSyncService.SyncData(type = "FIGHT_JOIN_REQUEST", sourceName = myName))
                }
                .setNegativeButton("No", null)
                .setOnDismissListener { fightInvitationDialog = null }
                .show()
        }
    }

    fun acceptFight(opponentIp: String) {
        isFightOngoing = true
        fightOpponentIp = opponentIp
        P2PSyncService.broadcastData(P2PSyncService.SyncData(
            type = "FIGHT_START",
            targetIp = opponentIp,
            sourceIp = P2PSyncService.localIp
        ))
        onFightStarted(opponentIp)
    }

    fun requestFight() {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val name = prefs.getString("trainer_name", "Trainer")
        P2PSyncService.broadcastData(P2PSyncService.SyncData(type = "FIGHT_REQUEST", sourceName = name))
        Toast.makeText(context, "Fight request sent!", Toast.LENGTH_SHORT).show()
    }

    fun endFight() {
        P2PSyncService.broadcastData(P2PSyncService.SyncData(
            type = "FIGHT_END",
            sourceIp = P2PSyncService.localIp,
            targetIp = fightOpponentIp
        ))
        resetFightState()
    }

    fun resetFightState() {
        isFightOngoing = false
        fightOpponentIp = null
        onFightEnded()
    }

    fun syncViaP2P() {
        if (!isFightOngoing || fightOpponentIp == null) return

        if (P2PSyncService.connectionStatus == P2PSyncService.Status.CONNECTED) {
            P2PSyncService.sendDataToPeer(fightOpponentIp!!, P2PSyncService.SyncData(
                type = "SYNC",
                ownPokemonJson = Gson().toJson(viewModel.ownPokemon.value),
                enemyPokemonJson = Gson().toJson(viewModel.enemyPokemon.value),
                ownWeather = viewModel.ownWeather.value,
                enemyWeather = viewModel.enemyWeather.value
            ))
        }
    }
}
