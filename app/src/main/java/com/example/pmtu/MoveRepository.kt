package com.example.pmtu

import android.content.Context
import android.util.Log
import androidx.camera.view.impl.ZoomGestureDetector

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

            //add moveless attack for status conditions:
            val typeLessData = MoveData(
                type = "Typeless",
                ignores = false,
                wurfel = "",
                powerStr = "0",
                powerStab = "0",
                englishName = "Typeless",
                germanName = "Typenlos",
                effect1 = "",
                effect2 = ""
            )
            fullMoveDataCache["typeless"] = typeLessData
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
        val pType1 = pokemon.getType1().replace("{", "").replace("}", "").trim()
        val pType2 = pokemon.getType2().replace("{", "").replace("}", "").trim()
        
        return cleanType.equals(pType1, ignoreCase = true) || 
               (pType2 != "None" && cleanType.equals(pType2, ignoreCase = true))
    }

    fun getBasePower(moveName: String, pokemon: PokemonInfo?, enemy: PokemonInfo?): Int {
        val moveData = fetchMoveData(moveName) ?: return 0
        val isStabSuffix = moveName.endsWith("(S)")
        var powerStr = if (isStabSuffix) moveData.powerStab else moveData.powerStr
        powerStr = powerStr?.replace("*", "") ?: "0"
        var originalBasePower: Int
        if (powerStr.equals("1-2 Lvl", ignoreCase = true)) {
            if ((moveData.effect1 == "{W You}") || (moveData.effect2 == "{W You}")) {
                if (pokemon == null) return 0
                originalBasePower = (pokemon.base_level + pokemon.additionalLevel) / 2
            } else {
                if (enemy == null) return 0
                originalBasePower = (enemy.base_level + enemy.additionalLevel) / 2
            }

        } else {
            originalBasePower = powerStr.toIntOrNull() ?: 0

        }
        return originalBasePower
    }

    fun calculateMovePower(
        moveName: String,
        pokemon: PokemonInfo,
        enemy: PokemonInfo?,
        ownWeather: String?,
        enemyWeather: String?,
        enemyUsesProtect : Boolean = false
    ): PowerResult? {

        //Basepower is calculated with ORIGINAL move! not dynamaxed one
        var originalBasePower = getBasePower(moveName, pokemon, enemy)

        var moveData: MoveData
        //calculate if the move is dynamaxed version:
        var is_dynamax = pokemon.isDynaActivated
        if(pokemon.isGigaDynaActivated)
        {
            if(moveName.contains("{G-Max}", ignoreCase = true))
            {
                is_dynamax = false
            }
            else
            {
                is_dynamax = true
            }
        }
        if (is_dynamax)
        {
            val newMoveName = getMaxMove(moveName, originalBasePower)
            moveData = fetchMoveData(newMoveName) ?: return null
        }else
        {
            moveData = fetchMoveData(moveName) ?: return null
        }

        var cleanType = moveData.type?.replace("{", "")?.replace("}", "")?.trim() ?: ""

        if (is_dynamax)
        {
            cleanType =  getMaxMoveType(moveName, originalBasePower)
        }

        var powerval: Int


        if(pokemon.hasTypelessMove()){
            originalBasePower = 0
            cleanType = "Typeless"
        }
        if(enemyUsesProtect)
        {
            originalBasePower = 0
        }
        powerval = originalBasePower

        if (pokemon.baseItem.equals("Alph", ignoreCase = true) && originalBasePower > 0 && originalBasePower < 4) {
            if (pokemon.isBaseItemActivated) {
                powerval += 2
                if (powerval > 4) powerval = 4
            }
        }

        if(pokemon.statusCondition.equals("Burn", ignoreCase = true))
        {
            if(powerval > 0) {
                powerval -= 1
            }
        }

        powerval += pokemon.base_level + pokemon.additionalLevel



        if (pokemon.isTeraActivated && pokemon.teraType?.equals(cleanType, ignoreCase = true) == true) {
            powerval += 1
        }

        if (pokemon.typeEnhancerType?.equals(cleanType, ignoreCase = true) == true && originalBasePower >= 1) {
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
            val pokeIsStellar = pokemon.isTeraActivated && (pokemon.teraType == "Stellar")
            val ignoreEffi = moveData.ignores || pokeIsStellar
            effectiveness = calculateMoveEffectiveness(cleanType, ignoreEffi, enemy.getType1(), enemy.getType2())
            if (effectiveness == -4) effectiveness = -3
            if (effectiveness == 4) effectiveness = 3
            
            if (effectiveness < -4) powerval = -3
            else powerval += effectiveness
        }

        if (ownWeather == "Stealth Rock") {
            var sr_change = -1
            val pType1 = pokemon.getType1().replace("{", "").replace("}", "").trim()
            val pType2 = pokemon.getType2().replace("{", "").replace("}", "").trim()
            val sr_eff = calculateMoveEffectiveness("Rock", moveData.ignores, pType1, pType2)
            if (sr_eff <= -1 || pType1 == "Rock" || pType2 == "Rock") sr_change = 0
            if (sr_eff >= 1) sr_change = -2
            powerval += sr_change
        }

        if (ownWeather == "Hail" || enemyWeather == "Hail") {
            val pType1 = pokemon.getType1().replace("{", "").replace("}", "").trim()
            val pType2 = pokemon.getType2().replace("{", "").replace("}", "").trim()
            if (!pType1.equals("Ice", ignoreCase = true) && !pType2.equals("Ice", ignoreCase = true)) {
                powerval -= 1
            }
        }

        if (ownWeather == "Sandy" || enemyWeather == "Sandy") {
            val pType1 = pokemon.getType1().replace("{", "").replace("}", "").trim()
            val pType2 = pokemon.getType2().replace("{", "").replace("}", "").trim()
            if (!pType1.equals("Ground", ignoreCase = true) && !pType2.equals("Ground", ignoreCase = true) &&
                !pType1.equals("Rock", ignoreCase = true) && !pType2.equals("Rock", ignoreCase = true) &&
                !pType1.equals("Steel", ignoreCase = true) && !pType2.equals("Steel", ignoreCase = true)) {
                powerval -= 1
            }
        }
        if (ownWeather == "Spikes") {
            powerval -= 1
        }

        if (pokemon.baseItem.equals("Vita", ignoreCase = true) || pokemon.baseItem.equals("Shin", ignoreCase = true)) {
            powerval += 1
        }

        if (pokemon.isBaseItemActivated && pokemon.baseItem.equals("Left", ignoreCase = true)) {
            powerval -= 1
        }


        return PowerResult(powerval, effectiveness, cleanType, moveData)
    }

    fun getMaxMove(moveName:String, power:Int): String
    {
        if (moveName == "")
            return ""

        val moveData = fetchMoveData(moveName) ?: return ""
        if(power <= 0)
        {
            return "Guard"
        }
        var moveType = moveData.type ?: return ""
        moveType = moveType.replace("{", "").replace("}", "").trim()

        if (moveType == "Normal")
            return "Strike"
        if (moveType == "Fighting")
            return "Knuckle"
        if (moveType == "Flying")
            return "Airstream"
        if (moveType == "Poison")
            return "Ooze"
        if (moveType == "Ground")
            return "Quake"
        if (moveType == "Rock")
            return "Rockfall"
        if (moveType == "Bug")
            return "Flutterby"
        if (moveType == "Ghost")
            return "Phantasm"
        if (moveType == "Steel")
            return "Steelspike"
        if (moveType == "Fire")
            return "Flare"
        if (moveType == "Water")
            return "Geyser"
        if (moveType == "Grass")
            return "Overgrowth"
        if (moveType == "Electric")
            return "Lightning"
        if (moveType == "Psychic")
            return "Mindstorm"
        if (moveType == "Ice")
            return "Hailstorm"
        if (moveType == "Dragon")
            return "Wyrmwind"
        if (moveType == "Dark")
            return "Darkness"
        if (moveType == "Fairy")
            return "Starfall"
        return "Guard"
    }

    fun getMaxMoveType(moveName:String, power:Int): String
    {
        if (moveName == "")
            return "Normal"

        val moveData = fetchMoveData(moveName) ?: return "Normal"
        if(power <= 0)
        {
            return "Normal"
        }
        var moveType = moveData.type ?: return "Normal"
        moveType = moveType.replace("{", "").replace("}", "").trim()
        return moveType
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

    private fun getDynamaxEffect(moveName:String):String
    {
        if (moveName == "Strike")
            return "W Priority"
        if (moveName == "Knuckle")
            return "W Adv 1"
        if (moveName == "Airstream")
            return "W Priority"
        if (moveName == "Ooze")
            return "W Adv 1"
        if (moveName == "Rockfall")
            return "Sandstorm"
        if (moveName == "Flutterby")
            return "B Dis 1"
        if (moveName == "Phantasm")
            return "W Adv 1"
        if (moveName == "Steelspike")
            return "B Dis 1"
        if (moveName == "Flare")
            return "Sunny"
        if (moveName == "Geyser")
            return "Rain"
        if (moveName == "Overgrowth")
            return "Grassy Terrain"
        if (moveName == "Lightning")
            return "Electric Terrain"
        if (moveName == "Mindstorm")
            return "Psychic Terrain"
        if (moveName == "Hailstorm")
            return "Hail"
        if (moveName == "Wyrmwind")
            return "B Dis 1"
        if (moveName == "Darkness")
            return "W Adv 1"
        if (moveName == "Starfall")
            return "Misty Terrain"
        if (moveName == "Guard")
            return "W Prot 1"
        return ""
    }

    private fun getGigaDynamaxEffect(moveName:String):List<String>
    {
        var effects = mutableListOf<String>()
        if (moveName.equals("BEFUDDLE", ignoreCase = true)){
            effects.add("B Pois 2")
            effects.add("B Para 4")
            effects.add("B Sleep 6")
        }
        if (moveName.equals("DEPLETION", ignoreCase = true)){
            effects.add("W Recharge")
        }
        if (moveName.equals("STUN SHOCK", ignoreCase = true)){
            effects.add("B Pois 3")
            effects.add("B Para 6")
        }
        if (moveName.equals("WIND RAGE", ignoreCase = true)){
            effects.add("Clear")
        }

        return effects
    }

    fun getAllEffects(result: MoveRepository.PowerResult,
                      ownPokemon: PokemonInfo?,
                      enemyPokemon: PokemonInfo?,
                      ownWeather: String?,
                      enemyWeather: String?,
                      pokedexRepository: PokedexRepository) : List<Pair<String,String>> {
        var usedKing = false
        var usedZoom = false
        val moveData = result.moveData
        var allEffects = mutableListOf<Pair<String,String>>()
        if(moveData.englishName==null)
        {
            return allEffects
        }
        var finalMoveName = moveData.englishName
        val isSpecialMove = moveData.powerStr!!.contains("*")
        if(isSpecialMove)
        {
            finalMoveName+="*"
        }
        var meff1 = moveData.effect1?.replace("{", "")?.replace("}", "")?.trim() ?: ""
        var meff2 = moveData.effect2?.replace("{", "")?.replace("}", "")?.trim() ?: ""
        val effs = mutableListOf(meff1, meff2) // Initialization
        if (ownPokemon!!.isDynaActivated)
        {
            //dynamex always erases old effects
            effs.clear()
            effs.add(getDynamaxEffect(moveData.englishName))
        }
        if (ownPokemon.isGigaDynaActivated || ownPokemon.name.contains("Gigantamax"))
        {
            effs.clear()
            val geff = getDynamaxEffect(moveData.englishName)
            val geff2 = getGigaDynamaxEffect(moveData.englishName)
            if(!geff2.isEmpty())
            {
                //gmax effects
                for(e in geff2)
                {
                    effs.add(e)
                }
            }else {
                if (geff != "") {
                    effs.add(geff)
                }
            }

        }

        for (effi in effs)
        {
            if(effi == "") {
                continue
            }
            var eff = effi
            // Kings stone
            if(ownPokemon.baseItem == "King" && !usedKing && eff.contains("B Dis"))
            {
                val counter = (eff.split(" ").last()).toIntOrNull()
                if (counter != null && counter > 1)
                {
                    eff = eff.replace(counter.toString(), (counter - 1).toString())
                    usedKing = true
                }
            }
            // Zoom Lense
            if(ownPokemon.baseItem == "Zoom" && !usedZoom && eff.contains("W Adv"))
            {
                val counter = (eff.split(" ").last()).toIntOrNull()
                if (counter != null && counter > 1)
                {
                    eff = eff.replace(counter.toString(), (counter - 1).toString())
                    usedZoom = true
                }
            }
            //Wide lense
            if(ownPokemon.baseItem == "Wide" && !eff.contains("KO") && ownPokemon.isBaseItemActivated)
            {
                val counter = (eff.split(" ").last()).toIntOrNull()
                if (counter != null && counter > 1)
                {
                    eff = eff.replace(counter.toString(), (counter - 1).toString())
                }
            }

            // Add effects
            if(enemyWeather == "Mist" && eff.contains("Dis"))
            {
                //ignore diss advantage if enemy has mist
            }
            else
            {
                allEffects.add(Pair(eff, finalMoveName))
            }
        }

        //additional effects:
        if(ownWeather == "Renewal")
        {
            allEffects.add(Pair("W Life", ""))
        }
        if(enemyWeather != "Mist" && ownPokemon.baseItem == "Evio" && ownPokemon.isBaseItemActivated && !pokedexRepository.isFullyEvolved(ownPokemon.id))
        {
            //evio adds a dis to your attacks:
            allEffects.add(Pair("B Dis 1", ""))
        }
        if(enemyWeather != "Mist" && ownPokemon.baseItem == "King" && !usedKing){
            allEffects.add(Pair("B Dis 5", ""))
        }
        if(ownPokemon.baseItem == "Zoom" && !usedZoom){
            allEffects.add(Pair("W Adv 5", ""))
        }
        if(ownPokemon.baseItem == "Quic" && ownPokemon.isBaseItemActivated){
            allEffects.add(Pair("W Priority", ""))
        }
        if(ownPokemon.baseItem == "Razo"){
            allEffects.add(Pair("W Extra 6", "Razo"))
        }
        if(ownPokemon.isTeraActivated && ownPokemon.teraType == "Stellar")
        {
            allEffects.add(Pair("W Ignore", ""))
        }
        return allEffects
    }

    fun hasProtection(pokemon: PokemonInfo,
                           enemy: PokemonInfo,
                           weather: String?,
                           enemyWeather: String?,
                           pokedexRepo: PokedexRepository): Boolean {
        if(pokemon.hasTypelessMove())
        {
            return false
        }
        var moves = mutableListOf<String>()
        moves.add(pokemon.move1)
        if(pokemon.move2!="")
        {
            moves.add(pokemon.move2)
        }
        if(pokemon.move3!=null)
        {
            moves.add(pokemon.move3!!)
        }
        for(movename in moves)
        {
            val result = calculateMovePower(
                movename,
                pokemon,
                enemy,
                weather,
                enemyWeather) ?: continue

            val effects = getAllEffects(result, pokemon, enemy, weather, enemyWeather, pokedexRepo)
            for(effect in effects)
            {
                if(effect.first.contains("W Prot"))
                {
                    return true
                }
            }
        }

      return false
    }

    fun getPokemonEffectiveness(pokemon: PokemonInfo, enemy: PokemonInfo): Int {
        val move1Data = fetchMoveData(pokemon.move1)
        val move2Data = fetchMoveData(pokemon.move2)
        val move3Data = pokemon.move3?.let { fetchMoveData(it) }

        var hasSuper = false
        var hasNeutral = false

        val pokeIsStellar = pokemon.isTeraActivated && (pokemon.teraType == "Stellar")
        fun check(data: MoveData?) {
            if (data == null) return
            val ignore_effi = data.ignores || pokeIsStellar
            val total = calculateMoveEffectiveness(data.type, ignore_effi, enemy.getType1(), enemy.getType2())
            if (total > 0) hasSuper = true
            if (total >= 0) hasNeutral = true
        }

        if(pokemon.hasTypelessMove()){
            return 0
        }

        check(move1Data)
        check(move2Data)
        check(move3Data)

        return if (hasSuper) 1 else if (!hasNeutral) -1 else 0
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
