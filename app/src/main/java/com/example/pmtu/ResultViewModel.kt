package com.example.pmtu

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

class ResultViewModel(application: Application) : AndroidViewModel(application) {
    private val TEAM_FILE_NAME = "team_data.json"

    private val _ownPokemon = MutableStateFlow<PokemonInfo?>(null)
    val ownPokemon: StateFlow<PokemonInfo?> = _ownPokemon

    private val _enemyPokemon = MutableStateFlow<PokemonInfo?>(null)
    val enemyPokemon: StateFlow<PokemonInfo?> = _enemyPokemon

    private val _teamPokemon = MutableStateFlow<Array<PokemonInfo?>>(arrayOfNulls(6))
    val teamPokemon: StateFlow<Array<PokemonInfo?>> = _teamPokemon

    private val _ownWeather = MutableStateFlow<String?>(null)
    val ownWeather: StateFlow<String?> = _ownWeather

    private val _enemyWeather = MutableStateFlow<String?>(null)
    val enemyWeather: StateFlow<String?> = _enemyWeather

    private val _currentTeamIndex = MutableStateFlow<Int?>(null)
    val currentTeamIndex: StateFlow<Int?> = _currentTeamIndex

    init {
        loadTeamData()
    }

    fun setOwnPokemon(pokemon: PokemonInfo?, index: Int? = null) {
        val targetIndex = if (index == null && pokemon != null) {
            val idx = _teamPokemon.value.indexOfFirst { it?.id == pokemon.id }
            if (idx != -1) idx else null
        } else {
            index
        }
        _ownPokemon.value = pokemon
        _currentTeamIndex.value = targetIndex
    }

    fun setEnemyPokemon(pokemon: PokemonInfo?) {
        _enemyPokemon.value = pokemon
    }

    fun setOwnWeather(weather: String?) {
        _ownWeather.value = weather
    }

    fun setEnemyWeather(weather: String?) {
        _enemyWeather.value = weather
    }

    fun clearEnemy() {
        _enemyPokemon.value = null
    }

    fun addToTeam(slot: Int) {
        ownPokemon.value?.let { current ->
            val newTeam = _teamPokemon.value.copyOf()
            newTeam[slot] = current
            _teamPokemon.value = newTeam
            _currentTeamIndex.value = slot
            saveTeamData()
        }
    }

    fun removeFromTeam() {
        _currentTeamIndex.value?.let { index ->
            val newTeam = _teamPokemon.value.copyOf()
            newTeam[index] = null
            _currentTeamIndex.value = null
            _teamPokemon.value = newTeam
            saveTeamData()
        }
    }

    fun switchWithEnemy() {
        val oldOwn = _ownPokemon.value
        val oldWeather = _ownWeather.value

        _ownPokemon.value = _enemyPokemon.value
        _enemyPokemon.value = oldOwn

        _ownWeather.value = _enemyWeather.value
        _enemyWeather.value = oldWeather
        
        _currentTeamIndex.value = null
    }

    fun saveTeamData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val team = _teamPokemon.value
                team.forEach { pokemon ->
                    if (pokemon?.spriteBitmap != null && pokemon.spriteBase64 == null) {
                        pokemon.spriteBase64 = bitmapToBase64(pokemon.spriteBitmap!!)
                    }
                }
                val json = Gson().toJson(team)
                File(getApplication<Application>().filesDir, TEAM_FILE_NAME).writeText(json)
            } catch (e: Exception) {
                Log.e("Storage", "Error saving team data", e)
            }
        }
    }

    fun loadTeamData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(getApplication<Application>().filesDir, TEAM_FILE_NAME)
                if (file.exists()) {
                    val json = file.readText()
                    val type = object : TypeToken<Array<PokemonInfo?>>() {}.type
                    val loadedTeam: Array<PokemonInfo?> = Gson().fromJson(json, type)
                    
                    loadedTeam.forEach { pokemon ->
                        if (pokemon?.spriteBase64 != null) {
                            pokemon.spriteBitmap = base64ToBitmap(pokemon.spriteBase64!!)
                        }
                    }
                    _teamPokemon.value = loadedTeam
                    
                    // Update current pokemon if it's not set or if it's from the team
                    val currentIdx = _currentTeamIndex.value
                    if (currentIdx != null && currentIdx < 6) {
                        _ownPokemon.value = loadedTeam[currentIdx]
                    } else if (_ownPokemon.value == null) {
                        val first = loadedTeam.indexOfFirst { it != null }
                        if (first != -1) {
                            _ownPokemon.value = loadedTeam[first]
                            _currentTeamIndex.value = first
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("Storage", "Error loading team data", e)
            }
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
    }

    private fun base64ToBitmap(base64: String): Bitmap {
        val decodedBytes = Base64.decode(base64, Base64.DEFAULT)
        return BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
    }
}
