package com.example.pmtu

import android.widget.PopupWindow
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
import androidx.appcompat.app.AlertDialog
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
    private lateinit var enemyStatusContainer: LinearLayout
    private lateinit var clearEnemyButton: ImageView
    private lateinit var pokedexButton: Button
    private lateinit var statusFieldContainer: LinearLayout
    private lateinit var fieldEffectsContainer: LinearLayout
    private lateinit var addRemoveButton: Button
    private lateinit var evolutionsContainer: LinearLayout
    private lateinit var preEvolutionsContainer: LinearLayout
    private lateinit var movesLayout: LinearLayout
    private lateinit var settingsButton: ImageView
    private lateinit var syncInfoRow: LinearLayout
    private lateinit var connectionCountTv: TextView
    private lateinit var fightButton: ImageView
    
    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingTTS: String? = null
    
    private var isSelectingSlot = false

    private val viewModel: ResultViewModel by viewModels()
    private lateinit var pokedexRepository: PokedexRepository
    private lateinit var moveRepository: MoveRepository
    private lateinit var trainerRepository: TrainerRepository
    private lateinit var scanHandler: ScanHandler
    private lateinit var uiMapper: PokemonUiMapper
    private lateinit var detailsRepository: DetailsRepository
    private lateinit var syncManager: SyncManager

    private fun logging(message: String) {
        Log.d("ResultActivity", message)
    }

    private val statusListener = { status: P2PSyncService.Status, _: String? ->
        runOnUiThread {
            if (::syncInfoRow.isInitialized) {
                syncInfoRow.visibility = if (P2PSyncService.isServerEnabledByUser) View.VISIBLE else View.GONE
            }
            if (::connectionCountTv.isInitialized) {
                connectionCountTv.text = "Connections: ${P2PSyncService.activeConnections}"
            }
            updateFightButton()
        }
        if (status == P2PSyncService.Status.CONNECTED) {
            lifecycleScope.launch {
                logging("status listener $status")
                syncManager.syncViaP2P()
            }
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
            val selectedTeam = result.data?.getParcelableExtra<SavedTeam>("SELECTED_TEAM")
            selectedTeam?.let { team ->
                viewModel.setTeam(team.pokemon)
                viewModel.saveTeamData()
                viewModel.setUpdateUI()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val currentLang = prefs.getString("language", "en") ?: "en"
        tts?.setLanguage(if (currentLang == "de") Locale.GERMAN else Locale.ENGLISH)
        viewModel.checkLanguageAndReset(currentLang, pokedexRepository)
        setupWindow()
        logging("onResume ${P2PSyncService.connectionStatus} ${P2PSyncService.isServerEnabledByUser}")
        if (P2PSyncService.connectionStatus != P2PSyncService.Status.CONNECTED && P2PSyncService.isServerEnabledByUser && !P2PSyncService.isPending()) {
            logging("try to reconnect")
            P2PSyncService.reconnect()
        }
        viewModel.setUpdateUI()
        if (::syncInfoRow.isInitialized) {
            syncInfoRow.visibility = if (P2PSyncService.isServerEnabledByUser) View.VISIBLE else View.GONE
        }
        if (::connectionCountTv.isInitialized) {
            connectionCountTv.text = "Connections: ${P2PSyncService.activeConnections}"
        }
        updateFightButton()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pokedexRepository = PokedexRepository(this)
        moveRepository = MoveRepository(this)
        trainerRepository = TrainerRepository(this)
        scanHandler = ScanHandler(this, viewModel, pokedexRepository, moveRepository, trainerRepository)
        uiMapper = PokemonUiMapper(this)
        detailsRepository = DetailsRepository(this)
        syncManager = SyncManager(
            context = this,
            viewModel = viewModel,
            onFightStarted = { runOnUiThread { updateFightButton() }; syncManager.syncViaP2P() },
            onFightEnded = { 
                runOnUiThread { 
                    updateFightButton()
                    viewModel.setEnemyPokemon(null)
                    viewModel.setUpdateUI()
                } 
            },
            onSyncReceived = { pokemon, weather ->
                viewModel.setEnemyPokemon(pokemon)
                viewModel.setEnemyWeather(weather)
                viewModel.setUpdateUINoSync()
                P2PSyncService.sendOK()
            }
        )

        setupWindow()
        setupUI()
        P2PSyncService.addStatusListener(statusListener)
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

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.updateUI.collectLatest {
                        updateEnemySprite(viewModel.enemyPokemon.value?.spriteUrl ?: "")
                        refreshUI()
                        syncManager.syncViaP2P()
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
        updateStatusFieldIcons()
        updateFieldIcons()
    }

    private fun processScanResult(scannedText: String) {
        when (val result = scanHandler.handleScan(scannedText, this)) {
            is ScanHandler.ScanResult.Connect -> {
                P2PSyncService.startClient(result.ip)
                Toast.makeText(this, "Connecting to Peer at ${result.ip}...", Toast.LENGTH_SHORT).show()
            }
            is ScanHandler.ScanResult.Pokemon -> {
                val spriteUrl = "https://www.serebii.net/pokedex-sv/icon/${result.number}.png"
                val artUrl = "https://www.serebii.net/pokemon/art/${result.number}.png"
                get_pokedex(result.number, spriteUrl, artUrl)
            }
            else -> {}
        }
    }

    private fun evolvePokemon(newPokemonID: String, levelDiff: Int = 0, source: String = "lvl") {
        val oldPoke = viewModel.ownPokemon.value
        val spriteUrl = "https://www.serebii.net/pokedex-sv/icon/${newPokemonID}.png"
        val artUrl = "https://www.serebii.net/pokemon/art/${newPokemonID}.png"
        val newPoke = pokedexRepository.findPokemonByNumber(newPokemonID, spriteUrl, artUrl)
        if (newPoke != null && oldPoke != null) {
            newPoke.copyStateFrom(oldPoke)
            newPoke.additionalLevel += levelDiff
            if (source == "mega") newPoke.isBaseItemActivated = true
            if (source == "gmax") newPoke.isGigaDynaActivated = true
        }
        viewModel.setOwnPokemon(newPoke)
        viewModel.setUpdateUI()
    }

    private fun setupUI() {
        val rootLayout = FrameLayout(this)
        rootLayout.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        val mainContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(16, 16, 64, 16)
        }
        settingsButton = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            layoutParams = LinearLayout.LayoutParams(80, 80)
            setOnClickListener { teamBrowserLauncher.launch(Intent(this@ResultActivity, SettingsActivity::class.java)) }
        }
        topBar.addView(settingsButton)
        mainContainer.addView(topBar)
        syncInfoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(64, 0, 0, 0)
            visibility = if (P2PSyncService.isServerEnabledByUser) View.VISIBLE else View.GONE
        }
        fightButton = ImageView(this).apply {
            setPadding(0, 0, 16, 0)
            setOnClickListener { if (syncManager.isFightOngoing) syncManager.endFight() else syncManager.requestFight() }
        }
        val dp150 = (50 * resources.displayMetrics.density).toInt()
        val dp100 = (35 * resources.displayMetrics.density).toInt()
        fightButton.layoutParams = LinearLayout.LayoutParams(dp150, dp100)
        syncInfoRow.addView(fightButton)
        connectionCountTv = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.CYAN)
            text = "Connections: ${P2PSyncService.activeConnections}"
        }
        syncInfoRow.addView(connectionCountTv)
        mainContainer.addView(syncInfoRow)
        teamContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        ViewCompat.setOnApplyWindowInsetsListener(teamContainer) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<LinearLayout.LayoutParams> { topMargin = (insets.top + 32).coerceAtLeast(0) }
            windowInsets
        }
        mainContainer.addView(teamContainer)
        addRemoveButton = Button(this)
        mainContainer.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_HORIZONTAL; addView(addRemoveButton) })
        mainContainer.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, 64) })
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
        mainContainer.addView(diceContainer)
        val imageEvoLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600)
        }
        imageView = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            layoutParams = LinearLayout.LayoutParams(600, 600)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        imageEvoLayout.addView(imageView)
        mainContainer.addView(imageEvoLayout)
        preEvolutionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                marginStart = 50
            }
        }
        evolutionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = 50
            }
        }
        rootLayout.addView(preEvolutionsContainer)
        rootLayout.addView(evolutionsContainer)
        val pokedexRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 32
                bottomMargin = 32
            }
        }
        statusFieldContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        pokedexRow.addView(statusFieldContainer)
        pokedexButton = Button(this).apply {
            text = "Pokédex"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = 16
                rightMargin = 16
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
        pokedexRow.addView(pokedexButton)
        fieldEffectsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        pokedexRow.addView(fieldEffectsContainer)
        mainContainer.addView(pokedexRow)
        movesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        mainContainer.addView(movesLayout)
        textView = TextView(this).apply {
            textSize = 20f
            gravity = Gravity.CENTER
        }
        movesLayout.addView(textView)
        mainContainer.addView(centerContainer)
        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(64, 0, 64, 128) }
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
        enemyStatusContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = 8 }
        }
        enemyInfoContainer.addView(enemyStatusContainer)
        enemyTypesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = 8 }
        }
        enemyInfoContainer.addView(enemyTypesContainer)
        enemySpriteView = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(120, 120) }
        enemyInfoContainer.addView(enemySpriteView)
        clearEnemyButton = ImageView(this).apply {
            try { setImageBitmap(BitmapFactory.decodeStream(assets.open("trash.png"))) } catch (e: Exception) {}
            layoutParams = LinearLayout.LayoutParams(100, 100).apply { leftMargin = 8 }
            visibility = View.GONE
            setOnClickListener { viewModel.clearEnemy(); viewModel.setUpdateUI() }
        }
        enemyInfoContainer.addView(clearEnemyButton)
        val switchToEnemyButton = Button(this).apply {
            text = "Switch to Enemy"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { viewModel.switchWithEnemy(); viewModel.setUpdateUI() }
        }
        enemyLayout.addView(enemyInfoContainer)
        enemyLayout.addView(switchToEnemyButton)
        buttonContainer.addView(enemyLayout)
        mainContainer.addView(buttonContainer)
        rootLayout.addView(mainContainer)
        setContentView(rootLayout)
        logging("finish stuff")
    }

    private fun updateFightButton() {
        runOnUiThread {
            if (!::fightButton.isInitialized) return@runOnUiThread
            if (P2PSyncService.activeConnections > 0) {
                fightButton.visibility = View.VISIBLE
                val assetName = if (syncManager.isFightOngoing) "run.png" else "fight.png"
                try {
                    fightButton.setImageBitmap(BitmapFactory.decodeStream(assets.open(assetName)))
                } catch (e: Exception) {
                    fightButton.setImageResource(if (syncManager.isFightOngoing) android.R.drawable.ic_menu_close_clear_cancel else android.R.drawable.ic_menu_add)
                }
            } else {
                fightButton.visibility = View.GONE
            }
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
            addRemoveButton.setOnClickListener { viewModel.removeFromTeam(); viewModel.setUpdateUI() }
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
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply { if (currentIndex == i) setMargins(8, 8, 8, 8) }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            slotContainer.addView(slotIv)
            val pokemon = team[i]
            if (isSelectingSlot) {
                slotIv.setBackgroundColor(if (pokemon == null) Color.GREEN else Color.YELLOW)
                slotIv.setOnClickListener { isSelectingSlot = false; viewModel.addToTeam(i); viewModel.setUpdateUI() }
                pokemon?.spriteBitmap?.let { slotIv.setImageBitmap(it) }
            } else if (pokemon != null) {
                slotIv.setBackgroundColor(Color.WHITE)
                if (enemy != null) {
                    val ownEff = moveRepository.getPokemonEffectiveness(pokemon, enemy)
                    if (ownEff == 1) addArrow(slotContainer, "arrow_green.png", Gravity.BOTTOM or Gravity.START)
                    if (ownEff == -1) addArrow(slotContainer, "arrow_red.png", Gravity.BOTTOM or Gravity.START)
                    val enemyEff = moveRepository.getPokemonEffectiveness(enemy, pokemon)
                    if (enemyEff == 1) addArrow(slotContainer, "arrow_red.png", Gravity.BOTTOM or Gravity.END)
                    if (enemyEff == -1) addArrow(slotContainer, "arrow_green.png", Gravity.BOTTOM or Gravity.END)
                }
                pokemon.spriteBitmap?.let {
                    slotIv.setImageBitmap(it)
                    slotIv.setOnClickListener { viewModel.setOwnPokemon(pokemon, i); viewModel.setUpdateUI() }
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
            val bitmap = getPokemonBitmap(url) ?: withContext(Dispatchers.IO) {
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
                imageView.setOnClickListener { viewModel.setOwnPokemon(pokemon, index); viewModel.setUpdateUI() }
            }
        }
    }

    private fun refreshMoves() {
        movesLayout.removeAllViews()
        val own = viewModel.ownPokemon.value ?: return
        if (own.hasTypelessMove()) {
            addMoveRow("Typeless")
        } else {
            addMoveRow(own.move1)
            addMoveRow(own.move2)
            own.move3?.let { addMoveRow(it, true) }
        }
        own.teraType?.let { addTeraRow(own) }
        own.typeEnhancerType?.let { addTypeEnhancerRow(own) }
        own.baseItem?.let { addBaseItemRow(own) }
    }

    private fun addMoveRow(moveName: String, isTM: Boolean = false) {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = 8; bottomMargin = 8 }
        }
        val result = moveRepository.calculateMovePower(
            moveName,
            viewModel.ownPokemon.value!!,
            viewModel.enemyPokemon.value,
            viewModel.ownWeather.value,
            viewModel.enemyWeather.value, viewModel.enemyUsesProtect) ?: return
        if (prefs.getBoolean("show_speakers", false)) {
            val speakerIv = ImageView(this).apply {
                try { setImageBitmap(BitmapFactory.decodeStream(assets.open("speaker.png"))) } catch (e: Exception) {}
                layoutParams = LinearLayout.LayoutParams(100, 100).apply { rightMargin = 16 }
                setPadding(8, 8, 8, 8)
                setOnClickListener {
                    val lang = prefs.getString("language", "en") ?: "en"
                    speakOut(if (lang == "en") result.moveData.englishName ?: "Unknown" else result.moveData.germanName ?: "Unbekannt")
                }
            }
            row.addView(speakerIv)
        }
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
        val moveTextView = TextView(this).apply {
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
        }
        moveTextView.textSize = 20f
        moveTextView.text = uiMapper.formatMoveText(
            result, moveTextView, prefs.getString("language", "en") ?: "en",
            viewModel.ownPokemon.value, viewModel.enemyPokemon.value,
            viewModel.ownWeather.value, viewModel.enemyWeather.value,
            pokedexRepository, moveRepository
        ) { effectName, view, path -> showDetailPopup(effectName, view, path) }
        row.addView(moveTextView)
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
                setOnClickListener { viewModel.ownPokemon.value?.move3 = null; refreshMoves(); viewModel.saveTeamData() }
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
            setOnClickListener { pokemon.isTeraActivated = !pokemon.isTeraActivated; viewModel.setUpdateUI(); viewModel.saveTeamData() }
        }
        row.addView(teraIv)
        addDeleteButton(row) { pokemon.teraType = null; pokemon.isTeraActivated = false; refreshMoves(); viewModel.saveTeamData() }
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
        addDeleteButton(row) { pokemon.typeEnhancerType = null; refreshMoves(); viewModel.saveTeamData() }
        movesLayout.addView(row)
    }

    private fun addBaseItemRow(pokemon: PokemonInfo) {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 16, 0, 16) }
        }
        val iv = ImageView(this).apply {
            try { setImageBitmap(BitmapFactory.decodeStream(assets.open("base_items/${pokemon.baseItem}.png"))) } catch (e: Exception) {}
            layoutParams = LinearLayout.LayoutParams(150, 150)
            colorFilter = if (!pokemon.isBaseItemActivated) ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) else null
            setOnClickListener {
                if (pokemon.baseItem in scanHandler.getToggleAbleItems()) pokemon.isBaseItemActivated = !pokemon.isBaseItemActivated
                if (pokemon.baseItem == "Mega") {
                    val mega = pokedexRepository.hasMegaEvolution(pokemon.id)
                    if (pokemon.isBaseItemActivated) {
                        pokemon.isBaseItemActivated = false
                        if (mega != null && !pokemon.isDynaActivated && !pokemon.isGigaDynaActivated) evolvePokemon(mega, 0, "mega")
                    } else if (pokedexRepository.isMega(pokemon.id)) {
                        viewModel.lastSelectedIndex?.let { idx -> viewModel.setOwnPokemon(viewModel.teamPokemon.value[idx], idx) }
                        viewModel.setUpdateUI()
                    }
                } else {
                    refreshMoves(); viewModel.saveTeamData(); syncManager.syncViaP2P()
                }
            }
        }
        row.addView(iv)
        addDeleteButton(row) { pokemon.baseItem = null; pokemon.isBaseItemActivated = false; refreshMoves(); viewModel.saveTeamData() }
        movesLayout.addView(row)
    }

    private fun addDeleteButton(row: LinearLayout, onClick: () -> Unit) {
        val deleteIv = ImageView(this).apply {
            try { setImageBitmap(BitmapFactory.decodeStream(assets.open("trash.png"))) } catch (e: Exception) {}
            layoutParams = LinearLayout.LayoutParams(80, 80).apply { leftMargin = 32 }
            setOnClickListener { onClick(); syncManager.syncViaP2P() }
        }
        row.addView(deleteIv)
    }

    private fun showDice(all: Boolean) {
        diceContainer.removeAllViews()
        val own = viewModel.ownPokemon.value ?: return
        if (all) {
            for (i in 0..6) {
                val diceIv = ImageView(this).apply {
                    try { setImageBitmap(BitmapFactory.decodeStream(assets.open("blued6_$i.png"))) } catch (e: Exception) {}
                    layoutParams = LinearLayout.LayoutParams(100, 100).apply { setMargins(8, 0, 8, 0) }
                    setOnClickListener { own.additionalLevel = i; showDice(false); refreshMoves(); viewModel.saveTeamData(); syncManager.syncViaP2P() }
                }
                diceContainer.addView(diceIv)
            }
        } else {
            val wrapper = FrameLayout(this).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
            val diceIv = ImageView(this).apply {
                try { setImageBitmap(BitmapFactory.decodeStream(assets.open("blued6_${own.additionalLevel}.png"))) } catch (e: Exception) {}
                layoutParams = FrameLayout.LayoutParams(150, 150).apply { gravity = Gravity.CENTER }
                setOnClickListener { showDice(true) }
            }
            wrapper.addView(diceIv)
            if (own.isDynaAvailable && !own.isGigaDynaActivated && !pokedexRepository.isMega(own.id)) {
                val dynaIv = ImageView(this).apply {
                    try { setImageBitmap(BitmapFactory.decodeStream(assets.open("G-Max Ball.png"))) } catch (e: Exception) {}
                    layoutParams = FrameLayout.LayoutParams(120, 120).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; rightMargin = 64 }
                    if (!own.isDynaActivated) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                    setOnClickListener { own.isDynaActivated = !own.isDynaActivated; viewModel.saveTeamData(); viewModel.setUpdateUI() }
                }
                wrapper.addView(dynaIv)
            }
            if (own.isDynaAvailable && !own.isDynaActivated) {
                val gigaDyna = pokedexRepository.hasGMaxEvolution(own.id)
                if (gigaDyna != null || own.isGigaDynaActivated) {
                    val dynaIv = ImageView(this).apply {
                        try { setImageBitmap(BitmapFactory.decodeStream(assets.open("G-Max Symbol.png"))) } catch (e: Exception) {}
                        layoutParams = FrameLayout.LayoutParams(120, 120).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; rightMargin = 64 + 120 }
                        if (!own.isGigaDynaActivated) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                        setOnClickListener {
                            if (gigaDyna != null) evolvePokemon(gigaDyna, 0, "gmax")
                            else viewModel.lastSelectedIndex?.let { idx -> viewModel.setOwnPokemon(viewModel.teamPokemon.value[idx], idx); viewModel.setUpdateUI() }
                        }
                    }
                    wrapper.addView(dynaIv)
                }
            }
            diceContainer.addView(wrapper)
        }
    }

    private fun updateStatusFieldIcons() {
        statusFieldContainer.removeAllViews()
        val own = viewModel.ownPokemon.value ?: return
        own.statusCondition?.let { status ->
            if (status.isNotEmpty()) {
                val path = "status_icons/$status.png"
                val statusIv = ImageView(this).apply {
                    try { setImageBitmap(BitmapFactory.decodeStream(assets.open(path))) } catch (e: Exception) {}
                    layoutParams = LinearLayout.LayoutParams(100, 100)
                    setOnClickListener { showDetailPopup(status, this, path) }
                }
                statusFieldContainer.addView(statusIv)
                val trashIv = ImageView(this).apply {
                    try { setImageBitmap(BitmapFactory.decodeStream(assets.open("trash.png"))) } catch (e: Exception) {}
                    layoutParams = LinearLayout.LayoutParams(60, 60).apply { leftMargin = 4; rightMargin = 16 }
                    setOnClickListener { own.statusCondition = null; viewModel.saveTeamData(); viewModel.setUpdateUI() }
                }
                statusFieldContainer.addView(trashIv)
            }
        }
    }

    private fun updateFieldIcons() {
        fieldEffectsContainer.removeAllViews()
        viewModel.ownWeather.value?.let { weather ->
            val path = "Field/$weather.png"
            val effectName = "{WEATHER} $weather"
            val weatherIv = ImageView(this).apply {
                try { setImageBitmap(BitmapFactory.decodeStream(assets.open(path))) } catch (e: Exception) {}
                layoutParams = LinearLayout.LayoutParams(100, 100)
                setOnClickListener { showDetailPopup(effectName, this, path) }
            }
            fieldEffectsContainer.addView(weatherIv)
            val trashIv = ImageView(this).apply {
                try { setImageBitmap(BitmapFactory.decodeStream(assets.open("trash.png"))) } catch (e: Exception) {}
                layoutParams = LinearLayout.LayoutParams(60, 60).apply { leftMargin = 4; rightMargin = 16 }
                setOnClickListener { viewModel.setOwnWeather(null); viewModel.setUpdateUI() }
            }
            fieldEffectsContainer.addView(trashIv)
        }
    }

    private fun updateEnemySprite(spriteUrl: String) {
        if (spriteUrl.isEmpty()) {
            enemySpriteView.setImageDrawable(null)
            clearEnemyButton.visibility = View.GONE
            enemyTypesContainer.removeAllViews()
            enemyStatusContainer.removeAllViews()
            return
        }
        enemyStatusContainer.removeAllViews()
        val enemy = viewModel.enemyPokemon.value
        val own = viewModel.ownPokemon.value
        if (enemy != null && own != null && moveRepository.hasProtection(enemy, own, viewModel.enemyWeather.value, viewModel.ownWeather.value, pokedexRepository)) {
            val protIv = ImageView(this).apply {
                try { setImageBitmap(BitmapFactory.decodeStream(assets.open("move_symbols/Black/Protection 1.png"))) } catch (e: Exception) {}
                layoutParams = LinearLayout.LayoutParams(120, 120).apply { bottomMargin = 8 }
                alpha = if (viewModel.enemyUsesProtect) 1.0f else 0.3f
                setOnClickListener { viewModel.enemyUsesProtect = !viewModel.enemyUsesProtect; viewModel.setUpdateUI() }
            }
            enemyStatusContainer.addView(protIv)
        } else { viewModel.enemyUsesProtect = false }

        viewModel.enemyWeather.value?.let { weather ->
            val path = "Field/$weather.png"
            val weatherIv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(80, 80).apply { bottomMargin = 8 }
                try { setImageBitmap(BitmapFactory.decodeStream(assets.open(path))) } catch (e: Exception) {}
                setOnClickListener { showDetailPopup("{WEATHER} $weather", this, path) }
            }
            enemyStatusContainer.addView(weatherIv, 0)
        }
        enemy?.statusCondition?.let { status ->
            if (status.isNotEmpty()) {
                val path = "status_icons/$status.png"
                val statusIv = ImageView(this).apply {
                    try { setImageBitmap(BitmapFactory.decodeStream(assets.open(path))) } catch (e: Exception) {}
                    layoutParams = LinearLayout.LayoutParams(80, 80)
                    setOnClickListener { showDetailPopup(status, this, path) }
                }
                enemyStatusContainer.addView(statusIv)
            }
        }
        uiMapper.updateEnemyTypeIcons(enemy, enemyTypesContainer)
        lifecycleScope.launch {
            (getPokemonBitmap(spriteUrl) ?: withContext(Dispatchers.IO) {
                try {
                    val b = BitmapFactory.decodeStream(URL(spriteUrl).openStream())
                    if (b != null) saveBitmapToCache(spriteUrl, b)
                    b
                } catch (e: Exception) { null }
            })?.let { enemySpriteView.setImageBitmap(it); clearEnemyButton.visibility = View.VISIBLE }
        }
    }

    private fun get_pokedex(number: String, spriteUrl: String, artUrl: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val info = pokedexRepository.findPokemonByNumber(number, spriteUrl, artUrl)
            withContext(Dispatchers.Main) {
                if (info != null) {
                    val nextIndex = if (info.id == viewModel.lastPokemonId) viewModel.lastSelectedIndex else null
                    viewModel.setOwnPokemon(if (nextIndex != null) viewModel.teamPokemon.value[nextIndex] else info, nextIndex)
                    viewModel.setUpdateUI()
                } else { textView.text = "Error reading Pokédex" }
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
            (getPokemonBitmap(artUrl) ?: withContext(Dispatchers.IO) {
                try {
                    val b = BitmapFactory.decodeStream(URL(artUrl).openStream())
                    if (b != null) saveBitmapToCache(artUrl, b)
                    b
                } catch (e: Exception) { null }
            })?.let { imageView.setImageBitmap(it) }
            (getPokemonBitmap(spriteUrl) ?: withContext(Dispatchers.IO) {
                try {
                    val b = BitmapFactory.decodeStream(URL(spriteUrl).openStream())
                    if (b != null) saveBitmapToCache(spriteUrl, b)
                    b
                } catch (e: Exception) { null }
            })?.let { viewModel.ownPokemon.value?.let { p -> p.spriteBitmap = it; p.spriteBase64 = bitmapToBase64(it); viewModel.saveTeamData(); viewModel.setUpdateUINoSync() } }
        }
    }

    private fun updateEvolutionViews() {
        val own = viewModel.ownPokemon.value
        if (own == null || own.isTrainerPokemon) { evolutionsContainer.removeAllViews(); preEvolutionsContainer.removeAllViews(); return }
        lifecycleScope.launch(Dispatchers.IO) {
            val (evos, preEvos) = pokedexRepository.getEvolutions(own.id)
            withContext(Dispatchers.Main) {
                evolutionsContainer.removeAllViews(); preEvolutionsContainer.removeAllViews()
                evos.forEach { addEvoSprite(it, evolutionsContainer) }
                preEvos.forEach { addEvoSprite(it, preEvolutionsContainer) }
            }
        }
    }

    private fun addEvoSprite(number: String, container: LinearLayout) {
        val spriteUrl = "https://www.serebii.net/pokedex-sv/icon/$number.png"
        val iv = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(120, 120); setPadding(8, 8, 8, 8) }
        container.addView(iv)
        lifecycleScope.launch {
            (getPokemonBitmap(spriteUrl) ?: withContext(Dispatchers.IO) {
                try {
                    val b = BitmapFactory.decodeStream(URL(spriteUrl).openStream())
                    if (b != null) saveBitmapToCache(spriteUrl, b)
                    b
                } catch (e: Exception) { null }
            })?.let { iv.setImageBitmap(it); iv.setOnClickListener { get_pokedex(number, spriteUrl, "https://www.serebii.net/pokemon/art/$number.png") } }
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val os = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
        return Base64.encodeToString(os.toByteArray(), Base64.DEFAULT)
    }

    private fun getCacheFile(url: String): File {
        val dir = File(filesDir, "pokemon_images")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP))
    }

    private fun saveBitmapToCache(url: String, bitmap: Bitmap) {
        try { FileOutputStream(getCacheFile(url)).use { it -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (e: Exception) {}
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val lang = getSharedPreferences("settings", Context.MODE_PRIVATE).getString("language", "en") ?: "en"
            tts?.setLanguage(if (lang == "de") Locale.GERMAN else Locale.ENGLISH)
            isTtsReady = true
            pendingTTS?.let { speakOut(it); pendingTTS = null }
        }
    }

    private fun showDetailPopup(key: String, anchorView: View, imagePath: String? = null) {
        val description = detailsRepository.getDetails(key) ?: arrayOf(key, "unknown data")
        val popupView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(android.R.drawable.toast_frame)
            background.setTint(Color.parseColor("#EE333333"))
            setPadding(32, 24, 32, 24)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val headLineView = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        val titleTv = TextView(this).apply { text = description[0]; textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD); setPadding(16, 0, 0, 8) }
        if (imagePath != null) {
            val iv = ImageView(this).apply { layoutParams = LinearLayout.LayoutParams(120, 60).apply { bottomMargin = 4 } }
            try { iv.setImageBitmap(BitmapFactory.decodeStream(assets.open(imagePath))) } catch (e: Exception) {}
            headLineView.addView(iv)
        }
        headLineView.addView(titleTv)
        popupView.addView(headLineView)
        popupView.addView(TextView(this).apply { text = description[1]; textSize = 16f; setTextColor(Color.LTGRAY); maxWidth = 800; setPadding(0, 0, 0, 0) })
        PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply { elevation = 10f; animationStyle = android.R.style.Animation_Dialog }.showAsDropDown(anchorView, 0, 10)
    }

    private fun getPokemonBitmap(url: String): Bitmap? {
        val id = url.substringAfterLast("/").replace(".png", "")
        val folders = if (url.contains("icon")) listOf("sprites") else listOf("art")
        for (folder in folders) {
            try { assets.open("$folder/$id.png").use { return BitmapFactory.decodeStream(it) } } catch (e: Exception) {}
        }
        val file = getCacheFile(url)
        return if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    private fun speakOut(text: String) {
        if (isTtsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "") else pendingTTS = text
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown()
        P2PSyncService.removeStatusListener(statusListener)
        P2PSyncService.stopService()
        super.onDestroy()
    }
}
