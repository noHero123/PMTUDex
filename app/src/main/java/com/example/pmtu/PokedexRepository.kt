package com.example.pmtu

import android.content.Context
import android.util.Log

class PokedexRepository(private val context: Context) {

    private val pokedexCache: Map<String, List<String>> by lazy {
        val cache = mutableMapOf<String, List<String>>()
        try {
            context.assets.open("pokedex.csv").bufferedReader(Charsets.UTF_8).use { reader ->
                reader.lineSequence().forEach { line ->
                    if (line.isNotBlank()) {
                        val lin2 = line.drop(1).dropLast(1)
                        val rawColumns = lin2.split("\",\"")
                        val columns = rawColumns.map { it.trim().removeSurrounding("\"") }
                        if (columns.isNotEmpty()) {
                            cache[columns[0]] = columns
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PokedexRepository", "Error reading pokedex.csv into cache", e)
        }
        cache
    }

    private val evolutionsCache: List<List<String>> by lazy {
        val cache = mutableListOf<List<String>>()
        try {
            context.assets.open("evolutions.csv").bufferedReader().use { reader ->
                reader.lineSequence().forEach { line ->
                    if (line.isNotBlank()) {
                        val columns = line.split(",")
                        if (columns.isNotEmpty()) {
                            cache.add(columns)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PokedexRepository", "Error reading evolutions.csv into cache", e)
        }
        cache
    }

    fun getGermanText(number: String): MutableList<String> {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val lang = prefs.getString("language", "en")
        val filename = if (lang == "de") "pokedex_ger.csv" else "pokedex_en.csv"
        val entries = mutableListOf<String>()
        try {
            context.assets.open(filename).bufferedReader(Charsets.UTF_8).use { reader ->
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
            }
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
            context.assets.open(filename).bufferedReader(Charsets.UTF_8).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val lin2 = line?.drop(1)?.dropLast(1)
                    val rawColumns = lin2?.split("\",\"") ?: continue
                    val columns = rawColumns.map { it.trim().removeSurrounding("\"") }

                    if (columns.isNotEmpty() && columns[0] == number) {
                        return columns[1]
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("PokedexRepository", "Error reading $filename", e)
        }
        return "Missing No."
    }

    fun findPokemonByNumber(number: String, spriteUrl: String, artUrl: String): PokemonInfo? {
        val columns = pokedexCache[number] ?: return null
        
        val entries = getGermanText(number)
        entries.shuffle()
        val pokeName = getGermanName(number)
        return PokemonInfo(
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
    }

    fun getEvolutions(currentId: String): Pair<List<String>, List<String>> {
        val evos = mutableListOf<String>()
        val preEvos = mutableListOf<String>()
        val quotedId = "\"$currentId\""

        evolutionsCache.forEach { columns ->
            if (columns.isEmpty()) return@forEach

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
        return Pair(evos, preEvos)
    }

    fun isFullyEvolved(number: String): Boolean {
        val quotedId = "\"$number\""
        
        evolutionsCache.forEach { columns ->
            if (columns.isEmpty()) return@forEach

            // Check if this row is for our current Pokemon
            if (columns[0] == quotedId) {
                // Check if any subsequent column contains a standard evolution ID
                // We filter out empty strings AND special forms like Mega/Gmax
                val hasTrueEvolution = columns.drop(1).any { rawId ->
                    val cleanId = rawId.trim().removeSurrounding("\"")
                    // It is a "True" evolution if the cell is not empty
                    // AND it doesn't represent a temporary form (Mega/Gmax)
                    cleanId.isNotEmpty() &&
                            !cleanId.contains("m", ignoreCase = true) &&
                            !cleanId.contains("gi", ignoreCase = true)
                }

                // If it has a standard evolution path, it's not fully evolved
                if (hasTrueEvolution) {
                    return false
                }
            }
        }

        // If we didn't find the ID in the first column, or we found it but
        // all evolution columns were empty, it is fully evolved.
        return true
    }
}
