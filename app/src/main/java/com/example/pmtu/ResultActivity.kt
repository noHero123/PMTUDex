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
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
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
    private var currentLanguage: String? = "en"
    private var currentDisableSpeakers: Boolean? = false
    
    private var isSelectingSlot = false
    private var fightOpponentIp: String? = null
    private var isFightOngoing = false
    private var fightInvitationDialog: AlertDialog? = null

    private val viewModel: ResultViewModel by viewModels()
    private lateinit var pokedexRepository: PokedexRepository
    private lateinit var moveRepository: MoveRepository
    private lateinit var trainerRepository: TrainerRepository
    private lateinit var scanHandler: ScanHandler
    private lateinit var uiMapper: PokemonUiMapper

    private val detailsMap = mutableMapOf<String, Array<String>>()

    private fun logging(message: String) {Log.d("ResultActivity", message)
    }
    private fun loadDetailsFromCsv() {
        try {assets.open("details.csv").bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val parts = line.split(",", limit = 3)
                if (parts.size == 3) {
                    // key to lowercase for case-insensitive lookup
                    var detkey = parts[0].trim().lowercase()
                    detkey = detkey.replace("\"","")
                    val headline = parts[1].trim().trim('\"')
                    val text =  parts[2].trim().trim('\"')
                    detailsMap[detkey] = arrayOf(headline,text)
                }
            }
        }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
                logging("status listener " + status.toString())
                if (isFightOngoing && fightOpponentIp != null) {
                    syncViaP2P()
                }
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

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val currentLang = prefs.getString("language", "en") ?: "en"
        tts?.setLanguage(if (currentLang == "de") Locale.GERMAN else Locale.ENGLISH)
        viewModel.checkLanguageAndReset(currentLang, pokedexRepository)
        // 1. Re-hide system bars (sometimes they reappear after backgrounding)
        setupWindow()
        logging("onResume " + P2PSyncService.connectionStatus.toString() + " "+P2PSyncService.isServerEnabledByUser.toString())
        // 2. Check and restore connection
        if (P2PSyncService.connectionStatus != P2PSyncService.Status.CONNECTED && P2PSyncService.isServerEnabledByUser && !P2PSyncService.isPending()) {
            logging("connect to peer")
            // Try to reconnect to the last known Peer IP
            logging("try to reconnect")
            P2PSyncService.reconnect()
        }

        // 3. Force a UI refresh and sync to ensure data is up to date
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

        setupWindow()
        setupUI()
        setupP2PSync()
        observeViewModel()
        loadDetailsFromCsv()

        tts = TextToSpeech(this, this)

        intent.getStringExtra("SCANNED_TEXT")?.let { processScanResult(it) }
    }

    private fun setupWindow() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController?.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun setupP2PSync() {
        P2PSyncService.onDataReceived = { json, senderIp ->
            lifecycleScope.launch {
                try {
                    logging("get data from $senderIp...")
                    val data = Gson().fromJson(json, P2PSyncService.SyncData::class.java)
                    
                    when (data.type) {
                        "HANDSHAKE" -> {
                        }
                        "SYNC" -> {
                            if (isFightOngoing && P2PSyncService.isSameIp(senderIp, fightOpponentIp)) {
                                val receivedOwn = data.ownPokemonJson?.let { Gson().fromJson(it, PokemonInfo::class.java) }
                                viewModel.setEnemyPokemon(receivedOwn)
                                viewModel.setEnemyWeather(data.ownWeather)
                                viewModel.setUpdateUINoSync()
                                P2PSyncService.sendOK()
                            }
                        }
                        "FIGHT_REQUEST" -> {
                            if (!isFightOngoing) {
                                showFightInvitation(senderIp, data.sourceName ?: senderIp)
                            }
                        }
                        "FIGHT_JOIN_REQUEST" -> {
                            if (!isFightOngoing) {
                                Toast.makeText(this@ResultActivity, "${data.sourceName ?: senderIp} wants to fight!", Toast.LENGTH_SHORT).show()
                                acceptFight(senderIp)
                            }
                        }
                        "FIGHT_START" -> {
                            // AUTO-CLOSE logic: If anyone starts a fight, we close the invitation dialog
                            runOnUiThread {
                                fightInvitationDialog?.dismiss()
                                fightInvitationDialog = null
                            }

                            if (P2PSyncService.isSameIp(data.targetIp, P2PSyncService.localIp)) {
                                isFightOngoing = true
                                fightOpponentIp = data.sourceIp
                                syncViaP2P()
                                updateFightButton()
                            } else if (P2PSyncService.isSameIp(data.sourceIp, P2PSyncService.localIp)) {
                                isFightOngoing = true
                                fightOpponentIp = data.targetIp
                                updateFightButton()
                            }
                        }
                        "FIGHT_END" -> {
                            if (P2PSyncService.isSameIp(data.targetIp, P2PSyncService.localIp) || 
                                P2PSyncService.isSameIp(data.sourceIp, P2PSyncService.localIp)) {
                                resetFightState()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SYNC", "Error parsing received data", e)
                }
            }
        }
        P2PSyncService.onPeerDisconnected = { ip ->
            if (isFightOngoing && P2PSyncService.isSameIp(ip, fightOpponentIp)) {
                runOnUiThread {
                    Toast.makeText(this, "Opponent disconnected", Toast.LENGTH_SHORT).show()
                    resetFightState()
                }
            }
        }
        P2PSyncService.removeStatusListener(statusListener)
        P2PSyncService.addStatusListener(statusListener)
    }

    private fun showFightInvitation(senderIp: String, senderName: String) {
        runOnUiThread {
            // Dismiss any existing dialog first to be safe
            fightInvitationDialog?.dismiss()

            fightInvitationDialog = AlertDialog.Builder(this)
                .setTitle("Fight Request")
                .setMessage("$senderName wants to fight. Join?")
                .setPositiveButton("Yes") { _, _ ->
                    val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
                    val myName = prefs.getString("trainer_name", "Trainer")
                    P2PSyncService.sendDataToPeer(senderIp, P2PSyncService.SyncData(type = "FIGHT_JOIN_REQUEST", sourceName = myName))
                }
                .setNegativeButton("No", null)
                .setOnDismissListener { fightInvitationDialog = null } // Clear reference when closed
                .show()
        }
    }
    private fun acceptFight(opponentIp: String) {
        isFightOngoing = true
        fightOpponentIp = opponentIp
        P2PSyncService.broadcastData(P2PSyncService.SyncData(
            type = "FIGHT_START",
            targetIp = opponentIp,
            sourceIp = P2PSyncService.localIp
        ))
        updateFightButton()
        syncViaP2P()
    }

    private fun endFight() {
        P2PSyncService.broadcastData(P2PSyncService.SyncData(
            type = "FIGHT_END",
            sourceIp = P2PSyncService.localIp,
            targetIp = fightOpponentIp
        ))
        resetFightState()
    }

    private fun resetFightState() {
        runOnUiThread {
            isFightOngoing = false
            fightOpponentIp = null
            updateFightButton()
            viewModel.setEnemyPokemon(null)
            viewModel.setUpdateUI()
        }
    }

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.updateUI.collectLatest {
                        updateEnemySprite(viewModel.enemyPokemon.value?.spriteUrl ?: "")
                        refreshUI()
                        if (isFightOngoing && fightOpponentIp != null) {
                            syncViaP2P()
                        }
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

    private fun evolvePokemon(newPokemonID: String, levelDiff:Int = 0, source:String = "lvl") {
        val oldPoke = viewModel.ownPokemon.value
        val spriteUrl = "https://www.serebii.net/pokedex-sv/icon/${newPokemonID}.png"
        val artUrl = "https://www.serebii.net/pokemon/art/${newPokemonID}.png"
        val newPoke = pokedexRepository.findPokemonByNumber(newPokemonID, spriteUrl, artUrl)
        if (newPoke != null && oldPoke != null) {
            newPoke.copyStateFrom(oldPoke)
            newPoke.additionalLevel += levelDiff
            if (source == "mega")
            {
                newPoke.isBaseItemActivated = true
            }
            if(source == "gmax")
            {
                newPoke.isGigaDynaActivated = true
            }
        }

        viewModel.setOwnPokemon(newPoke)
        viewModel.setUpdateUI()
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

        syncInfoRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(64, 0, 0, 0)
            visibility = if (P2PSyncService.isServerEnabledByUser) View.VISIBLE else View.GONE
        }

        fightButton = ImageView(this).apply {

            //layoutParams = LinearLayout.LayoutParams(150, 100)
            setPadding(0, 0, 16, 0)
            setOnClickListener {
                if (isFightOngoing) {
                    endFight()
                } else {
                    requestFight()
                }
            }
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

        // Team
        teamContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        ViewCompat.setOnApplyWindowInsetsListener(teamContainer) { view, windowInsets ->            // Use systemBars to account for both the status bar and the notch
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<LinearLayout.LayoutParams> {
                // Subtract the offset from the top inset, but don't go below 0
                topMargin = (insets.top+32).coerceAtLeast(0)
            }
            windowInsets
        }
        mainContainer.addView(teamContainer)

        addRemoveButton = Button(this)
        val buttonWrapper = LinearLayout(this).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            addView(addRemoveButton)
        }
        mainContainer.addView(buttonWrapper)

        // ADDED: Small space between add button and dice
        mainContainer.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(1, 64)
        })

        // Level Dice
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
        //centerContainer.addView(diceContainer)
        mainContainer.addView(diceContainer)

        // 1. Change imageEvoLayout to only contain the central image
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
        //centerContainer.addView(imageEvoLayout)
        mainContainer.addView(imageEvoLayout)

        // 2. Define the side containers but DON'T add them to centerContainer
        // Instead, we set them up to be added to the rootLayout (the FrameLayout)
        preEvolutionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                marginStart = 50
            }
        }

        evolutionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = 50
            }
        }

        // 3. Add them to the rootLayout so they float independently of the vertical list
        rootLayout.addView(preEvolutionsContainer)
        rootLayout.addView(evolutionsContainer)

        // 1. Create the Row Container
        val pokedexRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = 32
            bottomMargin = 32
            }
        }

        // 2. Left Side: Status Icons (Burn, Paralyze, etc.)
        // We use weight 1.0f to push the button to the center
        statusFieldContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        pokedexRow.addView(statusFieldContainer)

        // 3. Center: Pokédex Button
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

        // 4. Right Side: Field Effects (Weather, Terrain, etc.)
        // New container specifically for the right side
        fieldEffectsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        pokedexRow.addView(fieldEffectsContainer)

        //centerContainer.addView(pokedexRow)
        mainContainer.addView(pokedexRow)

        movesLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        //centerContainer.addView(movesLayout)
        mainContainer.addView(movesLayout)

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
            }
        }
        enemyLayout.addView(enemyInfoContainer)
        enemyLayout.addView(switchToEnemyButton)
        buttonContainer.addView(enemyLayout)

        mainContainer.addView(buttonContainer)
        rootLayout.addView(mainContainer)
        setContentView(rootLayout)
        logging("finish stuff")
    }

    private fun requestFight() {
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        val name = prefs.getString("trainer_name", "Trainer")
        P2PSyncService.broadcastData(P2PSyncService.SyncData(type = "FIGHT_REQUEST", sourceName = name))
        Toast.makeText(this, "Fight request sent!", Toast.LENGTH_SHORT).show()
    }

    private fun updateFightButton() {
        runOnUiThread {
            if (!::fightButton.isInitialized) return@runOnUiThread
            if (P2PSyncService.activeConnections > 0) {
                fightButton.visibility = View.VISIBLE
                val assetName = if (isFightOngoing) "run.png" else "fight.png"
                try {
                    val inputStream = assets.open(assetName)
                    val bitmap = BitmapFactory.decodeStream(inputStream)
                    fightButton.setImageBitmap(bitmap)
                } catch (e: Exception) {
                    fightButton.setImageResource(if (isFightOngoing) android.R.drawable.ic_menu_close_clear_cancel else android.R.drawable.ic_menu_add)
                }
            } else {
                fightButton.visibility = View.GONE
            }
        }
    }

    private fun syncViaP2P() {
        if (!isFightOngoing || fightOpponentIp == null) return

        if (P2PSyncService.connectionStatus == P2PSyncService.Status.CONNECTED) {
            logging("sync to " + fightOpponentIp)
            P2PSyncService.sendDataToPeer(fightOpponentIp!!, P2PSyncService.SyncData(
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
                    val ownEffectiveness = moveRepository.getPokemonEffectiveness(pokemon, enemy)
                    if ( ownEffectiveness== 1) addArrow(slotContainer, "arrow_green.png", Gravity.BOTTOM or Gravity.START)
                    if ( ownEffectiveness== -1) addArrow(slotContainer, "arrow_red.png", Gravity.BOTTOM or Gravity.START)
                    val enemyEffectiveness = moveRepository.getPokemonEffectiveness(enemy, pokemon)
                    if (enemyEffectiveness == 1) addArrow(slotContainer, "arrow_red.png", Gravity.BOTTOM or Gravity.END)
                    if (enemyEffectiveness == -1) addArrow(slotContainer, "arrow_green.png", Gravity.BOTTOM or Gravity.END)
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
                imageView.setOnClickListener {
                    viewModel.setOwnPokemon(pokemon, index)
                    viewModel.setUpdateUI()}
            }
        }
    }

    private fun refreshMoves() {
        movesLayout.removeAllViews()
        val own = viewModel.ownPokemon.value ?: return
        if (own.hasTypelessMove()) {
            addMoveRow("Typeless")
        }
        else{
            addMoveRow(own.move1)
            addMoveRow(own.move2)
            own.move3?.let {
                addMoveRow(it, true)
            }
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 8
                bottomMargin = 8
            }
        }

        // Speaker
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
                    if (lang == "en") {
                        speakOut(result.moveData.englishName ?: "Unknown move")
                    }
                    if (lang == "de")
                        speakOut(result.moveData.germanName ?: "Unbekannte Attacke")
                }
            }
            row.addView(speakerIv)
        }


        
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

        val moveTextView = TextView(this).apply {
            // This line is mandatory for ClickableSpans to work!
            movementMethod = android.text.method.LinkMovementMethod.getInstance()

            // Optional: Prevent the background from turning a weird color when clicking the icon
            highlightColor = android.graphics.Color.TRANSPARENT
        }
        moveTextView.textSize = 20f
        moveTextView.text = uiMapper.formatMoveText(
            result,
            moveTextView,
            prefs.getString("language", "en") ?: "en",
            viewModel.ownPokemon.value,
            viewModel.enemyPokemon.value,
            viewModel.ownWeather.value,
            viewModel.enemyWeather.value,
            pokedexRepository,
            moveRepository
        ){ effectName, view, path ->
            showDetailPopup(effectName, view, path) // Your popup function
        }

        row.addView(moveTextView)

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
                viewModel.setUpdateUI()
                viewModel.saveTeamData()
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
                val toggleableItems = scanHandler.getToggleAbleItems()
                if (pokemon.baseItem in toggleableItems) {
                    pokemon.isBaseItemActivated = !pokemon.isBaseItemActivated
                }
                if (pokemon.baseItem == "Mega"){
                    // mega stone is touched
                    val megaEvolution = pokedexRepository.hasMegaEvolution(pokemon.id)
                    val isMegaActivated = pokemon.isBaseItemActivated
                    if(isMegaActivated) {
                        pokemon.isBaseItemActivated = false
                        if (megaEvolution != null && !pokemon.isDynaActivated && !pokemon.isGigaDynaActivated) {
                            evolvePokemon(megaEvolution, 0, "mega")
                        }
                    }
                    if(!isMegaActivated) {
                        if (pokedexRepository.isMega(pokemon.id)) {
                            val idx = viewModel.lastSelectedIndex
                            if (idx != null) {
                                val target = viewModel.teamPokemon.value[idx]
                                viewModel.setOwnPokemon(target, idx)
                            }
                            viewModel.setUpdateUI()
                        }
                    }

                }else {
                    //normal items
                    refreshMoves()
                    viewModel.saveTeamData()
                    if (isFightOngoing && fightOpponentIp != null) syncViaP2P()
                }
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
                if (isFightOngoing && fightOpponentIp != null) syncViaP2P()
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
                        if (isFightOngoing && fightOpponentIp != null) syncViaP2P()
                    }
                }
                diceContainer.addView(diceIv)
            }
        } else {
            //only show level Dice
            val wrapper = FrameLayout(this).apply {
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }

            val diceIv = ImageView(this).apply {
                try { setImageBitmap(BitmapFactory.decodeStream(assets.open("blued6_$level.png"))) } catch (e: Exception) {}
                layoutParams = FrameLayout.LayoutParams(150, 150).apply { gravity = Gravity.CENTER }
                setOnClickListener { showDice(true) }
            }
            wrapper.addView(diceIv)

            // DYNAMAX BALL
            if (own.isDynaAvailable && !own.isGigaDynaActivated && !pokedexRepository.isMega(own.id)) {
                val dynaIv = ImageView(this).apply {
                    try { 
                        val bit = BitmapFactory.decodeStream(assets.open("G-Max Ball.png"))
                        setImageBitmap(bit)
                    } catch (e: Exception) {}
                    layoutParams = FrameLayout.LayoutParams(120, 120).apply {
                        gravity = Gravity.CENTER_VERTICAL or Gravity.END
                        rightMargin = 64
                    }
                    if (!own.isDynaActivated) {
                        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                    }
                    setOnClickListener {
                        own.isDynaActivated = !own.isDynaActivated
                        viewModel.saveTeamData()
                        viewModel.setUpdateUI()
                    }
                }
                wrapper.addView(dynaIv)
            }
            //GIGA DYNAMAX
            if (own.isDynaAvailable && !own.isDynaActivated) {
                val gigaDyna = pokedexRepository.hasGMaxEvolution(own.id)
                if (gigaDyna != null || own.isGigaDynaActivated) {
                    val dynaIv = ImageView(this).apply {
                        try {
                            val bit = BitmapFactory.decodeStream(assets.open("G-Max Symbol.png"))
                            setImageBitmap(bit)
                        } catch (e: Exception) {
                        }
                        layoutParams = FrameLayout.LayoutParams(120, 120).apply {
                            gravity = Gravity.CENTER_VERTICAL or Gravity.END
                            rightMargin = 64+120
                        }
                        if (!own.isGigaDynaActivated) {
                            colorFilter =
                                ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                        }
                        setOnClickListener {
                            if(gigaDyna!=null)
                                evolvePokemon(gigaDyna, 0, "gmax")
                            else
                            {
                                val idx = viewModel.lastSelectedIndex
                                if (idx != null)
                                {
                                    val target = viewModel.teamPokemon.value[idx]
                                    viewModel.setOwnPokemon(target, idx)
                                }
                                viewModel.setUpdateUI()

                            }
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
        val own = viewModel.ownPokemon.value
        // Status Condition
        own?.statusCondition?.let { status ->
            if (status.isNotEmpty()) {
                val imagePath = "status_icons/$status.png"
                val statusIv = ImageView(this).apply {

                    try {
                        setImageBitmap(BitmapFactory.decodeStream(assets.open(imagePath)))
                    }
                    catch (e: Exception) {}
                    layoutParams = LinearLayout.LayoutParams(100, 100)
                    setOnClickListener { showDetailPopup(status, this, imagePath) }
                     }

                statusFieldContainer.addView(statusIv)
                val trashIv = ImageView(this).apply {
                    try { setImageBitmap(BitmapFactory.decodeStream(assets.open("trash.png"))) } catch (e: Exception) {}
                    layoutParams = LinearLayout.LayoutParams(60, 60).apply { leftMargin = 4; rightMargin = 16 }
                    setOnClickListener {
                        own.statusCondition = null
                        viewModel.saveTeamData()
                        viewModel.setUpdateUI()
                    }
                }
                statusFieldContainer.addView(trashIv)
            }
        }
    }

    private fun updateFieldIcons() {
        fieldEffectsContainer.removeAllViews()
        val own = viewModel.ownPokemon.value

        // Field Symbol

        viewModel.ownWeather.value?.let { weather ->
            val weatherImagePath = "Field/$weather.png"
            val weatherEffectDescriptionName = "{WEATHER} $weather"
            val weatherIv = ImageView(this).apply {
                try { setImageBitmap(BitmapFactory.decodeStream(assets.open(weatherImagePath))) } catch (e: Exception) {}
                layoutParams = LinearLayout.LayoutParams(100, 100)
                setOnClickListener { showDetailPopup(weatherEffectDescriptionName, this, weatherImagePath) }
            }
            fieldEffectsContainer.addView(weatherIv)
            val trashIv = ImageView(this).apply {
                try { setImageBitmap(BitmapFactory.decodeStream(assets.open("trash.png"))) } catch (e: Exception) {}
                layoutParams = LinearLayout.LayoutParams(60, 60).apply { leftMargin = 4; rightMargin = 16 }
                setOnClickListener {
                    viewModel.setOwnWeather(null)
                    viewModel.setUpdateUI()
                }
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

        //Protect button
        // Protection trigger:
        val enemy = viewModel.enemyPokemon.value
        val own = viewModel.ownPokemon.value

        var hasProtection = false
        if(enemy!=null && own!=null)
            hasProtection = moveRepository.hasProtection(enemy,own, viewModel.enemyWeather.value, viewModel.ownWeather.value, pokedexRepository  )

        if (hasProtection) {
            val protectionIv = ImageView(this).apply {
                try {
                    val bit = BitmapFactory.decodeStream(assets.open("move_symbols/Black/Protection 1.png"))
                    setImageBitmap(bit)
                } catch (e: Exception) {
                    // Fallback or log error if protection.png is missing
                }
                layoutParams = LinearLayout.LayoutParams(120, 120).apply { bottomMargin = 8 }
                alpha = if (viewModel.enemyUsesProtect) 1.0f else 0.3f
                setOnClickListener {
                    viewModel.enemyUsesProtect =  !viewModel.enemyUsesProtect
                    viewModel.setUpdateUI()
                }
            }
            enemyStatusContainer.addView(protectionIv)
        }
        else
        {
            if(viewModel.enemyUsesProtect)
            {
                viewModel.enemyUsesProtect = false
            }
        }

        viewModel.enemyWeather.value?.let { weather ->
            val weatherImagePath = "Field/$weather.png"
            val weatherEffectDescriptionName = "{WEATHER} $weather"
            val weatherIv = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(80, 80).apply { bottomMargin = 8 }
                try { setImageBitmap(BitmapFactory.decodeStream(assets.open(weatherImagePath))) } catch (e: Exception) {}
                setOnClickListener { showDetailPopup(weatherEffectDescriptionName, this, weatherImagePath) }
            }
            enemyStatusContainer.addView(weatherIv, 0)
        }

        // Status Condition
        enemy?.statusCondition?.let { status ->
            if (status.isNotEmpty()) {
                val statusPath = "status_icons/$status.png"
                val statusIv = ImageView(this).apply {
                    try {
                        setImageBitmap(BitmapFactory.decodeStream(assets.open(statusPath)))
                    } catch (e: Exception) {
                    }
                    layoutParams = LinearLayout.LayoutParams(80, 80)
                    setOnClickListener { showDetailPopup(status, this, statusPath) }
                }
                enemyStatusContainer.addView(statusIv)
            }
        }

        uiMapper.updateEnemyTypeIcons(viewModel.enemyPokemon.value, enemyTypesContainer)


        lifecycleScope.launch {
            val bitmap = getPokemonBitmap(spriteUrl) ?: withContext(Dispatchers.IO) {
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
                    var nextIndex:Int? = null
                    if(info.id == viewModel.lastPokemonId)
                    {
                        nextIndex = viewModel.lastSelectedIndex
                    }
                    if(nextIndex == null) {
                        viewModel.setOwnPokemon(info, nextIndex)
                    }
                    else{
                        val team = viewModel.teamPokemon.value
                        viewModel.setOwnPokemon(team[nextIndex], nextIndex)
                    }
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
            val artBitmap = getPokemonBitmap(artUrl) ?: withContext(Dispatchers.IO) {
                try {
                    val b = BitmapFactory.decodeStream(URL(artUrl).openStream())
                    if (b != null) saveBitmapToCache(artUrl, b)
                    b
                } catch (e: Exception) { null }
            }
            artBitmap?.let { imageView.setImageBitmap(it) }

            val spriteBitmap = getPokemonBitmap(spriteUrl) ?: withContext(Dispatchers.IO) {
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
                    viewModel.setUpdateUINoSync()
                }
            }
        }
    }

    private fun updateEvolutionViews() {
        val own = viewModel.ownPokemon.value
        if(own == null || own.isTrainerPokemon)
        {
        evolutionsContainer.removeAllViews()
        preEvolutionsContainer.removeAllViews()
        return
        }
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
            val bitmap = getPokemonBitmap(spriteUrl) ?: withContext(Dispatchers.IO) {
                try {
                    val b = BitmapFactory.decodeStream(URL(spriteUrl).openStream())
                    if (b != null) saveBitmapToCache(spriteUrl, b)
                    b
                } catch (e: Exception) { null }
            }
            bitmap?.let {
                iv.setImageBitmap(it)
                iv.setOnClickListener {
                    get_pokedex(number, spriteUrl, "https://www.serebii.net/pokemon/art/$number.png")
                }
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

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val lang = getSharedPreferences("settings", Context.MODE_PRIVATE).getString("language", "en") ?: "en"
            tts?.setLanguage(if (lang == "de") Locale.GERMAN else Locale.ENGLISH)
            isTtsReady = true
            pendingTTS?.let { speakOut(it); pendingTTS = null }
        }
    }

    private fun showDetailPopup(key: String, anchorView: View, imagePath: String? = null) {
        var description = detailsMap[key.lowercase()]
        if (description == null)
        {
            if(key.startsWith("B ") || key.startsWith("W ")) {
                val splits = key.split(" ")
                val n_key = splits[0]+" "+splits[1]
                description = detailsMap[n_key.lowercase()]
            }
        }

        if (description==null)
        {
            description =arrayOf(key,"unknown data")
        }

        // 1. Create the layout programmatically
        val popupView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(android.R.drawable.toast_frame) // Built-in dark background
            background.setTint(Color.parseColor("#EE333333")) // Semi-transparent dark gray
            setPadding(32, 24, 32, 24)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val headLineView = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        val headlineText = description[0]
        val titleTv = TextView(this).apply {
            text = headlineText
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(16, 0, 0, 8)
        }



        if(imagePath != null) {
            val iv = ImageView(this)
            val size = 60
            iv.layoutParams = LinearLayout.LayoutParams(size*2, size).apply {
                bottomMargin = 4
            }
            try {
                val inputStream = this.assets.open(imagePath)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                iv.setImageBitmap(bitmap)
            } catch (e: Exception) {
            }
            headLineView.addView(iv)
        }

        headLineView.addView(titleTv)

        val descTv = TextView(this).apply {
            text = description[1]
            textSize = 16f
            setTextColor(Color.LTGRAY)
            maxWidth = 800 // Prevent the popup from being too wide
            setPadding(0,0,0,0)
        }

        popupView.addView(headLineView)
        popupView.addView(descTv)

        // 2. Initialize the PopupWindow
        val popupWindow = PopupWindow(
            popupView,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true // Focusable (allows tapping outside to dismiss)
        ).apply {
            elevation = 10f
            animationStyle = android.R.style.Animation_Dialog
        }

        // 3. Show the popup anchored to the clicked view
        popupWindow.showAsDropDown(anchorView, 0, 10)
    }

    private fun loadCachedBitmap(url: String): Bitmap? {
        val file = getCacheFile(url)
        return if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    private fun getPokemonBitmap(url: String): Bitmap? {
        val pokemonId = url.substringAfterLast("/").replace(".png","")
        val isSprite = url.contains("icon")
        val fileName = "$pokemonId.png"
        val folders = if (isSprite) {
            listOf("sprites") // Check sprites first if requested
        } else {
            listOf("art") // Check art first (high res) then sprites
        }

        for (folder in folders) {
            try {
                assets.open("$folder/$fileName").use { inputStream ->
                    return BitmapFactory.decodeStream(inputStream)
                }
            } catch (e: Exception) {
                // File not found in this folder, continue to next
            }
        }

        // Fallback: If not in assets, use cached files
        return loadCachedBitmap(url)
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
