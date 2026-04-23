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

    private var tts: TextToSpeech? = null
    private var isTtsReady = false
    private var pendingTTS: String? = null

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
        viewModel.loadTeamData()
        
        scanHandler = ScanHandler(this, viewModel, pokedexRepository, moveRepository, trainerRepository)
        syncManager = SyncManager(
            context = this,
            viewModel = viewModel,
            onFightStarted = { runOnUiThread { viewManager.updateFightButton() }; syncManager.syncViaP2P() },
            onFightEnded = { runOnUiThread { viewManager.updateFightButton(); viewModel.setEnemyPokemon(null); viewModel.setUpdateUI() } },
            onSyncReceived = { pokemon, weather ->
                viewModel.setEnemyPokemon(pokemon)
                viewModel.setEnemyWeather(weather)
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
                uiMapper.updatePokemonImage(pokemon, viewManager.imageView, android.R.drawable.ic_menu_camera)
                pokemon?.let { downloadImage(it.artUrl.ifEmpty { "https://www.serebii.net/pokemon/art/${it.id}.png" }, it.spriteUrl) }
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

    fun evolvePokemon(id: String, levelDiff: Int = 0, source: String = "lvl") {
        val old = viewModel.ownPokemon.value ?: return
        pokedexRepository.findPokemonByNumber(id, "https://www.serebii.net/pokedex-sv/icon/$id.png", "https://www.serebii.net/pokemon/art/$id.png")?.let { next ->
            next.copyStateFrom(old)
            next.additionalLevel += levelDiff
            if (source == "mega") next.isBaseItemActivated = true
            if (source == "gmax") next.isGigaDynaActivated = true
            viewModel.setOwnPokemon(next)
            viewModel.setUpdateUI()
        }
    }

    fun get_pokedex(number: String, spriteUrl: String, artUrl: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            pokedexRepository.findPokemonByNumber(number, spriteUrl, artUrl)?.let { info ->
                withContext(Dispatchers.Main) {
                    val nextIndex = if (info.id == viewModel.lastPokemonId) viewModel.lastSelectedIndex else null
                    viewModel.setOwnPokemon(if (nextIndex != null) viewModel.teamPokemon.value[nextIndex] else info, nextIndex)
                    viewModel.setUpdateUI()
                }
            } ?: run { withContext(Dispatchers.Main) { Toast.makeText(this@ResultActivity, "Error reading Pokédex", Toast.LENGTH_SHORT).show() } }
        }
    }

    private fun downloadImage(artUrl: String, spriteUrl: String) {
        lifecycleScope.launch {
            (getPokemonBitmap(artUrl) ?: withContext(Dispatchers.IO) {
                try {
                    val b = BitmapFactory.decodeStream(java.net.URL(artUrl).openStream())
                    if (b != null) saveBitmapToCache(artUrl, b)
                    b
                } catch (e: Exception) { null }
            })?.let { viewManager.imageView.setImageBitmap(it) }
            (getPokemonBitmap(spriteUrl) ?: withContext(Dispatchers.IO) {
                try {
                    val b = BitmapFactory.decodeStream(java.net.URL(spriteUrl).openStream())
                    if (b != null) saveBitmapToCache(spriteUrl, b)
                    b
                } catch (e: Exception) { null }
            })?.let { b -> viewModel.ownPokemon.value?.let { p -> p.spriteBitmap = b; p.spriteBase64 = bitmapToBase64(b); viewModel.saveTeamData(); viewModel.setUpdateUINoSync() } }
        }
    }

    fun getPokemonBitmap(url: String): Bitmap? {
        val id = url.substringAfterLast("/").replace(".png", "")
        val folders = if (url.contains("icon")) listOf("sprites") else listOf("art")
        for (f in folders) {
            try { assets.open("$f/$id.png").use { return BitmapFactory.decodeStream(it) } } catch (e: Exception) {}
        }
        val file = getCacheFile(url)
        return if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    fun saveBitmapToCache(url: String, bitmap: Bitmap) {
        try { FileOutputStream(getCacheFile(url)).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (e: Exception) {}
    }

    private fun getCacheFile(url: String): File {
        val dir = File(filesDir, "pokemon_images").apply { if (!exists()) mkdirs() }
        return File(dir, Base64.encodeToString(url.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP))
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val os = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
        return Base64.encodeToString(os.toByteArray(), Base64.DEFAULT)
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
