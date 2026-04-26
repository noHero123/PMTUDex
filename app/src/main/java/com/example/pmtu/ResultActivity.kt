package com.example.pmtu

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

class ResultActivity : AppCompatActivity(), TextToSpeech.OnInitListener {
    val viewModel: ResultViewModel by viewModels()
    lateinit var pokedexRepository: PokedexRepository
    lateinit var moveRepository: MoveRepository
    lateinit var trainerRepository: TrainerRepository
    lateinit var scanHandler: ScanHandler
    lateinit var uiMapper: PokemonUiMapper
    lateinit var detailsRepository: DetailsRepository
    lateinit var syncManager: SyncManager
    lateinit var viewManager: PokemonViewManager
    lateinit var imageManager: ImageManager
    lateinit var evolutionHandler: EvolutionHandler

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingTTS: String? = null
    private var scanJob: Job? = null
    private var downloadJob: Job? = null

    private val statusListener = { status: P2PSyncService.Status, _: String? ->
        runOnUiThread {
            if (::viewManager.isInitialized) {
                viewManager.syncInfoRow.visibility = if (P2PSyncService.isServerEnabledByUser) View.VISIBLE else View.GONE
                viewManager.connectionCountTv.text = "Connections: ${P2PSyncService.activeConnections}"
                viewManager.updateFightButton()
            }
        }
        if (status == P2PSyncService.Status.CONNECTED) {
            lifecycleScope.launch { syncManager.syncViaP2P() }
        }
    }

