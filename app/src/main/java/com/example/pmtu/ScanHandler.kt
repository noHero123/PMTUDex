package com.example.pmtu

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ScanHandler(
    private val context: Context,
    private val viewModel: ResultViewModel,
    private val pokedexRepository: PokedexRepository,
    private val moveRepository: MoveRepository,
    private val trainerRepository: TrainerRepository
) {
    sealed class ScanResult {
        data class Connect(val ip: String) : ScanResult()
        data class Pokemon(val number: String) : ScanResult()
        object Handled : ScanResult()
        object Unknown : ScanResult()
    }

    fun handleScan(scannedText: String, lifecycleOwner: LifecycleOwner): ScanResult {
        if (scannedText.startsWith("pmtu_connect", ignoreCase = true)) {
            val ip = scannedText.substring("pmtu_connect".length)
            return ScanResult.Connect(ip)
        } else if (scannedText.startsWith("tr", ignoreCase = true)) {
            handleTrainerScan(scannedText, lifecycleOwner)
            return ScanResult.Handled
        } else if (scannedText.startsWith("t", ignoreCase = true)) {
            handleTMScan(scannedText, lifecycleOwner)
            return ScanResult.Handled
        } else if (scannedText.startsWith("iz", ignoreCase = true)) {
            handleZMoveScan(scannedText, lifecycleOwner)
            return ScanResult.Handled
        } else if (scannedText.startsWith("it", ignoreCase = true)) {
            handleTeraScan(scannedText)
            return ScanResult.Handled
        } else if (scannedText.startsWith("ie", ignoreCase = true)) {
            handleTypeEnhancerScan(scannedText)
            return ScanResult.Handled
        } else if (scannedText.startsWith("ib", ignoreCase = true)) {
            handleBaseItemScan(scannedText)
            return ScanResult.Handled
        } else if (scannedText.startsWith("fm", ignoreCase = true)) {
            handleWeatherScan(scannedText)
            return ScanResult.Handled
        } else if (scannedText.firstOrNull()?.isDigit() == true) {
            return ScanResult.Pokemon(scannedText)
        }
        return ScanResult.Unknown
    }

    private fun handleWeatherScan(scannedText: String) {
        val weatherName = scannedText.substring(2)
        viewModel.setOwnWeather(weatherName)
        viewModel.setUpdateUI()
    }

    private fun handleTMScan(scannedText: String, lifecycleOwner: LifecycleOwner) {
        val own = viewModel.ownPokemon.value ?: run {
            Toast.makeText(context, "Scan a Pokémon first!", Toast.LENGTH_SHORT).show()
            return
        }
        if (own.isTrainerPokemon) {
            Toast.makeText(context, "Trainer Pokémon cannot attach cards!", Toast.LENGTH_SHORT).show()
            return
        }

        val data = scannedText.substring(1)
        val parts = data.split("_")
        if (parts.size != 2) return

        val gen = parts[0]
        val number = parts[1]

        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val tmData = moveRepository.getTMData(gen, number)
            if (tmData != null) {
                val pType1 = own.type1.replace("{", "").replace("}", "").trim()
                val pType2 = own.type2.replace("{", "").replace("}", "").trim()
                val isRealStab = tmData.isStabCsv && (tmData.type.equals(pType1, ignoreCase = true) ||
                        (pType2 != "None" && tmData.type.equals(pType2, ignoreCase = true)))
                val moveName = if (isRealStab) "${tmData.name} (S)" else tmData.name

                withContext(Dispatchers.Main) {
                    clearOtherAttachments(own)
                    own.move3 = moveName
                    viewModel.setOwnPokemon(own, viewModel.currentTeamIndex.value)
                    viewModel.saveTeamData()
                    viewModel.setUpdateUI()
                }
            }
        }
    }

    private fun handleZMoveScan(scannedText: String, lifecycleOwner: LifecycleOwner) {
        val own = viewModel.ownPokemon.value ?: run {
            Toast.makeText(context, "Scan a Pokémon first!", Toast.LENGTH_SHORT).show()
            return
        }
        if (own.isTrainerPokemon) {
            Toast.makeText(context, "Trainer Pokémon cannot attach cards!", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val moveName = moveRepository.getZMoveForPokemon(scannedText, own)
            withContext(Dispatchers.Main) {
                if (moveName != null) {
                    clearOtherAttachments(own)
                    own.move3 = moveName
                    viewModel.setOwnPokemon(own, viewModel.currentTeamIndex.value)
                    viewModel.saveTeamData()
                    viewModel.setUpdateUI()
                } else {
                    Toast.makeText(context, "Pokémon cannot learn this Z-Move", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleTeraScan(scannedText: String) {
        val own = viewModel.ownPokemon.value ?: return
        if (own.isTrainerPokemon) return
        val type = scannedText.substring(2)
        val formattedType = type.lowercase().replaceFirstChar { it.uppercase() }
        clearOtherAttachments(own)
        own.teraType = formattedType
        viewModel.setOwnPokemon(own, viewModel.currentTeamIndex.value)
        viewModel.saveTeamData()
        viewModel.setUpdateUI()
    }

    private fun handleTypeEnhancerScan(scannedText: String) {
        val own = viewModel.ownPokemon.value ?: return
        if (own.isTrainerPokemon) return
        val type = scannedText.substring(2)
        val formattedType = type.lowercase().replaceFirstChar { it.uppercase() }
        clearOtherAttachments(own)
        own.typeEnhancerType = formattedType
        viewModel.setOwnPokemon(own, viewModel.currentTeamIndex.value)
        viewModel.saveTeamData()
        viewModel.setUpdateUI()
    }

    private fun handleBaseItemScan(scannedText: String) {
        val own = viewModel.ownPokemon.value ?: return
        if (own.isTrainerPokemon) return
        val itemName = scannedText.substring(2)
        clearOtherAttachments(own)
        own.baseItem = itemName
        viewModel.setOwnPokemon(own, viewModel.currentTeamIndex.value)
        viewModel.saveTeamData()
        viewModel.setUpdateUI()
    }

    private fun handleTrainerScan(scannedText: String, lifecycleOwner: LifecycleOwner) {
        lifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val trainerData = trainerRepository.getTrainerPokemon(scannedText)
            if (trainerData != null) {
                val info = pokedexRepository.findPokemonByNumber(
                    trainerData.pokemonId,
                    "https://www.serebii.net/pokedex-sv/icon/${trainerData.pokemonId}.png",
                    "https://www.serebii.net/pokemon/art/${trainerData.pokemonId}.png"
                )
                if (info != null) {
                    val trainerPokemon = info.copy(
                        base_level = trainerData.baseLevel,
                        move1 = trainerData.move1,
                        move2 = trainerData.move2,
                        move3 = trainerData.move3,
                        isTrainerPokemon = true
                    )
                    withContext(Dispatchers.Main) {
                        viewModel.setOwnPokemon(trainerPokemon, null)
                        viewModel.setUpdateUI()
                    }
                }
            }
        }
    }

    private fun clearOtherAttachments(pokemon: PokemonInfo) {
        pokemon.move3 = null
        pokemon.teraType = null
        pokemon.isTeraActivated = false
        pokemon.typeEnhancerType = null
        pokemon.baseItem = null
    }
}
