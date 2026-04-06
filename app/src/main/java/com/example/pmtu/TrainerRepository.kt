package com.example.pmtu

import android.content.Context
import android.util.Log

class TrainerRepository(private val context: Context) {

    data class TrainerPokemonData(
        val trainerCardId: String,
        val pokemonId: String,
        val baseLevel: Int,
        val move1: String,
        val move2: String,
        val move3: String
    )

    fun getTrainerPokemon(trainerCardId: String): TrainerPokemonData? {
        try {
            val reader = context.assets.open("trainer_Gen1.csv").bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                // Parse CSV manually to handle quotes and internal commas
                val parts = mutableListOf<String>()
                var current = StringBuilder()
                var inQuotes = false
                for (char in line!!) {
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

                if (parts.size >= 6 && parts[0] == trainerCardId) {
                    reader.close()
                    return TrainerPokemonData(
                        trainerCardId = parts[0],
                        pokemonId = parts[1],
                        baseLevel = parts[2].toIntOrNull() ?: 1,
                        move1 = parts[3],
                        move2 = parts[4],
                        move3 = parts[5]
                    )
                }
            }
            reader.close()
        } catch (e: Exception) {
            Log.e("TrainerRepository", "Error reading trainer_Gen1.csv", e)
        }
        return null
    }
}
