package com.example.pmtu

import android.app.Activity
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.util.Locale

class ResultActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var imageView: ImageView
    private lateinit var textView: TextView
    private lateinit var diceContainer: LinearLayout
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingTTS: String? = null
    
    private var ownPokemon: PokemonInfo? = null

    companion object {
        private var enemyPokemon: PokemonInfo? = null
    }

    data class PokemonInfo(
        val name: String,
        val base_level: Int,
        val type1: String,
        val type2: String,
        val pokedex: String,
        val move1: String,
        val move2: String,
        var additionalLevel: Int = 0
    )

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

        val rootLayout = FrameLayout(this)
        rootLayout.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        // Center Container for Image and Text
        val centerContainer = LinearLayout(this)
        centerContainer.orientation = LinearLayout.VERTICAL
        centerContainer.gravity = Gravity.CENTER
        val centerParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        centerParams.gravity = Gravity.CENTER
        centerContainer.layoutParams = centerParams
        centerContainer.setPadding(32, 32, 32, 32)

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

        // Scanned Text
        val scannedText = intent.getStringExtra("SCANNED_TEXT") ?: "No data"
        
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
                val textToSpeak = it.name + ". " + it.pokedex
                speakOut(textToSpeak)
            }
        }
        centerContainer.addView(pokedexButton)

        // Text View for scanned result
        textView = TextView(this)
        textView.text = "Scanned Result:\n$scannedText"
        textView.textSize = 20f
        textView.gravity = Gravity.CENTER
        centerContainer.addView(textView)

        rootLayout.addView(centerContainer)

        // Button Container for New Scan and Scan Enemy
        val buttonContainer = LinearLayout(this)
        buttonContainer.orientation = LinearLayout.HORIZONTAL
        val containerParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        containerParams.gravity = Gravity.BOTTOM
        containerParams.setMargins(64, 0, 64, 64)
        buttonContainer.layoutParams = containerParams

        // Adjust button position to be above navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(buttonContainer) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<FrameLayout.LayoutParams> {
                bottomMargin = insets.bottom + 64
            }
            windowInsets
        }

        val buttonLayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)

        // New Scan Button
        val newScanButton = Button(this)
        newScanButton.text = "New Scan"
        newScanButton.layoutParams = buttonLayoutParams
        newScanButton.setOnClickListener {
            val intent = Intent(this, MainActivity::class.java)
            intent.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
            finish()
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

        rootLayout.addView(buttonContainer)

        setContentView(rootLayout)

        // Initialize TTS
        tts = TextToSpeech(this, this)

        if (scannedText.firstOrNull()?.isDigit()==true) {
            val number = scannedText
            var url_number = number
            if (number == "890-gi"){
                url_number = "890-e"
            }
            val poke_url = "https://www.serebii.net/pokemon/art/" + url_number + ".png"
            val poke_sprite_url = "https://www.serebii.net/pokedex-sv/icon/" + url_number + ".png"

            downloadImage(poke_url)
            val search_string = "#"+scannedText
            get_pokedex(search_string)

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
    
    private fun findPokemonByNumber(number: String): PokemonInfo? {
        try {
            val reader = assets.open("pokedex.csv").bufferedReader(Charsets.ISO_8859_1)
            var line: String?
            
            while (reader.readLine().also { line = it } != null) {
                val lin2 = line?.drop(1)?.dropLast(1)
                val rawColumns = lin2?.split("\",\"") ?: continue
                // Map columns to remove surrounding quotes and trim whitespace
                val columns = rawColumns.map { it.trim().removeSurrounding("\"") }
                
                if (columns.isNotEmpty() && columns[0] == number) {
                    val info = PokemonInfo(
                        name = columns[1].replace("{G-Max}", "Gmax").replace("{MEGA}", "Mega"),
                        base_level = columns[2].toInt(),
                        type1 = columns[3],
                        type2 = columns[4],
                        move1 = columns[5].split("/").last(),
                        move2 = columns[6].split("/").last(),
                        pokedex = columns.last(),
                        additionalLevel = 0
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

    /**
     * Calculates the effectiveness of an attacking type against a defending type.
     * Returns an integer: 0 (immune), 1 (not very effective), 2 (normal), 4 (super effective).
     */
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

    private fun get_pokedex(number: String) {
        val info = findPokemonByNumber(number)
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
            val info = findPokemonByNumber(search_string)
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
                        
                        // Add base_level and additionalLevel to power
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
                        
                        // Handle Type Image
                        val cleanType = type.replace("{", "").replace("}", "").trim()
                        val typeImagePath = "$cleanType.png"
                        try {
                            val inputStream = assets.open(typeImagePath)
                            val bitmap = BitmapFactory.decodeStream(inputStream)
                            val drawable: Drawable = BitmapDrawable(resources, bitmap)
                            
                            // Scale drawable to fit text height
                            val size = (textView.textSize * 1.5).toInt()
                            drawable.setBounds(0, 0, (size * bitmap.width / bitmap.height), size)
                            
                            builder.append("  ") // Placeholder for image
                            builder.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            builder.append(" ")
                        } catch (e: Exception) {
                            Log.e("Moves", "Error loading type image: $typeImagePath", e)
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

    private fun downloadImage(url: String) {
        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val inputStream = URL(url).openStream()
                    BitmapFactory.decodeStream(inputStream)
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }
            if (bitmap != null) {
                imageView.setImageBitmap(bitmap)
            }
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