    private val pokemonScannerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringExtra("SCANNED_TEXT")?.let { processScanResult(it) }
        }
    }

    private val teamBrowserLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getParcelableExtra<SavedTeam>("SELECTED_TEAM")?.let { team ->
                viewModel.setTeam(team.pokemon)
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
        uiMapper = PokemonUiMapper(this)
        detailsRepository = DetailsRepository(this)
        imageManager = ImageManager(this)
        evolutionHandler = EvolutionHandler(viewModel, pokedexRepository)
        
        viewModel.loadTeamData()
        
        scanHandler = ScanHandler(this, viewModel, pokedexRepository, moveRepository, trainerRepository)
        syncManager = SyncManager(
            context = this,
            viewModel = viewModel,
            onFightStarted = { runOnUiThread { if (::viewManager.isInitialized) viewManager.updateFightButton() }; syncManager.syncViaP2P() },
            onFightEnded = { runOnUiThread { if (::viewManager.isInitialized) { viewManager.updateFightButton(); viewModel.setEnemyPokemon(null); viewModel.setUpdateUI() } } },
            onSyncReceived = { pokemon, weather ->
                viewModel.setEnemyPokemon(pokemon)
                viewModel.setEnemyWeather(weather)
                viewModel.lastEnemySelectedIndex = null
                viewModel.setUpdateUINoSync()
                P2PSyncService.sendOK()
            }
        )

        viewManager = PokemonViewManager(
            activity = this,
            viewModel = viewModel,
            pokedexRepository = pokedexRepository,
            moveRepository = moveRepository,
            scanHandler = scanHandler,
            uiMapper = uiMapper,
            detailsRepository = detailsRepository,
            syncManager = syncManager,
            evolutionHandler = evolutionHandler,
            imageManager = imageManager,
            onNewScanRequested = { pokemonScannerLauncher.launch(Intent(this, MainActivity::class.java)) },
            onSettingsRequested = { teamBrowserLauncher.launch(Intent(this, SettingsActivity::class.java)) }
        )

        setContentView(viewManager.setupUI())
        setupWindow()
        P2PSyncService.addStatusListener(statusListener)
        observeViewModel()

        tts = TextToSpeech(this, this)
        intent.getStringExtra("SCANNED_TEXT")?.let { processScanResult(it) }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val currentLang = prefs.getString("language", "en") ?: "en"
        tts?.setLanguage(if (currentLang == "de") Locale.GERMAN else Locale.ENGLISH)
        viewModel.checkLanguageAndReset(currentLang, pokedexRepository)
        setupWindow()
        if (P2PSyncService.connectionStatus != P2PSyncService.Status.CONNECTED && P2PSyncService.isServerEnabledByUser && !P2PSyncService.isPending()) {
            P2PSyncService.reconnect()
        }
        viewModel.setUpdateUI()
        if (::viewManager.isInitialized) {
            viewManager.syncInfoRow.visibility = if (P2PSyncService.isServerEnabledByUser) View.VISIBLE else View.GONE
            viewManager.connectionCountTv.text = "Connections: ${P2PSyncService.activeConnections}"
            viewManager.updateFightButton()
        }
    }

    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            viewModel.updateUI.collectLatest {
                viewManager.updateEnemySprite(viewModel.enemyPokemon.value?.spriteUrl ?: "")
                viewManager.refreshUI()
                syncManager.syncViaP2P()
            }
        }
        lifecycleScope.launch {
            viewModel.updateUINoSync.collectLatest {
                viewManager.updateEnemySprite(viewModel.enemyPokemon.value?.spriteUrl ?: "")
                viewManager.refreshUI()
            }
        }
        lifecycleScope.launch {
            viewModel.ownPokemon.collectLatest { pokemon ->
                if (pokemon == null) {
                    viewManager.imageView.setImageBitmap(imageManager.getDefaultImage())
                } else {
                    val artUrl = pokemon.artUrl.ifEmpty { "https://www.serebii.net/pokemon/art/${pokemon.id}.png" }
                    val artBitmap = imageManager.getPokemonBitmap(artUrl)
                    if (artBitmap != null) {
                        viewManager.imageView.setImageBitmap(artBitmap)
                        downloadSpriteOnly(pokemon)
                    } else {
                        // Fallback to placeholder then download
                        uiMapper.updatePokemonImage(pokemon, viewManager.imageView, imageManager.getDefaultImage())
                        downloadAllImages(pokemon)
                    }
                }
            }
        }
    }

    private fun processScanResult(scannedText: String) {
        when (val result = scanHandler.handleScan(scannedText, this)) {
            is ScanHandler.ScanResult.Connect -> {
                P2PSyncService.startClient(result.ip)
                Toast.makeText(this, "Connecting to Peer at ${result.ip}...", Toast.LENGTH_SHORT).show()
            }
            is ScanHandler.ScanResult.Pokemon -> {
                get_pokedex(result.number, "https://www.serebii.net/pokedex-sv/icon/${result.number}.png", "https://www.serebii.net/pokemon/art/${result.number}.png")
            }
            else -> {}
        }
    }

    fun get_pokedex(number: String, spriteUrl: String, artUrl: String) {
        scanJob?.cancel()
        scanJob = lifecycleScope.launch(Dispatchers.IO) {
            pokedexRepository.findPokemonByNumber(number, spriteUrl, artUrl)?.let { info ->
                withContext(Dispatchers.Main) {
                    val nextIndex = if (info.id == viewModel.lastPokemonId) viewModel.lastSelectedIndex else null
                    viewModel.setOwnPokemon(if (nextIndex != null) viewModel.teamPokemon.value[nextIndex] else info, nextIndex)
                    viewModel.setUpdateUI()
                }
            } ?: run { withContext(Dispatchers.Main) { Toast.makeText(this@ResultActivity, "Error reading Pokédex", Toast.LENGTH_SHORT).show() } }
        }
    }

    private fun downloadSpriteOnly(pokemon: PokemonInfo) {
        downloadJob?.cancel()
        downloadJob = lifecycleScope.launch {
            imageManager.downloadImage(pokemon.spriteUrl)?.let { b ->
                pokemon.spriteBitmap = b
                pokemon.spriteBase64 = imageManager.bitmapToBase64(b)
                viewModel.saveTeamData()
                viewModel.setUpdateUINoSync()
            }
        }
    }

    private fun downloadAllImages(pokemon: PokemonInfo) {
        downloadJob?.cancel()
        downloadJob = lifecycleScope.launch {
            val artUrl = pokemon.artUrl.ifEmpty { "https://www.serebii.net/pokemon/art/${pokemon.id}.png" }
            imageManager.downloadImage(artUrl)?.let { viewManager.imageView.setImageBitmap(it) }
            imageManager.downloadImage(pokemon.spriteUrl)?.let { b ->
                pokemon.spriteBitmap = b
                pokemon.spriteBase64 = imageManager.bitmapToBase64(b)
                viewModel.saveTeamData()
                viewModel.setUpdateUINoSync()
            }
        }
    }

    fun speakOut(text: String) {
        if (isTtsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "") else pendingTTS = text
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val lang = getSharedPreferences("settings", Context.MODE_PRIVATE).getString("language", "en") ?: "en"
            tts?.setLanguage(if (lang == "de") Locale.GERMAN else Locale.ENGLISH)
            isTtsReady = true
            pendingTTS?.let { speakOut(it); pendingTTS = null }
        }
    }

    override fun onDestroy() {
        tts?.stop(); tts?.shutdown()
        P2PSyncService.removeStatusListener(statusListener)
        P2PSyncService.stopService()
        super.onDestroy()
    }
}
