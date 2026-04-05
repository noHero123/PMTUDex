package com.example.pmtu

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
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
import android.view.View
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.Locale
import kotlin.random.Random

class ResultActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    private lateinit var imageView: ImageView
    private lateinit var textView: TextView
    private lateinit var diceContainer: LinearLayout
    private lateinit var teamContainer: LinearLayout
    private lateinit var enemySpriteView: ImageView
    private lateinit var enemyTypesContainer: LinearLayout
    private lateinit var clearEnemyButton: ImageView
    private lateinit var pokedexButton: Button
    private lateinit var addRemoveButton: Button
    private lateinit var evolutionsContainer: LinearLayout
    private lateinit var preEvolutionsContainer: LinearLayout
    private lateinit var movesLayout: LinearLayout
    private lateinit var settingsButton: ImageView
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingTTS: String? = null
    private var currentLanguage: String? = null
    
    private var ownPokemon: PokemonInfo? = null
    private var isSelectingSlot = false
    private var currentTeamIndex: Int? = null

    private lateinit var pokedexRepository: PokedexRepository
    private lateinit var moveRepository: MoveRepository

    private val statusListener = { status: HttpSyncService.Status, _: String? ->
        if (status == HttpSyncService.Status.CONNECTED) {
            syncViaHttp()
        }
    }

    companion object {
        private var enemyPokemon: PokemonInfo? = null
        private var teamPokemon = arrayOfNulls<PokemonInfo>(6)
        private const val TEAM_FILE_NAME = "team_data.json"
        private const val IMAGE_DIR_NAME = "pokemon_images"
    }

    private val pokemonScannerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scannedText = result.data?.getStringExtra("SCANNED_TEXT")
            if (scannedText != null) {
                if (scannedText.startsWith("pmtu_connect", ignoreCase = true)) {
                    val ip = scannedText.substring("pmtu_connect".length)
                    HttpSyncService.startAsSlave(ip)
                    Toast.makeText(this, "Connecting to Master at $ip...", Toast.LENGTH_SHORT).show()
                } else if (scannedText.startsWith("t", ignoreCase = true)) {
                    handleTMScan(scannedText)
                } else if (scannedText.firstOrNull()?.isDigit() == true) {
                    val number = scannedText
                    val spriteUrl = "https://www.serebii.net/pokedex-sv/icon/$number.png"
                    val artUrl = "https://www.serebii.net/pokemon/art/$number.png"
                    get_pokedex(number, spriteUrl, artUrl)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pokedexRepository = PokedexRepository(this)
        moveRepository = MoveRepository(this)

        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        currentLanguage = prefs.getString("language", "en")

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        HttpSyncService.onDataReceived = { json ->
            lifecycleScope.launch {
                try {
                    val data = Gson().fromJson(json, HttpSyncService.SyncData::class.java)
                    if (data.type == "SYNC") {
                        val receivedOwn = data.ownPokemonJson?.let { Gson().fromJson(it, PokemonInfo::class.java) }
                        val receivedEnemy = data.enemyPokemonJson?.let { Gson().fromJson(it, PokemonInfo::class.java) }
                        
                        if (HttpSyncService.isMaster) {
                            enemyPokemon = receivedOwn
                        } else {
                            enemyPokemon = receivedOwn
                            ownPokemon = receivedEnemy
                        }
                        
                        showDice(false)
                        refreshMoves()
                        updateTeamView()
                        updateAddRemoveButton()
                        updatePokedexButtonText()
                        updateEvolutionViews()
                        
                        ownPokemon?.let { p ->
                            val artUrl = if (p.artUrl.isNotEmpty()) p.artUrl else "https://www.serebii.net/pokemon/art/${p.id}.png"
                            downloadImage(artUrl, p.spriteUrl)
                        }
                        enemyPokemon?.let { p ->
                            updateEnemySprite(p.spriteUrl)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SYNC", "Error parsing received data", e)
                }
            }
        }
        HttpSyncService.addStatusListener(statusListener)

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

        // Top bar with Settings
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(16, 16, 64, 16) // Increased right padding for curved edges
        }
        
        settingsButton = ImageView(this).apply {
            // Using a system icon for now
            setImageResource(android.R.drawable.ic_menu_preferences)
            layoutParams = LinearLayout.LayoutParams(80, 80)
            setOnClickListener {
                startActivity(Intent(this@ResultActivity, SettingsActivity::class.java))
            }
        }
        topBar.addView(settingsButton)
        mainContainer.addView(topBar)

        // Team Layout (Horizontal)
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

        // Add to Team Button (+) or Remove (-) below team
        addRemoveButton = Button(this)
        addRemoveButton.text = "+"
        val addTeamParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        addRemoveButton.layoutParams = addTeamParams
        
        val buttonWrapper = LinearLayout(this)
        buttonWrapper.gravity = Gravity.CENTER_HORIZONTAL
        buttonWrapper.addView(addRemoveButton)
        mainContainer.addView(buttonWrapper)
        
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

        // Main content horizontal layout (Pre-evolutions | Big Image | Evolutions)
        val imageEvoLayout = LinearLayout(this)
        imageEvoLayout.orientation = LinearLayout.HORIZONTAL
        imageEvoLayout.gravity = Gravity.CENTER
        imageEvoLayout.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        // Left Container (Pre-evolutions)
        preEvolutionsContainer = LinearLayout(this)
        preEvolutionsContainer.orientation = LinearLayout.VERTICAL
        preEvolutionsContainer.gravity = Gravity.CENTER
        val evoParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        preEvolutionsContainer.layoutParams = evoParams
        imageEvoLayout.addView(preEvolutionsContainer)

        // Image View (Center)
        imageView = ImageView(this)
        imageView.setImageResource(android.R.drawable.ic_menu_camera)
        val imageParams = LinearLayout.LayoutParams(600, 600)
        imageView.layoutParams = imageParams
        imageEvoLayout.addView(imageView)

        // Right Container (Evolutions)
        evolutionsContainer = LinearLayout(this)
        evolutionsContainer.orientation = LinearLayout.VERTICAL
        evolutionsContainer.gravity = Gravity.CENTER
        evolutionsContainer.layoutParams = evoParams
        imageEvoLayout.addView(evolutionsContainer)

        centerContainer.addView(imageEvoLayout)

        // Pokedex Button
        pokedexButton = Button(this)
        pokedexButton.text = "Pokédex"
        val pokeButtonParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        pokeButtonParams.topMargin = 32
        pokeButtonParams.bottomMargin = 32
        pokedexButton.layoutParams = pokeButtonParams
        pokedexButton.setOnClickListener {
            ownPokemon?.let {
                if (it.pokedexEntries.isNotEmpty()) {
                    val entry = it.pokedexEntries[it.nextPokedexIndex]
                    val textToSpeak = it.name + ". " + entry
                    speakOut(textToSpeak)
                    it.nextPokedexIndex = (it.nextPokedexIndex + 1) % it.pokedexEntries.size
                    updatePokedexButtonText()
                }
            }
        }
        centerContainer.addView(pokedexButton)

        // Moves Layout
        movesLayout = LinearLayout(this)
        movesLayout.orientation = LinearLayout.VERTICAL
        movesLayout.gravity = Gravity.CENTER
        val movesParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        movesLayout.layoutParams = movesParams
        centerContainer.addView(movesLayout)

        // Main Text View (fallback or general info if needed)
        textView = TextView(this)
        textView.text = ""
        textView.textSize = 20f
        textView.gravity = Gravity.CENTER
        movesLayout.addView(textView)

        mainContainer.addView(centerContainer)

        // Button Container for New Scan and Scan Enemy
        val buttonContainer = LinearLayout(this)
        buttonContainer.orientation = LinearLayout.HORIZONTAL
        buttonContainer.gravity = Gravity.BOTTOM
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
            pokemonScannerLauncher.launch(intent)
        }
        buttonContainer.addView(newScanButton)

        // Spacer
        val spacer = android.view.View(this)
        spacer.layoutParams = LinearLayout.LayoutParams(32, 1)
        buttonContainer.addView(spacer)

        // Enemy Layout (Vertical: Sprite/X Container + Button)
        val enemyLayout = LinearLayout(this)
        enemyLayout.orientation = LinearLayout.VERTICAL
        enemyLayout.gravity = Gravity.CENTER
        enemyLayout.layoutParams = buttonLayoutParams

        // Sprite, Types and X Container
        val enemyInfoContainer = LinearLayout(this)
        enemyInfoContainer.orientation = LinearLayout.HORIZONTAL
        enemyInfoContainer.gravity = Gravity.CENTER
        enemyLayout.addView(enemyInfoContainer)

        // Enemy Types Container (Left of sprite)
        enemyTypesContainer = LinearLayout(this)
        enemyTypesContainer.orientation = LinearLayout.VERTICAL
        enemyTypesContainer.gravity = Gravity.CENTER
        val etParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        etParams.rightMargin = 8
        enemyTypesContainer.layoutParams = etParams
        enemyInfoContainer.addView(enemyTypesContainer)

        enemySpriteView = ImageView(this)
        val enemySpriteParams = LinearLayout.LayoutParams(120, 120)
        enemySpriteView.layoutParams = enemySpriteParams
        enemyInfoContainer.addView(enemySpriteView)

        // Clear Enemy Button (Trash icon)
        clearEnemyButton = ImageView(this)
        try {
            val inputStream = assets.open("trash.png")
            val bitmap = BitmapFactory.decodeStream(inputStream)
            clearEnemyButton.setImageBitmap(bitmap)
        } catch (e: Exception) {
            Log.e("UI", "Error loading trash icon", e)
        }
        val trashParams = LinearLayout.LayoutParams(100, 100)
        trashParams.leftMargin = 8
        clearEnemyButton.layoutParams = trashParams
        clearEnemyButton.visibility = View.GONE
        clearEnemyButton.setOnClickListener {
            clearEnemy()
        }
        enemyInfoContainer.addView(clearEnemyButton)

        // Switch to Enemy Button
        val switchToEnemyButton = Button(this)
        switchToEnemyButton.text = "Switch to Enemy"
        switchToEnemyButton.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        switchToEnemyButton.setOnClickListener {
            val oldOwn = ownPokemon

            if (enemyPokemon != null) {
                val newPoki = enemyPokemon
                selectPokemon(newPoki!!, null)
            } else {
                Toast.makeText(this, "No enemy to switch with", Toast.LENGTH_SHORT).show()
            }

            enemyPokemon = oldOwn
            if (enemyPokemon != null) {
                updateEnemySprite(enemyPokemon!!.spriteUrl)
            } else {
                clearEnemy()
            }
            refreshMoves()
            updateTeamView()
            syncViaHttp()
        }
        enemyLayout.addView(switchToEnemyButton)

        buttonContainer.addView(enemyLayout)

        mainContainer.addView(buttonContainer)
        rootLayout.addView(mainContainer)

        setContentView(rootLayout)

        // Initialize TTS
        tts = TextToSpeech(this, this)

        // Scanned Text
        val scannedText = intent.getStringExtra("SCANNED_TEXT")
        if (scannedText != null) {
            if (scannedText.startsWith("pmtu_connect", ignoreCase = true)) {
                val ip = scannedText.substring("pmtu_connect".length)
                HttpSyncService.startAsSlave(ip)
                Toast.makeText(this, "Connecting to Master at $ip...", Toast.LENGTH_SHORT).show()
            } else if (scannedText.startsWith("t", ignoreCase = true)) {
                handleTMScan(scannedText)
            } else if (scannedText.firstOrNull()?.isDigit() == true) {
                val number = scannedText
                var url_number = number
                val poke_url = "https://www.serebii.net/pokemon/art/" + url_number + ".png"
                val poke_sprite_url = "https://www.serebii.net/pokedex-sv/icon/" + url_number + ".png"

                downloadImage(poke_url, poke_sprite_url)
                get_pokedex(number, poke_sprite_url, poke_url)
            }
        } else if (teamPokemon.any { it != null }) {
            val firstIndex = teamPokemon.indexOfFirst { it != null }
            if (firstIndex != -1) {
                teamPokemon[firstIndex]?.let {
                    selectPokemon(it, firstIndex)
                }
            }
        }
        
        enemyPokemon?.let {
            updateEnemySprite(it.spriteUrl)
        }
        updatePokedexButtonText()
        updateAddRemoveButton()
    }

    override fun onResume() {
        super.onResume()
        checkLanguageUpdate()
    }

    private fun checkLanguageUpdate() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val lang = prefs.getString("language", "en") ?: "en"
        if (lang != currentLanguage) {
            currentLanguage = lang
            updateAllPokemonData()
            updateTtsLanguage(lang)
        }
    }

    private fun updateTtsLanguage(lang: String) {
        if (tts != null) {
            val locale = if (lang == "de") Locale.GERMAN else Locale.ENGLISH
            tts?.setLanguage(locale)
        }
    }

    private fun updateAllPokemonData() {
        ownPokemon?.let { updatePokemonFields(it) }
        enemyPokemon?.let { updatePokemonFields(it) }
        teamPokemon.forEach { it?.let { p -> updatePokemonFields(p) } }
        
        // Refresh UI
        refreshMoves()
        updatePokedexButtonText()
    }

    private fun updatePokemonFields(pokemon: PokemonInfo) {
        pokemon.name = pokedexRepository.getGermanName(pokemon.id)
        pokemon.pokedexEntries = pokedexRepository.getGermanText(pokemon.id)
    }

    private fun syncViaHttp() {
        if (HttpSyncService.connectionStatus == HttpSyncService.Status.CONNECTED) {
            HttpSyncService.sendData(HttpSyncService.SyncData(
                type = "SYNC",
                ownPokemonJson = Gson().toJson(ownPokemon),
                enemyPokemonJson = Gson().toJson(enemyPokemon)
            ))
        }
    }

    private fun handleTMScan(scannedText: String) {
        val own = ownPokemon
        if (own == null) {
            Toast.makeText(this, "Scan a Pokémon first!", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val data = scannedText.substring(1)
            val parts = data.split("_")
            if (parts.size != 2) return

            val gen = parts[0]
            val number = parts[1]

            lifecycleScope.launch(Dispatchers.IO) {
                val tmData = moveRepository.getTMData(gen, number)
                if (tmData != null) {
                    val pType1 = own.type1.replace("{", "").replace("}", "").trim()
                    val pType2 = own.type2.replace("{", "").replace("}", "").trim()

                    val isRealStab = tmData.isStabCsv && (tmData.type.equals(pType1, ignoreCase = true) ||
                                                      (pType2 != "None" && tmData.type.equals(pType2, ignoreCase = true)))

                    val moveName = if (isRealStab) "${tmData.name} (S)" else tmData.name

                    withContext(Dispatchers.Main) {
                        own.move3 = moveName
                        refreshMoves()
                        saveTeamData()
                        updateTeamView()
                        syncViaHttp()
                        Toast.makeText(this@ResultActivity, "TM Added: ${tmData.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("TM", "Error handling TM scan", e)
        }
    }

    private fun saveTeamData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
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
            currentTeamIndex = slot
            updateTeamView()
            saveTeamData()
            updateAddRemoveButton()
            Toast.makeText(this, "${current.name} saved to slot ${slot + 1}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeFromTeam() {
        currentTeamIndex?.let { index ->
            teamPokemon[index] = null
            currentTeamIndex = null
            saveTeamData()
            updateTeamView()
            updateAddRemoveButton()
            Toast.makeText(this, "Removed from team", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateAddRemoveButton() {
        val current = ownPokemon
        if (current == null) {
            addRemoveButton.visibility = View.GONE
            return
        }
        addRemoveButton.visibility = View.VISIBLE

        if (currentTeamIndex != null) {
            addRemoveButton.text = "-"
            addRemoveButton.setOnClickListener { removeFromTeam() }
        } else {
            addRemoveButton.text = "+"
            addRemoveButton.setOnClickListener {
                isSelectingSlot = true
                Toast.makeText(this, "Select a slot to save ${current.name}", Toast.LENGTH_SHORT).show()
                updateTeamView()
            }
        }
    }

    private fun selectPokemon(pokemon: PokemonInfo, index: Int? = null) {
        ownPokemon = pokemon
        currentTeamIndex = index
        val artUrl = if (pokemon.artUrl.isNotEmpty()) pokemon.artUrl else "https://www.serebii.net/pokemon/art/${pokemon.id}.png"

        lifecycleScope.launch {
            if (artUrl.isNotEmpty()) {
                val artBitmap = loadCachedBitmap(artUrl)
                if (artBitmap != null) {
                    imageView.setImageBitmap(artBitmap)
                } else {
                    val downloaded = withContext(Dispatchers.IO) {
                        try {
                            val inputStream = URL(artUrl).openStream()
                            val bitmap = BitmapFactory.decodeStream(inputStream)
                            if (bitmap != null) saveBitmapToCache(artUrl, bitmap)
                            bitmap
                        } catch (e: Exception) { null }
                    }
                    if (downloaded != null) {
                        imageView.setImageBitmap(downloaded)
                    }
                }
            }
        }
        showDice(false)
        refreshMoves()
        updatePokedexButtonText()
        updateAddRemoveButton()
        updateEvolutionViews()
        updateTeamView()
        syncViaHttp()
    }

    private fun updateEvolutionViews() {
        evolutionsContainer.removeAllViews()
        preEvolutionsContainer.removeAllViews()
        val currentId = ownPokemon?.id ?: return

        lifecycleScope.launch(Dispatchers.IO) {
            val (evos, preEvos) = pokedexRepository.getEvolutions(currentId)

            withContext(Dispatchers.Main) {
                evolutionsContainer.removeAllViews()
                preEvolutionsContainer.removeAllViews()
                evos.forEach { addEvoSprite(it, evolutionsContainer) }
                preEvos.forEach { addEvoSprite(it, preEvolutionsContainer) }
            }
        }
    }

    private fun addEvoSprite(number: String, container: LinearLayout) {
        val spriteUrl = "https://www.serebii.net/pokedex-sv/icon/$number.png"
        val iv = ImageView(this)
        iv.layoutParams = LinearLayout.LayoutParams(120, 120)
        iv.setPadding(8, 8, 8, 8)
        container.addView(iv)

        lifecycleScope.launch {
            val bitmap = loadCachedBitmap(spriteUrl) ?: withContext(Dispatchers.IO) {
                try {
                    val inputStream = URL(spriteUrl).openStream()
                    val b = BitmapFactory.decodeStream(inputStream)
                    if (b != null) saveBitmapToCache(spriteUrl, b)
                    b
                } catch (e: Exception) { null }
            }
            if (bitmap != null) {
                iv.setImageBitmap(bitmap)
                iv.setOnClickListener {
                    val artUrl = "https://www.serebii.net/pokemon/art/$number.png"
                    get_pokedex(number, spriteUrl, artUrl)
                }
            }
        }
    }

    private fun getTeamMemberEffectiveness(pokemon: PokemonInfo, enemy: PokemonInfo): Int {
        val move1Data = moveRepository.fetchMoveData(pokemon.move1)
        val move2Data = moveRepository.fetchMoveData(pokemon.move2)
        val move3Data = pokemon.move3?.let { moveRepository.fetchMoveData(it) }

        var hasSuper = false
        var hasNeutral = false

        fun check(data: MoveRepository.MoveData?) {
            if (data == null) return
            val total = moveRepository.calculateMoveEffectiveness(data.type, data.ignores, enemy.type1, enemy.type2)
            if (total > 0) hasSuper = true
            if (total >= 0) hasNeutral = true
        }

        check(move1Data)
        check(move2Data)
        check(move3Data)

        return if (hasSuper) 1 else if (!hasNeutral) -1 else 0
    }

    private fun isEnemyDangerous(enemy: PokemonInfo, target: PokemonInfo): Int {
        val move1Data = moveRepository.fetchMoveData(enemy.move1)
        val move2Data = moveRepository.fetchMoveData(enemy.move2)
        val move3Data = enemy.move3?.let { moveRepository.fetchMoveData(it) }

        var hasSuper = false
        var hasNeutral = false

        fun check(data: MoveRepository.MoveData?) {
            if (data == null) return
            val total = moveRepository.calculateMoveEffectiveness(data.type, data.ignores, target.type1, target.type2)
            if (total > 0) hasSuper = true
            if (total >= 0) hasNeutral = true
        }

        check(move1Data)
        check(move2Data)
        check(move3Data)

        return if (hasSuper) 1 else if (!hasNeutral) -1 else 0
    }

    private fun updateTeamView() {
        teamContainer.removeAllViews()
        for (i in 0 until 6) {
            val slotContainer = FrameLayout(this)
            val slotParams = LinearLayout.LayoutParams(120, 120)
            slotParams.setMargins(8, 0, 8, 0)
            slotContainer.layoutParams = slotParams

            val slotIv = ImageView(this)
            val ivParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

            if (currentTeamIndex == i) {
                slotContainer.setBackgroundColor(Color.BLUE)
                ivParams.setMargins(8, 8, 8, 8)
            } else {
                slotContainer.setBackgroundColor(Color.TRANSPARENT)
                ivParams.setMargins(0, 0, 0, 0)
            }
            slotIv.layoutParams = ivParams
            slotIv.scaleType = ImageView.ScaleType.FIT_CENTER
            slotContainer.addView(slotIv)

            val pokemon = teamPokemon[i]

            if (isSelectingSlot) {
                slotIv.setBackgroundColor(if (pokemon == null) Color.GREEN else Color.YELLOW)
                slotIv.setOnClickListener {
                    addToTeam(i)
                }
                if (pokemon?.spriteBitmap != null) slotIv.setImageBitmap(pokemon.spriteBitmap)
            } else {
                if (pokemon != null) {
                    slotIv.setBackgroundColor(Color.WHITE)

                    if (enemyPokemon != null) {
                        val ownEff = getTeamMemberEffectiveness(pokemon, enemyPokemon!!)

                        if (ownEff == 1) {
                            val greenArrow = ImageView(this)
                            try {
                                val inputStream = assets.open("arrow_green.png")
                                val bitmap = BitmapFactory.decodeStream(inputStream)
                                greenArrow.setImageBitmap(bitmap)
                            } catch (e: Exception) {}
                            val arrowParams = FrameLayout.LayoutParams(40, 40)
                            arrowParams.gravity = Gravity.BOTTOM or Gravity.START
                            greenArrow.layoutParams = arrowParams
                            slotContainer.addView(greenArrow)
                        }

                        val enemyEff = isEnemyDangerous(enemyPokemon!!, pokemon)
                        if (enemyEff == 1) {
                            val redArrow = ImageView(this)
                            try {
                                val inputStream = assets.open("arrow_red.png")
                                val bitmap = BitmapFactory.decodeStream(inputStream)
                                redArrow.setImageBitmap(bitmap)
                            } catch (e: Exception) {}
                            val arrowParams = FrameLayout.LayoutParams(40, 40)
                            arrowParams.gravity = Gravity.BOTTOM or Gravity.END
                            redArrow.layoutParams = arrowParams
                            slotContainer.addView(redArrow)
                        }
                    }

                    if (pokemon.spriteBitmap != null) {
                        slotIv.setImageBitmap(pokemon.spriteBitmap)
                        slotIv.setOnClickListener {
                            selectPokemon(pokemon, i)
                        }
                    } else if (pokemon.id.isNotEmpty()) {
                        val sUrl = if (pokemon.spriteUrl.isNotEmpty()) pokemon.spriteUrl
                                  else "https://www.serebii.net/pokedex-sv/icon/${pokemon.id}.png"

                        lifecycleScope.launch {
                            val bitmap = loadCachedBitmap(sUrl) ?: withContext(Dispatchers.IO) {
                                try {
                                    val inputStream = URL(sUrl).openStream()
                                    val b = BitmapFactory.decodeStream(inputStream)
                                    if (b != null) saveBitmapToCache(sUrl, b)
                                    b
                                } catch (e: Exception) { null }
                            }
                            if (bitmap != null) {
                                pokemon.spriteBitmap = bitmap
                                if (pokemon.spriteBase64 == null) {
                                    pokemon.spriteBase64 = bitmapToBase64(bitmap)
                                    saveTeamData()
                                }
                                withContext(Dispatchers.Main) {
                                    slotIv.setImageBitmap(bitmap)
                                    slotIv.setOnClickListener { selectPokemon(pokemon, i) }
                                }
                            }
                        }
                    }
                } else {
                    slotIv.setBackgroundColor(Color.LTGRAY)
                    slotIv.setOnClickListener(null)
                }
            }
            teamContainer.addView(slotContainer)
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
            val lang = prefs.getString("language", "en") ?: "en"
            val locale = if (lang == "de") Locale.GERMAN else Locale.ENGLISH
            val result = tts?.setLanguage(locale)
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

    private fun get_pokedex(number: String, spriteUrl: String, artUrl: String) {
        val info = pokedexRepository.findPokemonByNumber(number, spriteUrl, artUrl)
        ownPokemon = info
        currentTeamIndex = null // Clear index for new scan or evolution click
        if (info != null) {
            val finalArtUrl = if (artUrl.isNotEmpty()) artUrl else "https://www.serebii.net/pokemon/art/$number.png"
            downloadImage(finalArtUrl, spriteUrl)
            showDice(false)
            refreshMoves()
            updatePokedexButtonText()
            updateTeamView()
            updateAddRemoveButton()
            updateEvolutionViews()
            syncViaHttp()
        } else {
            textView.text = "Error reading Pokédex"
        }
    }

    private fun refreshMoves() {
        movesLayout.removeAllViews()
        ownPokemon?.let {
            addMoveRow(it.move1)
            addMoveRow(it.move2)
            if (it.move3 != null) {
                addMoveRow(it.move3!!, isTM = true)
            }
        }
    }

    private fun addMoveRow(moveName: String, isTM: Boolean = false) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val rowParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = 8
            bottomMargin = 8
        }
        row.layoutParams = rowParams

        // Speaker icon
        val speakerIv = ImageView(this)
        try {
            val inputStream = assets.open("speaker.png")
            val bitmap = BitmapFactory.decodeStream(inputStream)
            speakerIv.setImageBitmap(bitmap)
        } catch (e: Exception) {
            Log.e("UI", "Error loading speaker icon", e)
        }
        val sParams = LinearLayout.LayoutParams(100, 100)
        sParams.rightMargin = 16
        speakerIv.layoutParams = sParams
        speakerIv.setPadding(8, 8, 8, 8)
        speakerIv.setOnClickListener {
            val searchResult = search_moves(moveName).toString()
            val textToSpeak = searchResult
                .replace(Regex("\\{.*?\\}"), "")
                .replace(Regex("\\d+d\\d+"), "")
                .replace(Regex("\\d+"), "")
                .trim()
            speakOut(textToSpeak)
        }
        row.addView(speakerIv)

        // Find wurfel from move data
        val moveData = moveRepository.fetchMoveData(moveName)
        val wurfel = moveData?.wurfel

        // Die symbol ({d4}, {d8}, {G-Max d4}, etc.) from wurfel
        if (wurfel != null && (wurfel.contains("d4}") || wurfel.contains("d8}"))) {
            val dieType = if (wurfel.contains("d4}")) "d4" else "d8"
            val dieIv = ImageView(this)
            try {
                val inputStream = assets.open("move_symbols/$dieType.png")
                val bitmap = BitmapFactory.decodeStream(inputStream)
                dieIv.setImageBitmap(bitmap)
            } catch (e: Exception) {
                Log.e("UI", "Error loading $dieType icon", e)
            }
            val dParams = LinearLayout.LayoutParams(60, 60)
            dParams.rightMargin = 16
            dieIv.layoutParams = dParams
            row.addView(dieIv)
        }

        val moveTv = TextView(this)
        moveTv.text = search_moves(moveName)
        moveTv.textSize = 20f
        row.addView(moveTv)

        // Effectiveness arrow
        if (enemyPokemon != null) {
            if (moveData?.type != null) {
                val eff = moveRepository.calculateMoveEffectiveness(moveData.type, moveData.ignores, enemyPokemon!!.type1, enemyPokemon!!.type2)
                if (eff != 0) {
                    val arrowIv = ImageView(this)
                    val arrowPath = if (eff > 0) "arrow_green.png" else "arrow_red.png"
                    try {
                        val inputStream = assets.open(arrowPath)
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        arrowIv.setImageBitmap(bitmap)
                    } catch (e: Exception) {}
                    val aParams = LinearLayout.LayoutParams(40, 40)
                    aParams.leftMargin = 16
                    arrowIv.layoutParams = aParams
                    row.addView(arrowIv)
                }
            }
        }

        if (isTM) {
            val deleteIv = ImageView(this)
            try {
                val inputStream = assets.open("trash.png")
                val bitmap = BitmapFactory.decodeStream(inputStream)
                deleteIv.setImageBitmap(bitmap)
            } catch (e: Exception) {}
            val params = LinearLayout.LayoutParams(80, 80)
            params.leftMargin = 16
            deleteIv.layoutParams = params
            deleteIv.setOnClickListener {
                ownPokemon?.move3 = null
                refreshMoves()
                saveTeamData()
                updateTeamView()
                syncViaHttp()
            }
            row.addView(deleteIv)
        }

        movesLayout.addView(row)
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
                        syncViaHttp()
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

    private fun updateEnemySprite(spriteUrl: String) {
        if (spriteUrl.isEmpty()) {
            enemySpriteView.setImageDrawable(null)
            clearEnemyButton.visibility = View.GONE
            enemyTypesContainer.removeAllViews()
            return
        }

        enemyPokemon?.let { updateEnemyTypeViews(it) }

        lifecycleScope.launch {
            val spriteBitmap = loadCachedBitmap(spriteUrl)
            if (spriteBitmap != null) {
                enemySpriteView.setImageBitmap(spriteBitmap)
                clearEnemyButton.visibility = View.VISIBLE
            } else {
                val downloaded = withContext(Dispatchers.IO) {
                    try {
                        val inputStream = URL(spriteUrl).openStream()
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        if (bitmap != null) saveBitmapToCache(spriteUrl, bitmap)
                        bitmap
                    } catch (e: Exception) { null }
                }
                if (downloaded != null) {
                    enemySpriteView.setImageBitmap(downloaded)
                    clearEnemyButton.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun updateEnemyTypeViews(pokemon: PokemonInfo) {
        enemyTypesContainer.removeAllViews()
        addTypeIcon(pokemon.type1, enemyTypesContainer)
        if (pokemon.type2 != "None" && pokemon.type2.isNotBlank()) {
            addTypeIcon(pokemon.type2, enemyTypesContainer)
        }
    }

    private fun addTypeIcon(type: String, container: LinearLayout) {
        val cleanType = type.replace("{", "").replace("}", "").trim()
        val iv = ImageView(this)
        val size = 60
        iv.layoutParams = LinearLayout.LayoutParams(size, size).apply {
            bottomMargin = 4
        }
        try {
            val inputStream = assets.open("$cleanType.png")
            val bitmap = BitmapFactory.decodeStream(inputStream)
            iv.setImageBitmap(bitmap)
        } catch (e: Exception) {
            val tv = TextView(this)
            tv.text = cleanType
            tv.textSize = 8f
            container.addView(tv)
            return
        }
        container.addView(iv)
    }

    private fun clearEnemy() {
        enemyPokemon = null
        enemySpriteView.setImageDrawable(null)
        clearEnemyButton.visibility = View.GONE
        enemyTypesContainer.removeAllViews()
        refreshMoves()
        updateTeamView()
        syncViaHttp()
    }

    private fun updatePokedexButtonText() {
        ownPokemon?.let {
            val total = it.pokedexEntries.size
            if (total > 0) {
                val current = it.nextPokedexIndex
                var displayIndex = current
                if (displayIndex == 0) displayIndex = total

                pokedexButton.text = "Pokédex ($displayIndex/$total)"
            } else {
                pokedexButton.text = "Pokédex"
            }
        } ?: run {
            pokedexButton.text = "Pokédex"
        }
    }

    private fun search_moves(moveName: String): CharSequence {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val lang = prefs.getString("language", "en") ?: "en"
        val moveData = moveRepository.fetchMoveData(moveName) ?: return ""

        var wurfel = moveData.wurfel ?: ""
        var powerStr = if (moveName.endsWith("(S)")) moveData.powerStab else moveData.powerStr
        powerStr = powerStr?.replace("*", "") ?: "0"

        var powerval: Int
        if (powerStr.equals("1-2 Lvl", ignoreCase = true)) {
            powerval = ((ownPokemon?.base_level ?: 0) + (ownPokemon?.additionalLevel ?: 0)) / 2
        } else {
            powerval = powerStr.toIntOrNull() ?: 0
        }

        ownPokemon?.let {
            powerval += it.base_level + it.additionalLevel
        }

        var effectiveness = 0
        if (enemyPokemon != null) {
            effectiveness = moveRepository.calculateMoveEffectiveness(moveData.type, moveData.ignores, enemyPokemon!!.type1, enemyPokemon!!.type2)

            if (effectiveness == -4) effectiveness = -3
            if (effectiveness == 4) effectiveness = 3
            if (effectiveness < -4) {
                powerval = 0
            } else {
                powerval = powerval + effectiveness
            }
        }

        val builder = SpannableStringBuilder()
        val cleanType = moveData.type?.replace("{", "")?.replace("}", "")?.trim() ?: ""
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
            builder.append(moveData.type ?: "").append(" ")
        }

        val start = builder.length
        builder.append(powerval.toString())
        val end = builder.length

        if (enemyPokemon != null) {
            if (effectiveness < 0) {
                builder.setSpan(ForegroundColorSpan(Color.RED), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (effectiveness > 0) {
                builder.setSpan(ForegroundColorSpan(Color.GREEN), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        val finalMoveName = if (lang == "de") moveData.germanName else moveData.englishName
        val cleanWurfel = wurfel.replace(Regex("\\{.*?d[48]\\}"), "").trim()
        builder.append(" ").append(finalMoveName ?: "").append(" ").append(cleanWurfel)
        return builder
    }

    private fun downloadImage(artUrl: String, spriteUrl: String) {
        lifecycleScope.launch {
            val artBitmap = loadCachedBitmap(artUrl)
            if (artBitmap != null) {
                imageView.setImageBitmap(artBitmap)
            } else {
                val downloadedArt = withContext(Dispatchers.IO) {
                    try {
                        val inputStream = URL(artUrl).openStream()
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        if (bitmap != null) saveBitmapToCache(artUrl, bitmap)
                        bitmap
                    } catch (e: Exception) { null }
                }
                if (downloadedArt != null) {
                    imageView.setImageBitmap(downloadedArt)
                }
            }

            val spriteBitmap = loadCachedBitmap(spriteUrl)
            if (spriteBitmap != null) {
                ownPokemon?.let {
                    it.spriteBitmap = spriteBitmap
                    it.spriteBase64 = bitmapToBase64(spriteBitmap)
                }
                updateTeamView()
                updateAddRemoveButton()
            } else {
                val downloadedSprite = withContext(Dispatchers.IO) {
                    try {
                        val inputStream = URL(spriteUrl).openStream()
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        if (bitmap != null) saveBitmapToCache(spriteUrl, bitmap)
                        bitmap
                    } catch (e: Exception) { null }
                }
                ownPokemon?.let {
                    it.spriteBitmap = downloadedSprite
                    if (downloadedSprite != null) {
                        it.spriteBase64 = bitmapToBase64(downloadedSprite)
                    }
                }
                updateTeamView()
                updateAddRemoveButton()
            }
        }
    }

    private fun getCacheFile(url: String): File {
        val dir = File(filesDir, IMAGE_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        val fileName = Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP)
        return File(dir, fileName)
    }

    private fun saveBitmapToCache(url: String, bitmap: Bitmap) {
        try {
            val file = getCacheFile(url)
            val out = FileOutputStream(file)
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush()
            out.close()
        } catch (e: Exception) {
            Log.e("Cache", "Error saving image to cache", e)
        }
    }

    private fun loadCachedBitmap(url: String): Bitmap? {
        val file = getCacheFile(url)
        return if (file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath)
        } else {
            null
        }
    }

    override fun onDestroy() {
        if (tts != null) {
            tts?.stop()
            tts?.shutdown()
        }
        HttpSyncService.removeStatusListener(statusListener)
        HttpSyncService.stopAll()
        super.onDestroy()
    }
}
