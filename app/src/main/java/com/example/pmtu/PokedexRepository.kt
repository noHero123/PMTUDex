package com.example.pmtu

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

class PokedexRepository(private val context: Context) {

    fun getGermanText(number: String): MutableList<String> {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val lang = prefs.getString("language", "en")
        val filename = if (lang == "de") "pokedex_ger.csv" else "pokedex_en.csv"
        val entries = mutableListOf<String>()
        try {
            val reader = context.assets.open(filename).bufferedReader(Charsets.UTF_8)
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val lin2 = line?.drop(1)?.dropLast(1)
                val rawColumns = lin2?.split("\",\"") ?: continue
                val columns = rawColumns.map { it.trim().removeSurrounding("\"") }

                if (columns.isNotEmpty() && columns[0] == number) {
                    if (columns.size > 2) {
                        for (i in 2 until columns.size) {
                            entries.add(columns[i])
                        }
                    }
                    break
                }
            }
            reader.close()
        } catch (e: Exception) {
            Log.e("PokedexRepository", "Error reading $filename", e)
        }
        return entries
    }

    fun getGermanName(number: String): String {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val lang = prefs.getString("language", "en")

        val filename = if (lang == "de") "pokedex_ger.csv" else "pokedex_en.csv"
        try {
            val reader = context.assets.open(filename).bufferedReader(Charsets.UTF_8)
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val lin2 = line?.drop(1)?.dropLast(1)
                val rawColumns = lin2?.split("\",\"") ?: continue
                val columns = rawColumns.map { it.trim().removeSurrounding("\"") }

                if (columns.isNotEmpty() && columns[0] == number) {
                    reader.close()
                    return columns[1]
                }
            }
            reader.close()
        } catch (e: Exception) {
            Log.e("PokedexRepository", "Error reading $filename", e)
        }
        return "Missing No."
    }

    fun findPokemonByNumber(number: String, spriteUrl: String, artUrl: String): PokemonInfo? {
        try {
            val reader = context.assets.open("pokedex.csv").bufferedReader(Charsets.UTF_8)
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val lin2 = line?.drop(1)?.dropLast(1)
                val rawColumns = lin2?.split("\",\"") ?: continue
                val columns = rawColumns.map { it.trim().removeSurrounding("\"") }
                
                if (columns.isNotEmpty() && columns[0] == number) {
                    val entries = getGermanText(number)
                    entries.shuffle()
                    val pokeName = getGermanName(number)
                    val info = PokemonInfo(
                        id = number,
                        name = pokeName,
                        base_level = columns[2].toInt(),
                        type1 = columns[3],
                        type2 = columns[4],
                        pokedexEntries = entries,
                        move1 = columns[5].split("/").last(),
                        move2 = columns[6].split("/").last(),
                        spriteUrl = spriteUrl,
                        artUrl = artUrl,
                        additionalLevel = 0,
                        nextPokedexIndex = 0
                    )
                    reader.close()
                    return info
                }
            }
            reader.close()
        } catch (e: Exception) {
            Log.e("PokedexRepository", "Error reading pokedex.csv", e)
        }
        return null
    }

    fun getEvolutions(currentId: String): Pair<List<String>, List<String>> {
        val evos = mutableListOf<String>()
        val preEvos = mutableListOf<String>()
        val quotedId = "\"$currentId\""

        try {
            val reader = context.assets.open("evolutions.csv").bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val columns = line?.split(",") ?: continue
                if (columns.isEmpty()) continue

                if (columns[0] == quotedId) {
                    for (i in 1 until columns.size) {
                        if (columns[i].isNotEmpty()) evos.add(columns[i].removeSurrounding("\""))
                    }
                }

                for (i in 1 until columns.size) {
                    if (columns[i] == quotedId) {
                        preEvos.add(columns[0].removeSurrounding("\""))
                    }
                }
            }
            reader.close()
        } catch (e: Exception) {
            Log.e("PokedexRepository", "Error reading evolutions.csv", e)
        }
        return Pair(evos, preEvos)
    }
}
