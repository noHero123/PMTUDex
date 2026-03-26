package com.example.pmtu

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URL
import java.util.Locale
import kotlin.random.Random

class ResultActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var imageView: ImageView
    private lateinit var textView: TextView
    private lateinit var diceContainer: LinearLayout
    private lateinit var teamContainer: LinearLayout
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingTTS: String? = null
    
    private var ownPokemon: PokemonInfo? = null
    private var isSelectingSlot = false

    companion object {
        private var enemyPokemon: PokemonInfo? = null
        private var teamPokemon = arrayOfNulls<PokemonInfo>(6)
        private const val TEAM_FILE_NAME = "team_data.json"
    }

    data class PokemonInfo(
        val name: String,
        val base_level: Int,
        val type1: String,
        val type2: String,
        val pokedexEntries: List<String>,
        val move1: String,
        val move2: String,
        val spriteUrl: String,
        val artUrl: String,
        var spriteBase64: String? = null,
        var additionalLevel: Int = 0,
        var nextPokedexIndex: Int = 0
    ) {
        @Transient
        var spriteBitmap: Bitmap? = null
    }

    private val scanEnemyLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scannedText = result.data?.getStringExtra("SCANNED_TEXT")
            if (scannedText != null) {
                readEnemyData(scannedText)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadTeamData()

        val rootLayout = FrameLayout(this)
        rootLayout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val mainContainer = LinearLayout(this)
        mainContainer.orientation = LinearLayout.VERTICAL
        mainContainer.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Team Container at the top
        teamContainer = LinearLayout(this)
        teamContainer.orientation = LinearLayout.HORIZONTAL
        teamContainer.gravity = Gravity.CENTER
        val teamParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        teamContainer.layoutParams = teamParams
        
        ViewCompat.setOnApplyWindowInsetsListener(teamContainer) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updateLayoutParams<LinearLayout.LayoutParams> {
                topMargin = insets.top + 8
            }
            windowInsets
        }
        
        mainContainer.addView(teamContainer)
        updateTeamView()

        // Center Container for Image and Text
        val centerContainer = LinearLayout(this)
        centerContainer.orientation = LinearLayout.VERTICAL
        centerContainer.gravity = Gravity.CENTER
        val centerParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1.0f
        )
        centerContainer.layoutParams = centerParams
        centerContainer.setPadding(32, 0, 32, 32)

        // Dice Container
        diceContainer = LinearLayout(this)
        diceContainer.orientation = LinearLayout.HORIZONTAL
        diceContainer.gravity = Gravity.CENTER
        val diceParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        diceParams.bottomMargin = 16
        diceContainer.layoutParams = diceParams
        centerContainer.addView(diceContainer)

        // Add to Team Button (+)
        val addToTeamButton = Button(this)
        addToTeamButton.text = "+"
        val addTeamParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addTeamParams.bottomMargin = 8
        addToTeamButton.layoutParams = addTeamParams
        addToTeamButton.setOnClickListener {
            if (ownPokemon != null) {
                isSelectingSlot = true
                Toast.makeText(this, "Select a slot to save ${ownPokemon?.name}", Toast.LENGTH_SHORT).show()
                updateTeamView()
            }
        }
        centerContainer.addView(addToTeamButton)

        // Scanned Text
        val scannedText = intent.getStringExtra("SCANNED_TEXT")
        
        // Image View
        imageView = ImageView(this)
        imageView.setImageResource(android.R.drawable.ic_menu_camera)
        val imageParams = LinearLayout.LayoutParams(600, 600)
        imageParams.bottomMargin = 32
        imageView.layoutParams = imageParams
        centerContainer.addView(imageView)

        // Pokedex Button
        val pokedexButton = Button(this)
        pokedexButton.text = "Pokédex"
        val pokeButtonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        pokeButtonParams.bottomMargin = 32
        pokedexButton.layoutParams = pokeButtonParams
        pokedexButton.setOnClickListener {
            ownPokemon?.let {
                if (it.pokedexEntries.isNotEmpty()) {
                    val entry = it.pokedexEntries[it.nextPokedexIndex]
                    val textToSpeak = it.name + ". " + entry
                    speakOut(textToSpeak)
                    it.nextPokedexIndex = (it.nextPokedexIndex + 1) % it.pokedexEntries.size
                }
            }
        }
        centerContainer.addView(pokedexButton)

        // Text View for scanned result
        textView = TextView(this)
        textView.text = ""
        textView.textSize = 20f
        textView.gravity = Gravity.CENTER
        centerContainer.addView(textView)

        mainContainer.addView(centerContainer)

        // Button Container for New Scan and Scan Enemy
        val buttonContainer = LinearLayout(this)
        buttonContainer.orientation = LinearLayout.HORIZONTAL
        val containerParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        containerParams.setMargins(64, 0, 64, 128)
        buttonContainer.layoutParams = containerParams

        val buttonLayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)

        // New Scan Button
        val newScanButton = Button(this)
        newScanButton.text = "New Scan"
        newScanButton.layoutParams = buttonLayoutParams
        newScanButton.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
        }
        buttonContainer.addView(newScanButton)

        // Spacer
        val spacer = android.view.View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(32, 1)
        buttonContainer.addView(spacer)

        // Scan Enemy Button
        val scanEnemyButton = Button(this)
        scanEnemyButton.text = "Scan Enemy"
        scanEnemyButton.layoutParams = buttonLayoutParams
        scanEnemyButton.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            scanEnemyLauncher.launch(intent)
        }
        buttonContainer.addView(scanEnemyButton)

        mainContainer.addView(buttonContainer)
        rootLayout.addView(mainContainer)

        setContentView(rootLayout)

        // Initialize TTS
        tts = TextToSpeech(this, this)

        if (scannedText != null && scannedText.firstOrNull()?.isDigit()==true) {
            val number = scannedText
            var url_number = number
            if (number == "890-gi"){
                url_number = "890-e"
            }
            val poke_url = "https://www.serebii.net/pokemon/art/" + url_number + ".png"
            val poke_sprite_url = "https://www.serebii.net/pokedex-sv/icon/" + url_number + ".png"

            downloadImage(poke_url, poke_sprite_url)
            val search_string = "#"+scannedText
            get_pokedex(search_string, poke_sprite_url, poke_url)
        } else if (teamPokemon.any { it != null }) {
            // Select first available team member if no scan
            teamPokemon.firstOrNull { it != null }?.let { 
                selectPokemon(it)
            }
        }
    }

    private fun saveTeamData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // Ensure all base64 strings are updated before saving
                teamPokemon.forEach { pokemon ->
                    if (pokemon?.spriteBitmap != null && pokemon.spriteBase64 == null) {
                        pokemon.spriteBase64 = bitmapToBase64(pokemon.spriteBitmap!!)
                    }
                }
                val json = Gson().toJson(teamPokemon)
                File(filesDir, TEAM_FILE_NAME).writeText(json)
            } catch (e: Exception) {
                Log.e("Storage", "Error saving team data", e)
            }
        }
    }

    private fun loadTeamData() {
        try {
            val file = File(filesDir, TEAM_FILE_NAME)
            if (file.exists()) {
                val json = file.readText()
                val type = object : TypeToken<Array<PokemonInfo?>>() {}.type
                val loadedTeam: Array<PokemonInfo?> = Gson().fromJson(json, type)
                teamPokemon = loadedTeam
                
                // Restore bitmaps
                teamPokemon.forEach { pokemon ->
                    if (pokemon?.spriteBase64 != null) {
                        pokemon.spriteBitmap = base64ToBitmap(pokemon.spriteBase64!!)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Storage", "Error loading team data", e)
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

    private fun addToTeam(slot: Int) {
        ownPokemon?.let { current ->
            teamPokemon[slot] = current
            isSelectingSlot = false
            updateTeamView()
            saveTeamData()
            Toast.makeText(this, "${current.name} saved to slot ${slot + 1}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectPokemon(pokemon: PokemonInfo) {
        ownPokemon = pokemon
        lifecycleScope.launch {
            val artBitmap = withContext(Dispatchers.IO) {
                try {
                    val inputStream = URL(pokemon.artUrl).openStream()
                    BitmapFactory.decodeStream(inputStream)
                } catch (e: Exception) { null }
            }
            if (artBitmap != null) {
                imageView.setImageBitmap(artBitmap)
            }
        }
        showDice(false)
        refreshMoves()
    }

    private fun updateTeamView() {
        teamContainer.removeAllViews()
        for (i in 0 until 6) {
            val slotIv = ImageView(this)
            val params = LinearLayout.LayoutParams(120, 120)
            params.setMargins(8, 0, 8, 0)
            slotIv.layoutParams = params
            slotIv.scaleType = ImageView.ScaleType.FIT_CENTER
            
            val pokemon = teamPokemon[i]
            if (isSelectingSlot) {
                slotIv.setBackgroundColor(if (pokemon == null) Color.GREEN else Color.YELLOW)
                slotIv.setOnClickListener {
                    addToTeam(i)
                }
                if (pokemon?.spriteBitmap != null) slotIv.setImageBitmap(pokemon.spriteBitmap)
            } else {
                if (pokemon?.spriteBitmap != null) {
                    slotIv.setImageBitmap(pokemon.spriteBitmap)
                    slotIv.setOnClickListener {
                        selectPokemon(pokemon)
                    }
                } else {
                    slotIv.setBackgroundColor(Color.LTGRAY)
                }
            }
            teamContainer.addView(slotIv)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.GERMAN)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TTS", "The Language specified is not supported!")
            } else {
                isTtsReady = true
                pendingTTS?.let {
                    speakOut(it)
                    pendingTTS = null
                }
            }
        } else {
            Log.e("TTS", "Initialization Failed!")
        }
    }

    private fun speakOut(text: String) {
        if (isTtsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "")
        } else {
            pendingTTS = text
        }
    }
    
    private fun findPokemonByNumber(number: String, spriteUrl: String, artUrl: String): PokemonInfo? {
        try {
            val reader = assets.open("pokedex.csv").bufferedReader(Charsets.ISO_8859_1)
            var line: String?
            
            while (reader.readLine().also { line = it } != null) {
                val lin2 = line?.drop(1)?.dropLast(1)
                val rawColumns = lin2?.split("\",\"") ?: continue
                val columns = rawColumns.map { it.trim().removeSurrounding("\"") }
                
                if (columns.isNotEmpty() && columns[0] == number) {
                    val entries = mutableListOf<String>()
                    if (columns.size > 7) {
                        for (i in 7 until columns.size) {
                            if (columns[i].startsWith("de")) {
                                entries.add(columns[i].drop(2))
                            }
                        }
                    }
                    entries.shuffle()
                    
                    val info = PokemonInfo(
                        name = columns[1].replace("{G-Max}", "Gmax").replace("{MEGA}", "Mega"),
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
            e.printStackTrace()
        }
        return null
    }

    private fun getTypeEffectiveness(attacker: String, defender: String): Int {
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

    private fun get_pokedex(number: String, spriteUrl: String, artUrl: String) {
        val info = findPokemonByNumber(number, spriteUrl, artUrl)
        ownPokemon = info
        if (info != null) {
            showDice(false)
            refreshMoves()
        } else {
            textView.text = "Error reading Pokédex"
        }
    }

    private fun refreshMoves() {
        ownPokemon?.let {
            val m1 = search_moves(it.move1)
            val m2 = search_moves(it.move2)
            textView.text = TextUtils.concat(m1, "\n", m2)
        }
    }

    private fun showDice(all: Boolean) {
        diceContainer.removeAllViews()
        val level = ownPokemon?.additionalLevel ?: 0
        
        if (all) {
            for (i in 0..6) {
                val diceIv = ImageView(this)
                try {
                    val inputStream = assets.open("blued6_$i.png")
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    diceIv.setImageBitmap(bitmap)
                    val params = LinearLayout.LayoutParams(100, 100)
                    params.setMargins(8, 0, 8, 0)
                    diceIv.layoutParams = params
                    diceIv.setOnClickListener {
                        ownPokemon?.additionalLevel = i
                        showDice(false)
                        refreshMoves()
                        saveTeamData()
                    }
                    diceContainer.addView(diceIv)
                } catch (e: Exception) {
                    Log.e("Dice", "Error loading dice image blued6_$i.png", e)
                }
            }
        } else {
            val diceIv = ImageView(this)
            try {
                val inputStream = assets.open("blued6_$level.png")
                val bitmap = BitmapFactory.decodeStream(inputStream)
                diceIv.setImageBitmap(bitmap)
                val params = LinearLayout.LayoutParams(150, 150)
                diceIv.layoutParams = params
                diceIv.setOnClickListener {
                    showDice(true)
                }
                diceContainer.addView(diceIv)
            } catch (e: Exception) {
                Log.e("Dice", "Error loading dice image blued6_$level.png", e)
            }
        }
    }

    private fun readEnemyData(scannedText: String) {
        if (scannedText.firstOrNull()?.isDigit() == true) {
            val search_string = "#$scannedText"
            val info = findPokemonByNumber(search_string, "", "")
            if (info != null) {
                enemyPokemon = info
                Toast.makeText(this, "Enemy ${info.name} scanned", Toast.LENGTH_SHORT).show()
                Log.d("ScanEnemy", "Scanned enemy: ${info.name}, types: ${info.type1}/${info.type2}")
                refreshMoves()
            }
        }
    }

    private fun search_moves(moveName: String): CharSequence {
        try {
            val moveFiles = assets.list("")?.filter { it.startsWith("PMTU Moves") } ?: return ""
            for (fileName in moveFiles) {
                val reader = assets.open(fileName).bufferedReader(Charsets.UTF_8)
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val lin2 = line?.trim()?.removeSurrounding("\"")
                    val columns = lin2?.split(",") ?: continue

                    val filename = columns[10]
                    
                    if ( filename.equals(moveName, ignoreCase = true)) {
                        var wurfel = columns[1]
                        if (wurfel == ""){
                            wurfel = "d6"
                        }
                        var power = columns[3]
                        val is_stab = moveName.endsWith("(S)")
                        if (is_stab)
                        {
                            power = columns[4]
                        }
                        var powerval = power.toIntOrNull() ?: 0
                        
                        ownPokemon?.let {
                            powerval += it.base_level + it.additionalLevel
                        }

                        val type = columns[0]
                        var effectivnes = 0
                        if (enemyPokemon != null){
                            val effectiveness1 = getTypeEffectiveness(type, enemyPokemon!!.type1)
                            val effectiveness2= getTypeEffectiveness(type, enemyPokemon!!.type2)
                            effectivnes = effectiveness1 + effectiveness2

                            if (effectivnes == -4){
                                effectivnes = -3
                            }
                            if (effectivnes == 4){
                                effectivnes = 3
                            }
                            if (effectivnes < -4){
                                powerval = 0
                            }else
                            {
                                powerval = powerval + effectivnes
                            }
                        }
                        
                        val builder = SpannableStringBuilder()
                        val cleanType = type.replace("{", "").replace("}", "").trim()
                        val typeImagePath = "$cleanType.png"
                        try {
                            val inputStream = assets.open(typeImagePath)
                            val bitmap = BitmapFactory.decodeStream(inputStream)
                            val drawable: Drawable = BitmapDrawable(resources, bitmap)
                            val size = (textView.textSize * 1.5).toInt()
                            drawable.setBounds(0, 0, (size * bitmap.width / bitmap.height), size)
                            builder.append("  ")
                            builder.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            builder.append(" ")
                        } catch (e: Exception) {
                            builder.append(type).append(" ")
                        }
                        
                        val start = builder.length
                        builder.append(powerval.toString())
                        val end = builder.length
                        
                        if (enemyPokemon != null) {
                            if (effectivnes < 0) {
                                builder.setSpan(ForegroundColorSpan(Color.RED), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            } else if (effectivnes > 0) {
                                builder.setSpan(ForegroundColorSpan(Color.GREEN), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                        }
                        
                        builder.append(" ").append(columns[16]).append(" ").append(wurfel)
                        reader.close()
                        return builder
                    }
                }
                reader.close()
            }
        } catch (e: Exception) {
            Log.e("Moves", "Error searching moves", e)
        }
        return ""
    }

    private fun downloadImage(artUrl: String, spriteUrl: String) {
        lifecycleScope.launch {
            val artBitmap = withContext(Dispatchers.IO) {
                try {
                    val inputStream = URL(artUrl).openStream()
                    BitmapFactory.decodeStream(inputStream)
                } catch (e: Exception) {
                    null
                }
            }
            if (artBitmap != null) {
                imageView.setImageBitmap(artBitmap)
            }

            val spriteBitmap = withContext(Dispatchers.IO) {
                try {
                    val inputStream = URL(spriteUrl).openStream()
                    BitmapFactory.decodeStream(inputStream)
                } catch (e: Exception) {
                    null
                }
            }
            ownPokemon?.let {
                it.spriteBitmap = spriteBitmap
                if (spriteBitmap != null) {
                    it.spriteBase64 = bitmapToBase64(spriteBitmap)
                }
            }
            updateTeamView()
        }
    }

    override fun onDestroy() {
        if (tts != null) {
            tts?.stop()
            tts?.shutdown()
        }
        super.onDestroy()
    }
}
