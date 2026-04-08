package com.example.pmtu

import android.content.Context
import android.util.Log

class MoveRepository(private val context: Context) {
    private val fullMoveDataCache = mutableMapOf<String, MoveData>()
    private val tmDataCache = mutableMapOf<String, TMData>()
    private val zMoveCache = mutableMapOf<String, String?>()
    private var movesLoaded = false
    private var tmsLoaded = false

    data class MoveData(
        val type: String?,
        val wurfel: String?,
        val powerStr: String?,
        val powerStab: String?,
        val englishName: String?,
        val germanName: String?,
        val ignores: Boolean = false,
        val effect1: String? = null,
        val effect2: String? = null
    )

    data class PowerResult(
        val power: Int,
        val effectiveness: Int,
        val cleanType: String,
        val moveData: MoveData
    )

    private fun loadAllMoves() {
        if (movesLoaded) return
        try {
            val assetFiles = context.assets.list("") ?: return
            val moveFiles = assetFiles.filter { it.startsWith("PMTU Moves") }
            
            for (fileName in moveFiles) {
                context.assets.open(fileName).use { inputStream ->
                    val reader = inputStream.bufferedReader(Charsets.UTF_8)
                    reader.forEachLine { line ->
                        val columns = parseCsvLine(line)
                        if (columns.size >= 11) {
                            val type = columns[0]
                            val ignores = if (columns.size > 17) columns[17].contains("{W Ignore}", ignoreCase = true) else false
                            val wurfel = if (columns.size > 1) columns[1] else null
                            
                            val data = MoveData(
                                type = type,
                                ignores = ignores,
                                wurfel = wurfel,
                                powerStr = if (columns.size > 3) columns[3] else null,
                                powerStab = if (columns.size > 4) columns[4] else null,
                                englishName = if (columns.size > 2) columns[2] else null,
                                germanName = if (columns.size > 16) columns[16] else null,
                                effect1 = if (columns.size > 5) columns[5] else null,
                                effect2 = if (columns.size > 6) columns[6] else null
                            )
                            val moveNameCol = columns[2].lowercase()
                            val filenameCol = columns[10].lowercase()
                            if (moveNameCol.isNotEmpty()) fullMoveDataCache[moveNameCol] = data
                            if (filenameCol.isNotEmpty()) fullMoveDataCache[filenameCol] = data
                        }
                    }
                }
            }
            movesLoaded = true
        } catch (e: Exception) {
            Log.e("MoveRepository", "Error loading all moves", e)
        }
    }

