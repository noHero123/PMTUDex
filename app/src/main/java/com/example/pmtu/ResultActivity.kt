package com.example.pmtu

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.os.Bundle
import android.speech.tts.TextToSpeech
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
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.util.Locale

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
    private var currentLanguage: String? = "en"
    private var currentDisableSpeakers: Boolean? = false
    
    private var isSelectingSlot = false

    private val viewModel: ResultViewModel by viewModels()
    private lateinit var pokedexRepository: PokedexRepository
    private lateinit var moveRepository: MoveRepository
    private lateinit var trainerRepository: TrainerRepository
    private lateinit var scanHandler: ScanHandler
    private lateinit var uiMapper: PokemonUiMapper

    private val statusListener = { status: HttpSyncService.Status, _: String? ->
        if (status == HttpSyncService.Status.CONNECTED) {
            syncViaHttp()
        }
    }

    private val pokemonScannerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val scannedText = result.data?.getStringExtra("SCANNED_TEXT")
            if (scannedText != null) {
                processScanResult(scannedText)
            }
        }
    }

    private val teamBrowserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            //1. Extract the structure from the Intent
            val selectedTeam = result.data?.getParcelableExtra<SavedTeam>("SELECTED_TEAM")

            selectedTeam?.let { team ->
                // 2. Pass the structure directly to the ViewModel
                viewModel.setTeam(team.pokemon)

                // 3. Optional: Also save it to the "current" file for persistence
                viewModel.saveTeamData()

                viewModel.setUpdateUI()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pokedexRepository = PokedexRepository(this)
        moveRepository = MoveRepository(this)
        trainerRepository = TrainerRepository(this)
        scanHandler = ScanHandler(this, viewModel, pokedexRepository, moveRepository, trainerRepository)
        uiMapper = PokemonUiMapper(this)

        setupWindow()
        setupHttpSync()
        setupUI()
        observeViewModel()

        tts = TextToSpeech(this, this)

        intent.getStringExtra("SCANNED_TEXT")?.let { processScanResult(it) }
    }

    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupHttpSync() {
        HttpSyncService.onDataReceived = { json ->
            lifecycleScope.launch {
                try {
                    val data = Gson().fromJson(json, HttpSyncService.SyncData::class.java)
                    if (data.type == "SYNC") {
                        val receivedOwn = data.ownPokemonJson?.let { Gson().fromJson(it, PokemonInfo::class.java) }
                        val receivedEnemy = data.enemyPokemonJson?.let { Gson().fromJson(it, PokemonInfo::class.java) }
                        
                        if (HttpSyncService.isMaster) {
                            viewModel.setEnemyPokemon(receivedOwn)
                            viewModel.setEnemyWeather(data.ownWeather)
                        } else {
                            viewModel.setEnemyPokemon(receivedOwn)
                            viewModel.setOwnPokemon(receivedEnemy, null)
                            viewModel.setOwnWeather(data.enemyWeather)
                            viewModel.setEnemyWeather(data.ownWeather)
                        }
                        viewModel.setUpdateUINoSync()
                    }
                } catch (e: Exception) {
                    Log.e("SYNC", "Error parsing received data", e)
                }
            }
        }
        HttpSyncService.addStatusListener(statusListener)
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.updateUI.collectLatest {

                        updateEnemySprite(viewModel.enemyPokemon.value?.spriteUrl ?: "")
                        refreshUI()
                        syncViaHttp()
                    }
                }
                launch {
                    viewModel.updateUINoSync.collectLatest {
                        updateEnemySprite(viewModel.enemyPokemon.value?.spriteUrl ?: "")
                        refreshUI()
                    }
                }
                launch {
                    viewModel.ownPokemon.collectLatest { pokemon ->
                        uiMapper.updatePokemonImage(pokemon, imageView, android.R.drawable.ic_menu_camera)
                        pokemon?.let { p ->
                            val artUrl = if (p.artUrl.isNotEmpty()) p.artUrl else "https://www.serebii.net/pokemon/art/${p.id}.png"
                            downloadImage(artUrl, p.spriteUrl)
                        }
                        //refreshUI()
                        //syncViaHttp()
                    }
                }
                launch {
                    viewModel.enemyPokemon.collectLatest { pokemon ->
                        //updateEnemySprite(pokemon?.spriteUrl ?: "")
                        //refreshUI()
                        //syncViaHttp()
                    }
                }
                launch {
                    viewModel.teamPokemon.collectLatest {
                        //refreshUI()
                        //syncViaHttp()
                    }
                }
                launch {
                    viewModel.currentTeamIndex.collectLatest {
                        //refreshUI()
                        //syncViaHttp()
                    }
                }
                launch {
                    viewModel.ownWeather.collectLatest { 
                        //refreshUI()
                        //syncViaHttp()
                    }
                }
                launch {
                    viewModel.enemyWeather.collectLatest { 
                        //refreshUI()
                    }
                }
            }
        }
    }

    private fun refreshUI() {
        showDice(false)
        refreshMoves()
        updatePokedexButtonText()
        updateAddRemoveButton()
        updateEvolutionViews()
        updateTeamView()
    }

    private fun processScanResult(scannedText: String) {
        when (val result = scanHandler.handleScan(scannedText, this)) {
            is ScanHandler.ScanResult.Connect -> {
                HttpSyncService.startAsSlave(result.ip)
                Toast.makeText(this, "Connecting to Master at ${result.ip}...", Toast.LENGTH_SHORT).show()
            }
            is ScanHandler.ScanResult.Pokemon -> {
                val spriteUrl = "https://www.serebii.net/pokedex-sv/icon/${result.number}.png"
                val artUrl = "https://www.serebii.net/pokemon/art/${result.number}.png"
                get_pokedex(result.number, spriteUrl, artUrl)
            }
            else -> {}
        }
    }


    private fun setupUI() {
        val rootLayout = FrameLayout(this)
        rootLayout.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val mainContainer = LinearLayout(this)
        mainContainer.orientation = LinearLayout.VERTICAL
        mainContainer.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        // Top bar
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(16, 16, 64, 16)
        }
        settingsButton = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            layoutParams = LinearLayout.LayoutParams(80, 80)
            setOnClickListener {
                // Use the launcher instead of startActivity
                val intent = Intent(this@ResultActivity, SettingsActivity::class.java)
                teamBrowserLauncher.launch(intent)
            }
        }
        topBar.addView(settingsButton)
        mainContainer.addView(topBar)

        // Team
        teamContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        ViewCompat.setOnApplyWindowInsetsListener(teamContainer) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.updateLayoutParams<LinearLayout.LayoutParams> { topMargin = insets.top + 8 }
            windowInsets
        }
        mainContainer.addView(teamContainer)

        addRemoveButton = Button(this)
        val buttonWrapper = LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(addRemoveButton)
        }
        mainContainer.addView(buttonWrapper)

        // Center
        val centerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)
            setPadding(32, 0, 32, 32)
        }
        diceContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16 }
        }
        centerContainer.addView(diceContainer)

        val imageEvoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        preEvolutionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        imageEvoLayout.addView(preEvolutionsContainer)

        imageView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            layoutParams = LinearLayout.LayoutParams(600, 600)
        }
        imageEvoLayout.addView(imageView)

        evolutionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        imageEvoLayout.addView(evolutionsContainer)
        centerContainer.addView(imageEvoLayout)

        pokedexButton = Button(this).apply {
            text = "Pokédex"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 32
                bottomMargin = 32
            }
            setOnClickListener {
                viewModel.ownPokemon.value?.let {
                    if (it.pokedexEntries.isNotEmpty()) {
                        val entry = it.pokedexEntries[it.nextPokedexIndex]
                        speakOut("${it.name}. $entry")
                        it.nextPokedexIndex = (it.nextPokedexIndex + 1) % it.pokedexEntries.size
                        updatePokedexButtonText()
                    }
                }
            }
        }
        centerContainer.addView(pokedexButton)

        movesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        centerContainer.addView(movesLayout)

        textView = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
        }
        movesLayout.addView(textView)
        mainContainer.addView(centerContainer)

        // Bottom
        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                setMargins(64, 0, 64, 128)
            }
        }
        val buttonLayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)

        val newScanButton = Button(this).apply {
            text = "New Scan"
            layoutParams = buttonLayoutParams
            setOnClickListener { pokemonScannerLauncher.launch(Intent(this@ResultActivity, MainActivity::class.java)) }
        }
        buttonContainer.addView(newScanButton)

        buttonContainer.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(32, 1) })

        val enemyLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = buttonLayoutParams
        }
        val enemyInfoContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        enemyTypesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = 8 }
        }
        enemyInfoContainer.addView(enemyTypesContainer)

        enemySpriteView = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(120, 120) }
        enemyInfoContainer.addView(enemySpriteView)

        clearEnemyButton = ImageView(this).apply {
            try {
                setImageBitmap(BitmapFactory.decodeStream(assets.open("trash.png")))
            } catch (e: Exception) {}
            layoutParams = LinearLayout.LayoutParams(100, 100).apply { leftMargin = 8 }
            visibility = View.GONE
            setOnClickListener {
                viewModel.clearEnemy()
                viewModel.setUpdateUI()}
        }
        enemyInfoContainer.addView(clearEnemyButton)

        val switchToEnemyButton = Button(this).apply {
            text = "Switch to Enemy"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener {
                viewModel.switchWithEnemy()
                viewModel.setUpdateUI()
                if (viewModel.enemyPokemon.value != null) {
                    //syncViaHttp()
                } else {
                    Toast.makeText(this@ResultActivity, "No enemy to switch with", Toast.LENGTH_SHORT).show()
                }
            }
        }
        enemyLayout.addView(enemyInfoContainer)
        enemyLayout.addView(switchToEnemyButton)
        buttonContainer.addView(enemyLayout)

        mainContainer.addView(buttonContainer)
        rootLayout.addView(mainContainer)
        setContentView(rootLayout)
    }

    private fun syncViaHttp() {
        if (HttpSyncService.connectionStatus == HttpSyncService.Status.CONNECTED) {
            HttpSyncService.sendData(HttpSyncService.SyncData(
                type = "SYNC",
                ownPokemonJson = Gson().toJson(viewModel.ownPokemon.value),
                enemyPokemonJson = Gson().toJson(viewModel.enemyPokemon.value),
                ownWeather = viewModel.ownWeather.value,
                enemyWeather = viewModel.enemyWeather.value
            ))
        }
    }

    private fun updateAddRemoveButton() {
        val current = viewModel.ownPokemon.value
        if (current == null) {
            addRemoveButton.visibility = View.GONE
            return
        }
        addRemoveButton.visibility = View.VISIBLE
        if (viewModel.currentTeamIndex.value != null) {
            addRemoveButton.text = "-"
            addRemoveButton.setOnClickListener {
                viewModel.removeFromTeam()
                viewModel.setUpdateUI()
            }
        } else {
            addRemoveButton.text = "+"
            addRemoveButton.setOnClickListener {
                isSelectingSlot = true
                Toast.makeText(this, "Select a slot to save ${current.name}", Toast.LENGTH_SHORT).show()
                updateTeamView()
            }
        }
    }

    private fun updateTeamView() {
        teamContainer.removeAllViews()
        val team = viewModel.teamPokemon.value
        val currentIndex = viewModel.currentTeamIndex.value
        val enemy = viewModel.enemyPokemon.value

        for (i in 0 until 6) {
            val slotContainer = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(120, 120).apply { setMargins(8, 0, 8, 0) }
                setBackgroundColor(if (currentIndex == i) Color.BLUE else Color.TRANSPARENT)
            }
            val slotIv = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                    if (currentIndex == i) setMargins(8, 8, 8, 8)
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            slotContainer.addView(slotIv)

            val pokemon = team[i]
            if (isSelectingSlot) {
                slotIv.setBackgroundColor(if (pokemon == null) Color.GREEN else Color.YELLOW)
                slotIv.setOnClickListener {
                    isSelectingSlot = false
                    viewModel.addToTeam(i)
                    viewModel.setUpdateUI()
                }
                pokemon?.spriteBitmap?.let { slotIv.setImageBitmap(it) }
            } else if (pokemon != null) {
                slotIv.setBackgroundColor(Color.WHITE)
                if (enemy != null) {
                    if (moveRepository.getPokemonEffectiveness(pokemon, enemy) == 1) addArrow(slotContainer, "arrow_green.png", Gravity.BOTTOM or Gravity.START)
                    if (moveRepository.isPokemonDangerous(enemy, pokemon) == 1) addArrow(slotContainer, "arrow_red.png", Gravity.BOTTOM or Gravity.END)
                }
                pokemon.spriteBitmap?.let {
                    slotIv.setImageBitmap(it)
                    slotIv.setOnClickListener {
                        viewModel.setOwnPokemon(pokemon, i)
                        viewModel.setUpdateUI()
                    }
                } ?: run {
                    slotIv.setBackgroundColor(Color.LTGRAY)
                    loadTeamSprite(pokemon, i, slotIv)
                }
            } else {
                slotIv.setBackgroundColor(Color.LTGRAY)
            }
            teamContainer.addView(slotContainer)
        }
    }

    private fun addArrow(container: FrameLayout, assetName: String, gravity: Int) {
        val arrow = ImageView(this)
        try { arrow.setImageBitmap(BitmapFactory.decodeStream(assets.open(assetName))) } catch (e: Exception) {}
        arrow.layoutParams = FrameLayout.LayoutParams(40, 40).apply { this.gravity = gravity }
        container.addView(arrow)
    }

    private fun loadTeamSprite(pokemon: PokemonInfo, index: Int, imageView: ImageView) {
        lifecycleScope.launch {
            val url = if (pokemon.spriteUrl.isNotEmpty()) pokemon.spriteUrl else "https://www.serebii.net/pokedex-sv/icon/${pokemon.id}.png"
            val bitmap = loadCachedBitmap(url) ?: withContext(Dispatchers.IO) {
                try {
                    val b = BitmapFactory.decodeStream(URL(url).openStream())
                    if (b != null) saveBitmapToCache(url, b)
                    b
                } catch (e: Exception) { null }
            }
            bitmap?.let {
                pokemon.spriteBitmap = it
                imageView.setBackgroundColor(Color.WHITE)
                imageView.setImageBitmap(it)
                imageView.setOnClickListener {
                    viewModel.setOwnPokemon(pokemon, index)
                    viewModel.setUpdateUI()}
            }
        }
    }

    private fun refreshMoves() {
        movesLayout.removeAllViews()
        val own = viewModel.ownPokemon.value ?: return
        addMoveRow(own.move1)
        addMoveRow(own.move2)
        own.teraType?.let { addTeraRow(own) }
        own.typeEnhancerType?.let { addTypeEnhancerRow(own) }
        own.baseItem?.let { addBaseItemRow(own) }
        own.move3?.let { addMoveRow(it, true) }
    }

    private fun addMoveRow(moveName: String, isTM: Boolean = false) {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 8
                bottomMargin = 8
            }
        }

        // Speaker
        if (!prefs.getBoolean("disable_speakers", false)) {
            val speakerIv = ImageView(this).apply {
                try { setImageBitmap(BitmapFactory.decodeStream(assets.open("speaker.png"))) } catch (e: Exception) {}
                layoutParams = LinearLayout.LayoutParams(100, 100).apply { rightMargin = 16 }
                setPadding(8, 8, 8, 8)
                setOnClickListener {
                    val result = moveRepository.calculateMovePower(moveName, viewModel.ownPokemon.value!!, viewModel.enemyPokemon.value, viewModel.ownWeather.value, viewModel.enemyWeather.value)
                    val lang = prefs.getString("language", "en") ?: "en"
                    if (lang == "en") {
                        speakOut(result?.moveData?.englishName ?: "Unknown move")
                    }
                    if (lang == "de")
                        speakOut(result?.moveData?.germanName ?: "Unbekannter Zug")
                }
            }
            row.addView(speakerIv)
        }

        val result = moveRepository.calculateMovePower(moveName, viewModel.ownPokemon.value!!, viewModel.enemyPokemon.value, viewModel.ownWeather.value, viewModel.enemyWeather.value) ?: return
        
        // Die
        result.moveData.wurfel?.let { w ->
            if (w.contains("d4}") || w.contains("d8}")) {
                val dieIv = ImageView(this).apply {
                    val dieType = if (w.contains("d4}")) "d4" else "d8"
                    try { setImageBitmap(BitmapFactory.decodeStream(assets.open("move_symbols/$dieType.png"))) } catch (e: Exception) {}
                    layoutParams = LinearLayout.LayoutParams(60, 60).apply { rightMargin = 16 }
                }
                row.addView(dieIv)
            }
        }

        val moveTv = TextView(this).apply {
            text = uiMapper.formatMoveText(result, this, prefs.getString("language", "en") ?: "en", viewModel.ownPokemon.value,viewModel.enemyPokemon.value, viewModel.ownWeather.value, viewModel.enemyWeather.value, pokedexRepository)
            textSize = 20f
        }
        row.addView(moveTv)

        // Arrow
        if (viewModel.enemyPokemon.value != null && result.effectiveness != 0) {
            val arrowIv = ImageView(this).apply {
                try { setImageBitmap(BitmapFactory.decodeStream(assets.open(if (result.effectiveness > 0) "arrow_green.png" else "arrow_red.png"))) } catch (e: Exception) {}
                layoutParams = LinearLayout.LayoutParams(40, 40).apply { leftMargin = 16 }
            }
            row.addView(arrowIv)
        }

        if (isTM && viewModel.ownPokemon.value?.isTrainerPokemon != true) {
            val deleteIv = ImageView(this).apply {
                try { setImageBitmap(BitmapFactory.decodeStream(assets.open("trash.png"))) } catch (e: Exception) {}
                layoutParams = LinearLayout.LayoutParams(80, 80).apply { leftMargin = 16 }
                setOnClickListener {
                    viewModel.ownPokemon.value?.move3 = null
                    refreshMoves()
                    viewModel.saveTeamData()
                }
            }
            row.addView(deleteIv)
        }
        movesLayout.addView(row)
    }


    private fun addTeraRow(pokemon: PokemonInfo) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 16, 0, 16) }
        }
        val teraIv = ImageView(this).apply {
            try { setImageBitmap(BitmapFactory.decodeStream(assets.open("tera/Tera Type - ${pokemon.teraType}.png"))) } catch (e: Exception) {}
            layoutParams = LinearLayout.LayoutParams(150, 150)
            colorFilter = if (!pokemon.isTeraActivated) ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) else null
            setOnClickListener {
                pokemon.isTeraActivated = !pokemon.isTeraActivated
                refreshMoves()
                viewModel.saveTeamData()
                syncViaHttp()
            }
        }
        row.addView(teraIv)
        addDeleteButton(row) {
            pokemon.teraType = null
            pokemon.isTeraActivated = false
            refreshMoves()
            viewModel.saveTeamData()
        }
        movesLayout.addView(row)
    }

    private fun addTypeEnhancerRow(pokemon: PokemonInfo) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 16, 0, 16) }
        }
        val iv = ImageView(this).apply {
            try { setImageBitmap(BitmapFactory.decodeStream(assets.open("type_enhancer/TypeEnhancer${pokemon.typeEnhancerType}.png"))) } catch (e: Exception) {}
            layoutParams = LinearLayout.LayoutParams(150, 150)
        }
        row.addView(iv)
        addDeleteButton(row) {
            pokemon.typeEnhancerType = null
            refreshMoves()
            viewModel.saveTeamData()
        }
        movesLayout.addView(row)
    }

    private fun addBaseItemRow(pokemon: PokemonInfo) {
        val itemname = pokemon.baseItem
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 16, 0, 16) }
        }
        val iv = ImageView(this).apply {
            try { setImageBitmap(BitmapFactory.decodeStream(assets.open("base_items/${itemname}.png"))) } catch (e: Exception) {}
            layoutParams = LinearLayout.LayoutParams(150, 150)
            colorFilter = if (!pokemon.isBaseItemActivated) ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) else null
            setOnClickListener {
                val toggleableItems = listOf("Evio", "Left", "Quic", "Wide")
                if (pokemon.baseItem in toggleableItems) {
                    pokemon.isBaseItemActivated = !pokemon.isBaseItemActivated
                }
                refreshMoves()
                viewModel.saveTeamData()
                syncViaHttp()
            }
        }
        row.addView(iv)
        addDeleteButton(row) {
            pokemon.baseItem = null
            pokemon.isBaseItemActivated = false
            refreshMoves()
            viewModel.saveTeamData()
        }
        movesLayout.addView(row)
    }

    private fun addDeleteButton(row: LinearLayout, onClick: () -> Unit) {
        val deleteIv = ImageView(this).apply {
            try { setImageBitmap(BitmapFactory.decodeStream(assets.open("trash.png"))) } catch (e: Exception) {}
            layoutParams = LinearLayout.LayoutParams(80, 80).apply { leftMargin = 32 }
            setOnClickListener {
                onClick()
                syncViaHttp()
            }
        }
        row.addView(deleteIv)
    }

    private fun showDice(all: Boolean) {
        diceContainer.removeAllViews()
        val own = viewModel.ownPokemon.value ?: return
        val level = own.additionalLevel

        if (all) {
            for (i in 0..6) {
                val diceIv = ImageView(this).apply {
                    try { setImageBitmap(BitmapFactory.decodeStream(assets.open("blued6_$i.png"))) } catch (e: Exception) {}
                    layoutParams = LinearLayout.LayoutParams(100, 100).apply { setMargins(8, 0, 8, 0) }
                    setOnClickListener {
                        own.additionalLevel = i
                        showDice(false)
                        refreshMoves()
                        viewModel.saveTeamData()
                        syncViaHttp()
                    }
                }
                diceContainer.addView(diceIv)
            }
        } else {
            val diceIv = ImageView(this).apply {
                try { setImageBitmap(BitmapFactory.decodeStream(assets.open("blued6_$level.png"))) } catch (e: Exception) {}
                layoutParams = LinearLayout.LayoutParams(150, 150)
                setOnClickListener { showDice(true) }
            }
            diceContainer.addView(diceIv)
        }

        viewModel.ownWeather.value?.let { weather ->
            val weatherIv = ImageView(this).apply {
                try { setImageBitmap(BitmapFactory.decodeStream(assets.open("Field/$weather.png"))) } catch (e: Exception) {}
                layoutParams = LinearLayout.LayoutParams(150, 150).apply { leftMargin = 32 }
            }
            diceContainer.addView(weatherIv)
            val trashIv = ImageView(this).apply {
                try { setImageBitmap(BitmapFactory.decodeStream(assets.open("trash.png"))) } catch (e: Exception) {}
                layoutParams = LinearLayout.LayoutParams(80, 80).apply { leftMargin = 8 }
                setOnClickListener {
                    viewModel.setOwnWeather(null)
                    viewModel.setUpdateUI()
                    //syncViaHttp()
                }
            }
            diceContainer.addView(trashIv)
        }
    }

    private fun updateEnemySprite(spriteUrl: String) {
        if (spriteUrl.isEmpty()) {
            enemySpriteView.setImageDrawable(null)
            clearEnemyButton.visibility = View.GONE
            enemyTypesContainer.removeAllViews()
            //viewModel.setUpdateUI()
            return
        }
        uiMapper.updateEnemyTypeIcons(viewModel.enemyPokemon.value, enemyTypesContainer)
        viewModel.enemyWeather.value?.let { weather ->
            val weatherIv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(80, 80).apply { bottomMargin = 8 }
                try { setImageBitmap(BitmapFactory.decodeStream(assets.open("Field/$weather.png"))) } catch (e: Exception) {}
            }
            enemyTypesContainer.addView(weatherIv, 0)
        }

        lifecycleScope.launch {
            val bitmap = loadCachedBitmap(spriteUrl) ?: withContext(Dispatchers.IO) {
                try {
                    val b = BitmapFactory.decodeStream(URL(spriteUrl).openStream())
                    if (b != null) saveBitmapToCache(spriteUrl, b)
                    b
                } catch (e: Exception) { null }
            }
            bitmap?.let {
                enemySpriteView.setImageBitmap(it)
                clearEnemyButton.visibility = View.VISIBLE
            }
        }
    }

    private fun get_pokedex(number: String, spriteUrl: String, artUrl: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val info = pokedexRepository.findPokemonByNumber(number, spriteUrl, artUrl)
            withContext(Dispatchers.Main) {
                if (info != null) {
                    viewModel.setOwnPokemon(info, null)
                    //syncViaHttp()
                    viewModel.setUpdateUI()
                } else {
                    textView.text = "Error reading Pokédex"
                }
            }
        }
    }

    private fun updatePokedexButtonText() {
        viewModel.ownPokemon.value?.let {
            pokedexButton.text = if (it.pokedexEntries.isNotEmpty()) "Pokédex (${if (it.nextPokedexIndex == 0) it.pokedexEntries.size else it.nextPokedexIndex}/${it.pokedexEntries.size})" else "Pokédex"
        } ?: run { pokedexButton.text = "Pokédex" }
    }

    private fun downloadImage(artUrl: String, spriteUrl: String) {
        lifecycleScope.launch {
            val artBitmap = loadCachedBitmap(artUrl) ?: withContext(Dispatchers.IO) {
                try {
                    val b = BitmapFactory.decodeStream(URL(artUrl).openStream())
                    if (b != null) saveBitmapToCache(artUrl, b)
                    b
                } catch (e: Exception) { null }
            }
            artBitmap?.let { imageView.setImageBitmap(it) }

            val spriteBitmap = loadCachedBitmap(spriteUrl) ?: withContext(Dispatchers.IO) {
                try {
                    val b = BitmapFactory.decodeStream(URL(spriteUrl).openStream())
                    if (b != null) saveBitmapToCache(spriteUrl, b)
                    b
                } catch (e: Exception) { null }
            }
            spriteBitmap?.let {
                viewModel.ownPokemon.value?.let { p ->
                    p.spriteBitmap = it
                    p.spriteBase64 = bitmapToBase64(it)
                    viewModel.saveTeamData()
                    //updateTeamView()
                    //updateAddRemoveButton()
                    viewModel.setUpdateUI()
                }
            }
        }
    }

    private fun updateEvolutionViews() {
        evolutionsContainer.removeAllViews()
        preEvolutionsContainer.removeAllViews()
        val own = viewModel.ownPokemon.value ?: return
        if (own.isTrainerPokemon) return
        lifecycleScope.launch(Dispatchers.IO) {
            val (evos, preEvos) = pokedexRepository.getEvolutions(own.id)
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
        val iv = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(120, 120)
            setPadding(8, 8, 8, 8)
        }
        container.addView(iv)
        lifecycleScope.launch {
            val bitmap = loadCachedBitmap(spriteUrl) ?: withContext(Dispatchers.IO) {
                try {
                    val b = BitmapFactory.decodeStream(URL(spriteUrl).openStream())
                    if (b != null) saveBitmapToCache(spriteUrl, b)
                    b
                } catch (e: Exception) { null }
            }
            bitmap?.let {
                iv.setImageBitmap(it)
                iv.setOnClickListener { get_pokedex(number, spriteUrl, "https://www.serebii.net/pokemon/art/$number.png") }
            }
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        return Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
    }

    private fun getCacheFile(url: String): File {
        val dir = File(filesDir, "pokemon_images")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP))
    }

    private fun saveBitmapToCache(url: String, bitmap: Bitmap) {
        try {
            val out = FileOutputStream(getCacheFile(url))
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.flush(); out.close()
        } catch (e: Exception) {}
    }

    private fun loadCachedBitmap(url: String): Bitmap? {
        val file = getCacheFile(url)
        return if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val lang = getSharedPreferences("settings", Context.MODE_PRIVATE).getString("language", "en") ?: "en"
            tts?.setLanguage(if (lang == "de") Locale.GERMAN else Locale.ENGLISH)
            isTtsReady = true
            pendingTTS?.let { speakOut(it); pendingTTS = null }
        }
    }

    private fun speakOut(text: String) {
        if (isTtsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "") else pendingTTS = text
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown()
        HttpSyncService.removeStatusListener(statusListener)
        HttpSyncService.stopAll()
        super.onDestroy()
    }
}
