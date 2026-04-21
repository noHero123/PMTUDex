package com.example.pmtu

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.size

class TrainerRepository(private val context: Context) {

    data class TrainerPokemonData(
        val trainerCardId: String,
        val pokemonId: String,
        val baseLevel: Int,
        val move1: String,
        val move2: String,
        val move3: String
    )

    // Cache to store the CSV data in memory
    private var cachedTrainers: Map<String, TrainerPokemonData>? = null


    private fun loadTrainers(): Map<String, TrainerPokemonData> {
        // Return cache if already loaded
        cachedTrainers?.let { return it }

        val trainerMap = mutableMapOf<String, TrainerPokemonData>()
        try {
            context.assets.open("trainer_Gen1.csv").bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    val parts = parseCsvLine(line)
                    if (parts.size >= 6) {
                        val trainerId = parts[0]
                        trainerMap[trainerId] = TrainerPokemonData(
                            trainerCardId = trainerId,
                            pokemonId = parts[1],
                            baseLevel = parts[2].toIntOrNull() ?: 1,
                            move1 = parts[3],
                            move2 = parts[4],
                            move3 = parts[5]
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TrainerRepository", "Error reading trainer_Gen1.csv", e)
        }

        cachedTrainers = trainerMap
        return trainerMap
    }

    /**
     * Helper to handle quotes and internal commas during CSV parsing
     */
    private fun parseCsvLine(line: String): List<String> {
        val parts = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        for (char in line) {
            if (char == '\"') {
                inQuotes = !inQuotes
            } else if (char == ',' && !inQuotes) {
                parts.add(current.toString().trim())
                current = StringBuilder()
            } else {
                current.append(char)
            }
        }
        parts.add(current.toString().trim())
        return parts
    }

    fun getTrainerPokemon(trainerCardId: String): TrainerPokemonData? {
        // Queries the memory cache instead of reading the file every time
        return loadTrainers()[trainerCardId]
    }
}
