package com.example.pmtu

import android.content.Context
import android.util.Log

class MoveRepository(private val context: Context) {
    private val moveTypeCache = mutableMapOf<String, String>()
    private val moveIgnoreCache = mutableMapOf<String, Boolean>()

    data class MoveData(
        val type: String?,
        val ignores: Boolean,
        val wurfel: String?,
        val powerStr: String?,
        val powerStab: String?,
        val englishName: String?,
        val germanName: String?
    )

    fun fetchMoveData(moveName: String): MoveData? {
        val cleanName = moveName.split(" (S)")[0].trim()
        try {
            val moveFiles = context.assets.list("")?.filter { it.startsWith("PMTU Moves") } ?: return null
            for (fileName in moveFiles) {
                val reader = context.assets.open(fileName).bufferedReader(Charsets.UTF_8)
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val lin2 = line?.trim()?.removeSurrounding("\"")
                    val columns = lin2?.split(",") ?: continue
                    if (columns.size < 11) continue

                    val filename = columns[10]
                    if (filename.equals(cleanName, ignoreCase = true)) {
                        val type = columns[0]
                        val ignores = if (columns.size > 17) columns[17].contains("{W Ignore}", ignoreCase = true) else false
                        val wurfel = if (columns.size > 1) columns[1] else null
                        
                        moveTypeCache[cleanName] = type
                        moveIgnoreCache[cleanName] = ignores
                        
                        val data = MoveData(
                            type = type,
                            ignores = ignores,
                            wurfel = wurfel,
                            powerStr = if (columns.size > 3) columns[3] else null,
                            powerStab = if (columns.size > 4) columns[4] else null,
                            englishName = if (columns.size > 2) columns[2] else null,
                            germanName = if (columns.size > 16) columns[16] else null
                        )
                        reader.close()
                        return data
                    }
                }
                reader.close()
            }
        } catch (e: Exception) {
            Log.e("MoveRepository", "Error searching move data", e)
        }
        return null
    }

    fun calculateMoveEffectiveness(moveType: String?, ignores: Boolean, defType1: String, defType2: String): Int {
        if (moveType == null || ignores) return 0
        return getTypeEffectiveness(moveType, defType1) + getTypeEffectiveness(moveType, defType2)
    }

    fun getTypeEffectiveness(attacker: String, defender: String): Int {
        val cleanAttacker = attacker.replace("{", "").replace("}", "").trim()
        val cleanDefender = defender.replace("{", "").replace("}", "").trim()

        val typeChart = mapOf(
            "Normal" to mapOf("Rock" to 1, "Ghost" to 0, "Steel" to 1),
            "Grass" to mapOf("Flying" to 1, "Fire" to 1, "Bug" to 1, "Poison" to 1, "Steel" to 1, "Dragon" to 1, "Grass" to 1,  "Ground" to 4, "Water" to 4, "Rock" to 4 ),
            "Fire" to mapOf("Dragon" to 1, "Water" to 1, "Fire" to 1, "Rock" to 1, "Grass" to 4, "Ice" to 4, "Bug" to 4, "Steel" to 4),
            "Water" to mapOf( "Water" to 1, "Grass" to 1, "Dragon" to 1, "Fire" to 4, "Ground" to 4, "Rock" to 4),
            "Fighting" to mapOf("Ghost" to 0, "Poison" to 1, "Flying" to 1, "Psychic" to 1, "Bug" to 1, "Fairy" to 1, "Normal" to 4, "Ice" to 4,  "Rock" to 4,  "Dark" to 4, "Steel" to 4),
            "Flying" to mapOf("Electric" to 1, "Rock" to 1, "Steel" to 1, "Grass" to 4, "Fighting" to 4, "Bug" to 4),
            "Poison" to mapOf("Steel" to 0, "Poison" to 1, "Ground" to 1, "Rock" to 1, "Ghost" to 1, "Grass" to 4, "Fairy" to 4),
            "Ground" to mapOf("Flying" to 0, "Bug" to 1, "Grass" to 1, "Fire" to 4, "Electric" to 4, "Poison" to 4, "Rock" to 4, "Steel" to 4),
            "Rock" to mapOf("Fighting" to 1, "Ground" to 1, "Steel" to 1, "Fire" to 4, "Ice" to 4, "Flying" to 4, "Bug" to 4),
            "Bug" to mapOf("Fire" to 1, "Fighting" to 1, "Poison" to 1, "Flying" to 1, "Ghost" to 1, "Steel" to 1, "Fairy" to 1, "Psychic" to 4, "Grass" to 4, "Dark" to 4),
            "Ghost" to mapOf("Normal" to 0, "Dark" to 1, "Psychic" to 4, "Ghost" to 4 ),
            "Electric" to mapOf("Ground" to 0,"Dragon" to 1, "Electric" to 1, "Grass" to 1, "Water" to 4, "Flying" to 4),
            "Psychic" to mapOf("Dark" to 0, "Psychic" to 1, "Steel" to 1,"Fighting" to 4, "Poison" to 4),
            "Ice" to mapOf("Fire" to 1, "Water" to 1, "Ice" to 1, "Steel" to 1, "Ground" to 4, "Grass" to 4, "Flying" to 4, "Dragon" to 4),
            "Dragon" to mapOf("Dragon" to 4, "Steel" to 1, "Fairy" to 0),
            "Dark" to mapOf("Fighting" to 1, "Psychic" to 4, "Ghost" to 4, "Dark" to 1, "Fairy" to 1),
            "Steel" to mapOf("Steel" to 1, "Fire" to 1, "Water" to 1, "Electric" to 1, "Ice" to 4, "Rock" to 4, "Fairy" to 4),
            "Fairy" to mapOf("Steel" to 1, "Fire" to 1, "Poison" to 1, "Dragon" to 4, "Dark" to 4, "Fighting" to 4)
        )
        val type1Effectiveness = typeChart[cleanAttacker]?.get(cleanDefender) ?: 2
        if (type1Effectiveness == 0){return -100}
        if (type1Effectiveness == 1){return -2}
        if (type1Effectiveness == 4){return 2}
        return 0
    }

    fun getTMData(gen: String, number: String): TMData? {
        try {
            val reader = context.assets.open("TM Cards - TM List.csv").bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val columns = line?.split(",") ?: continue
                if (columns.size >= 5 && columns[0] == gen && columns[1] == number) {
                    val data = TMData(
                        type = columns[2].replace("{", "").replace("}", "").trim(),
                        name = columns[3],
                        isStabCsv = columns[4].trim().equals("TRUE", ignoreCase = true)
                    )
                    reader.close()
                    return data
                }
            }
            reader.close()
        } catch (e: Exception) {
            Log.e("MoveRepository", "Error reading TM list", e)
        }
        return null
    }

    data class TMData(val type: String, val name: String, val isStabCsv: Boolean)
}