    fun fetchMoveData(moveName: String): MoveData? {
        loadAllMoves()
        val cleanName = moveName.split(" (S)")[0].trim().lowercase()
        return fullMoveDataCache[cleanName]
    }

    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c == '\"') {
                inQuotes = !inQuotes
            } else if (c == ',' && !inQuotes) {
                result.add(current.toString().trim())
                current = StringBuilder()
            } else {
                current.append(c)
            }
            i++
        }
        result.add(current.toString().trim())
        return result
    }

    fun isStab(pokemon: PokemonInfo, type: String?): Boolean {
        if (type == null) return false
        val cleanType = type.replace("{", "").replace("}", "").trim()
        val pType1 = pokemon.type1.replace("{", "").replace("}", "").trim()
        val pType2 = pokemon.type2.replace("{", "").replace("}", "").trim()
        
        return cleanType.equals(pType1, ignoreCase = true) || 
               (pType2 != "None" && cleanType.equals(pType2, ignoreCase = true))
    }

    fun calculateMovePower(
        moveName: String,
        pokemon: PokemonInfo,
        enemy: PokemonInfo?,
        ownWeather: String?,
        enemyWeather: String?
    ): PowerResult? {
        val moveData = fetchMoveData(moveName) ?: return null

        val isStabSuffix = moveName.endsWith("(S)")
        var powerStr = if (isStabSuffix) moveData.powerStab else moveData.powerStr
        powerStr = powerStr?.replace("*", "") ?: "0"

        var powerval: Int
        var originalBasePower: Int
        if (powerStr.equals("1-2 Lvl", ignoreCase = true)) {
            originalBasePower = (pokemon.base_level + pokemon.additionalLevel) / 2
            powerval = originalBasePower
        } else {
            originalBasePower = powerStr.toIntOrNull() ?: 0
            powerval = originalBasePower
        }

        if (pokemon.baseItem.equals("Alph", ignoreCase = true) && originalBasePower > 0 && originalBasePower < 4) {
            originalBasePower += 2
            if (originalBasePower > 4) originalBasePower = 4
            powerval = originalBasePower
        }

        powerval += pokemon.base_level + pokemon.additionalLevel

        val cleanType = moveData.type?.replace("{", "")?.replace("}", "")?.trim() ?: ""

        if (pokemon.isTeraActivated && pokemon.teraType?.equals(cleanType, ignoreCase = true) == true) {
            powerval += 1
        }

        val originalBasePowerCheck: Int = if (powerStr.equals("1-2 Lvl", ignoreCase = true)) 1 else powerStr.toIntOrNull() ?: 0
        if (pokemon.typeEnhancerType?.equals(cleanType, ignoreCase = true) == true && originalBasePowerCheck >= 1) {
            powerval += 1
        }

        if ((ownWeather == "Electric Terrain" || enemyWeather == "Electric Terrain") && cleanType.equals("Electric", ignoreCase = true)) {
            powerval += 1
        }
        if ((ownWeather == "Grassy Terrain" || enemyWeather == "Grassy Terrain") && cleanType.equals("Grass", ignoreCase = true)) {
            powerval += 1
        }
        if ((ownWeather == "Psychic Terrain" || enemyWeather == "Psychic Terrain") && cleanType.equals("Psychic", ignoreCase = true)) {
            powerval += 1
        }
        if ((ownWeather == "Rainy" || enemyWeather == "Rainy") && cleanType.equals("Water", ignoreCase = true)) {
            powerval += 1
        }
        if ((ownWeather == "Rainy" || enemyWeather == "Rainy") && cleanType.equals("Fire", ignoreCase = true)) {
            powerval -= 1
        }
        if ((ownWeather == "Sunny" || enemyWeather == "Sunny") && cleanType.equals("Fire", ignoreCase = true)) {
            powerval += 1
        }
        if ((ownWeather == "Sunny" || enemyWeather == "Sunny") && cleanType.equals("Water", ignoreCase = true)) {
            powerval -= 1
        }
        if (ownWeather == "Misty Terrain" || enemyWeather == "Misty Terrain") {
            if (cleanType.equals("Fairy", ignoreCase = true)) powerval += 1
            else if (cleanType.equals("Dragon", ignoreCase = true)) powerval -= 1
        }

        var effectiveness = 0
        if (enemy != null) {
            effectiveness = calculateMoveEffectiveness(moveData.type, moveData.ignores, enemy.type1, enemy.type2)
            if (effectiveness == -4) effectiveness = -3
            if (effectiveness == 4) effectiveness = 3
            
            if (effectiveness < -4) powerval = -3
            else powerval += effectiveness
        }

        if (ownWeather == "Stealth Rock") {
            var sr_change = -1
            val pType1 = pokemon.type1.replace("{", "").replace("}", "").trim()
            val pType2 = pokemon.type2.replace("{", "").replace("}", "").trim()
            val sr_eff = calculateMoveEffectiveness("Rock", moveData.ignores, pType1, pType2)
            if (sr_eff <= -1 || pType1 == "Rock" || pType2 == "Rock") sr_change = 0
            if (sr_eff >= 1) sr_change = -2
            powerval += sr_change
        }

        if (ownWeather == "Hail" || enemyWeather == "Hail") {
            val pType1 = pokemon.type1.replace("{", "").replace("}", "").trim()
            val pType2 = pokemon.type2.replace("{", "").replace("}", "").trim()
            if (!pType1.equals("Ice", ignoreCase = true) && !pType2.equals("Ice", ignoreCase = true)) {
                powerval -= 1
            }
        }

        if (ownWeather == "Sandy" || enemyWeather == "Sandy") {
            val pType1 = pokemon.type1.replace("{", "").replace("}", "").trim()
            val pType2 = pokemon.type2.replace("{", "").replace("}", "").trim()
            if (!pType1.equals("Ground", ignoreCase = true) && !pType2.equals("Ground", ignoreCase = true) &&
                !pType1.equals("Rock", ignoreCase = true) && !pType2.equals("Rock", ignoreCase = true) &&
                !pType1.equals("Steel", ignoreCase = true) && !pType2.equals("Steel", ignoreCase = true)) {
                powerval -= 1
            }
        }
        if (ownWeather == "Spikes") powerval -= 1

        if (pokemon.baseItem.equals("Vita", ignoreCase = true) || pokemon.baseItem.equals("Shin", ignoreCase = true)) {
            powerval += 1
        }

        return PowerResult(powerval, effectiveness, cleanType, moveData)
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
        if (type1Effectiveness == 0){
            val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            return if (prefs.getBoolean("disable_immunities", false)) -2 else -100
        }
        if (type1Effectiveness == 1) return -2
        if (type1Effectiveness == 4) return 2
        return 0
    }

    fun getPokemonEffectiveness(pokemon: PokemonInfo, enemy: PokemonInfo): Int {
        val move1Data = fetchMoveData(pokemon.move1)
        val move2Data = fetchMoveData(pokemon.move2)
        val move3Data = pokemon.move3?.let { fetchMoveData(it) }

        var hasSuper = false
        var hasNeutral = false

        fun check(data: MoveData?) {
            if (data == null) return
            val total = calculateMoveEffectiveness(data.type, data.ignores, enemy.type1, enemy.type2)
            if (total > 0) hasSuper = true
            if (total >= 0) hasNeutral = true
        }

        check(move1Data)
        check(move2Data)
        check(move3Data)

        return if (hasSuper) 1 else if (!hasNeutral) -1 else 0
    }

    fun isPokemonDangerous(attacker: PokemonInfo, target: PokemonInfo): Int {
        return getPokemonEffectiveness(attacker, target)
    }

    private fun loadAllTMs() {
        if (tmsLoaded) return
        try {
            context.assets.open("TM Cards - TM List.csv").use { inputStream ->
                val reader = inputStream.bufferedReader()
                reader.forEachLine { line ->
                    val columns = parseCsvLine(line)
                    if (columns.size >= 5) {
                        val gen = columns[0]
                        val number = columns[1]
                        val data = TMData(
                            type = columns[2].replace("{", "").replace("}", "").trim(),
                            name = columns[3],
                            isStabCsv = columns[4].trim().equals("TRUE", ignoreCase = true)
                        )
                        tmDataCache["${gen}_${number}"] = data
                    }
                }
            }
            tmsLoaded = true
        } catch (e: Exception) {
            Log.e("MoveRepository", "Error reading TM list", e)
        }
    }

    fun getTMData(gen: String, number: String): TMData? {
        loadAllTMs()
        return tmDataCache["${gen}_${number}"]
    }

    fun getZMoveForPokemon(scannedId: String, pokemon: PokemonInfo): String? {
        val key = "${scannedId}_${pokemon.id}"
        if (zMoveCache.containsKey(key)) return zMoveCache[key]

        try {
            context.assets.open("zmoves.csv").use { inputStream ->
                val reader = inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val columns = parseCsvLine(line ?: "")
                    if (columns.isEmpty()) continue

                    if (columns[0].equals(scannedId, ignoreCase = true)) {
                        val defaultZMoveName = if (columns.size > 1) columns[1] else ""
                        
                        val baseZMoveData = fetchMoveData(defaultZMoveName) ?: run {
                            Log.e("MoveRepository", "Default Z-Move data not found: $defaultZMoveName")
                            null
                        }
                        val zType = baseZMoveData?.type ?: run {
                            zMoveCache[key] = null
                            return null
                        }
                        
                        val m1Data = fetchMoveData(pokemon.move1)
                        val m2Data = fetchMoveData(pokemon.move2)
                        
                        val knowsRequiredType = zType.equals(m1Data?.type, ignoreCase = true) || 
                                               zType.equals(m2Data?.type, ignoreCase = true)
                        
                        if (!knowsRequiredType) {
                            zMoveCache[key] = null
                            return null
                        }

                        var finalMoveName = defaultZMoveName
                        if (columns.size > 2) {
                            for (i in 2 until columns.size) {
                                val altEntry = columns[i]
                                val commaIdx = altEntry.indexOf(",")
                                if (commaIdx != -1) {
                                    val pId = altEntry.substring(0, commaIdx).trim()
                                    val altMoveName = altEntry.substring(commaIdx + 1).trim()
                                    if (pId == pokemon.id) {
                                        finalMoveName = altMoveName
                                        break
                                    }
                                }
                            }
                        }
                        zMoveCache[key] = finalMoveName
                        return finalMoveName
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("MoveRepository", "Error in getZMoveForPokemon", e)
        }
        zMoveCache[key] = null
        return null
    }

    data class TMData(val type: String, val name: String, val isStabCsv: Boolean)
}
