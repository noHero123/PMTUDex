package com.example.pmtu

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class PokemonViewManager(
    private val activity: ResultActivity,
    private val viewModel: ResultViewModel,
    private val pokedexRepository: PokedexRepository,
    private val moveRepository: MoveRepository,
    private val scanHandler: ScanHandler,
    private val uiMapper: PokemonUiMapper,
    private val detailsRepository: DetailsRepository,
    private val syncManager: SyncManager,
    private val onNewScanRequested: () -> Unit,
    private val onSettingsRequested: () -> Unit
) {
    lateinit var imageView: ImageView
    lateinit var textView: TextView
    private lateinit var diceContainer: LinearLayout
    private lateinit var teamContainer: LinearLayout
    lateinit var enemySpriteView: ImageView
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
    lateinit var syncInfoRow: LinearLayout
    lateinit var connectionCountTv: TextView
    private lateinit var fightButton: ImageView

    var isSelectingSlot = false

    fun setupUI(): View {
        val rootLayout = FrameLayout(activity)
        rootLayout.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val mainContainer = LinearLayout(activity)
        mainContainer.orientation = LinearLayout.VERTICAL
        mainContainer.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(16, 16, 64, 16)
        }
        settingsButton = ImageView(activity).apply {
            setImageResource(android.R.drawable.ic_menu_preferences)
            layoutParams = LinearLayout.LayoutParams(80, 80)
            setOnClickListener { onSettingsRequested() }
        }
        topBar.addView(settingsButton)
        mainContainer.addView(topBar)

        syncInfoRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(64, 0, 0, 0)
            visibility = if (P2PSyncService.isServerEnabledByUser) View.VISIBLE else View.GONE
        }

        fightButton = ImageView(activity).apply {
            setPadding(0, 0, 16, 0)
            setOnClickListener { if (syncManager.isFightOngoing) syncManager.endFight() else syncManager.requestFight() }
        }
        val dp150 = (50 * activity.resources.displayMetrics.density).toInt()
        val dp100 = (35 * activity.resources.displayMetrics.density).toInt()
        fightButton.layoutParams = LinearLayout.LayoutParams(dp150, dp100)
        syncInfoRow.addView(fightButton)

        connectionCountTv = TextView(activity).apply {
            textSize = 14f
            setTextColor(Color.CYAN)
            text = "Connections: ${P2PSyncService.activeConnections}"
        }
        syncInfoRow.addView(connectionCountTv)
        mainContainer.addView(syncInfoRow)

        teamContainer = LinearLayout(activity).apply {
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

        addRemoveButton = Button(activity)
        mainContainer.addView(LinearLayout(activity).apply { gravity = Gravity.CENTER_HORIZONTAL; addView(addRemoveButton) })
        mainContainer.addView(View(activity).apply { layoutParams = LinearLayout.LayoutParams(1, 64) })

        val centerContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)
            setPadding(32, 0, 32, 32)
        }
        diceContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16 }
        }
        mainContainer.addView(diceContainer)

        val imageEvoLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 600)
        }
        imageView = ImageView(activity).apply {
            setImageResource(android.R.drawable.ic_menu_camera)
            layoutParams = LinearLayout.LayoutParams(600, 600)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        imageEvoLayout.addView(imageView)
        mainContainer.addView(imageEvoLayout)

        preEvolutionsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                marginStart = 50
            }
        }
        evolutionsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                marginEnd = 50
            }
        }
        rootLayout.addView(preEvolutionsContainer)
        rootLayout.addView(evolutionsContainer)

        val pokedexRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 32
                bottomMargin = 32
            }
        }
        statusFieldContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        pokedexRow.addView(statusFieldContainer)

        pokedexButton = Button(activity).apply {
            text = "Pokédex"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                leftMargin = 16
                rightMargin = 16
            }
            setOnClickListener {
                viewModel.ownPokemon.value?.let {
                    if (it.pokedexEntries.isNotEmpty()) {
                        val entry = it.pokedexEntries[it.nextPokedexIndex]
                        activity.speakOut("${it.name}. $entry")
                        it.nextPokedexIndex = (it.nextPokedexIndex + 1) % it.pokedexEntries.size
                        updatePokedexButtonText()
                    }
                }
            }
        }
        pokedexRow.addView(pokedexButton)

        fieldEffectsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        pokedexRow.addView(fieldEffectsContainer)
        mainContainer.addView(pokedexRow)

        movesLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        mainContainer.addView(movesLayout)

        textView = TextView(activity).apply {
            textSize = 20f
            gravity = Gravity.CENTER
        }
        movesLayout.addView(textView)
        mainContainer.addView(centerContainer)

        val buttonContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(64, 0, 64, 128) }
        }
        val buttonLayoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)

        val newScanButton = Button(activity).apply {
            text = "New Scan"
            layoutParams = buttonLayoutParams
            setOnClickListener { onNewScanRequested() }
        }
        buttonContainer.addView(newScanButton)
        buttonContainer.addView(View(activity).apply { layoutParams = LinearLayout.LayoutParams(32, 1) })

        val enemyLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = buttonLayoutParams
        }
        val enemyInfoContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        enemyStatusContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = 8 }
        }
        enemyInfoContainer.addView(enemyStatusContainer)

        enemyTypesContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = 8 }
        }
        enemyInfoContainer.addView(enemyTypesContainer)

        enemySpriteView = ImageView(activity).apply { layoutParams = LinearLayout.LayoutParams(120, 120) }
        enemyInfoContainer.addView(enemySpriteView)

        clearEnemyButton = ImageView(activity).apply {
            try { setImageBitmap(BitmapFactory.decodeStream(activity.assets.open("trash.png"))) } catch (e: Exception) {}
            layoutParams = LinearLayout.LayoutParams(100, 100).apply { leftMargin = 8 }
            visibility = View.GONE
            setOnClickListener { viewModel.clearEnemy(); viewModel.setUpdateUI() }
        }
        enemyInfoContainer.addView(clearEnemyButton)

        val switchToEnemyButton = Button(activity).apply {
            text = "Switch to Enemy"
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { viewModel.switchWithEnemy(); viewModel.setUpdateUI() }
        }
        enemyLayout.addView(enemyInfoContainer)
        enemyLayout.addView(switchToEnemyButton)
        buttonContainer.addView(enemyLayout)

        mainContainer.addView(buttonContainer)
        rootLayout.addView(mainContainer)
        return rootLayout
    }

    fun refreshUI() {
        showDice(false)
        refreshMoves()
        updatePokedexButtonText()
        updateAddRemoveButton()
        updateEvolutionViews()
        updateTeamView()
        updateStatusFieldIcons()
        updateFieldIcons()
    }

    fun updateFightButton() {
        if (!::fightButton.isInitialized) return
        if (P2PSyncService.activeConnections > 0) {
            fightButton.visibility = View.VISIBLE
            val assetName = if (syncManager.isFightOngoing) "run.png" else "fight.png"
            try {
                activity.assets.open(assetName).use { inputStream ->
                    fightButton.setImageBitmap(BitmapFactory.decodeStream(inputStream))
                }
            } catch (e: Exception) {
                fightButton.setImageResource(if (syncManager.isFightOngoing) android.R.drawable.ic_menu_close_clear_cancel else android.R.drawable.ic_menu_add)
            }
        } else {
            fightButton.visibility = View.GONE
        }
    }

    fun updateAddRemoveButton() {
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
                Toast.makeText(activity, "Select a slot to save ${current.name}", Toast.LENGTH_SHORT).show()
                updateTeamView()
            }
        }
    }

    fun updateTeamView() {
        teamContainer.removeAllViews()
        val team = viewModel.teamPokemon.value
        val currentIndex = viewModel.currentTeamIndex.value
        val enemy = viewModel.enemyPokemon.value
        for (i in 0 until 6) {
            val slotContainer = FrameLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(120, 120).apply { setMargins(8, 0, 8, 0) }
                setBackgroundColor(if (currentIndex == i) Color.BLUE else Color.TRANSPARENT)
            }
            val slotIv = ImageView(activity).apply {
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
        val arrow = ImageView(activity)
        try { activity.assets.open(assetName).use { inputStream -> arrow.setImageBitmap(BitmapFactory.decodeStream(inputStream)) } } catch (e: Exception) {}
        arrow.layoutParams = FrameLayout.LayoutParams(40, 40).apply { this.gravity = gravity }
        container.addView(arrow)
    }

    private fun loadTeamSprite(pokemon: PokemonInfo, index: Int, imageView: ImageView) {
        activity.lifecycleScope.launch {
            val url = if (pokemon.spriteUrl.isNotEmpty()) pokemon.spriteUrl else "https://www.serebii.net/pokedex-sv/icon/${pokemon.id}.png"
            val bitmap = activity.getPokemonBitmap(url) ?: withContext(Dispatchers.IO) {
                try {
                    val b = BitmapFactory.decodeStream(URL(url).openStream())
                    if (b != null) activity.saveBitmapToCache(url, b)
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

    fun refreshMoves() {
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
        val prefs = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val row = LinearLayout(activity).apply {
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
            val speakerIv = ImageView(activity).apply {
                try { activity.assets.open("speaker.png").use { inputStream -> setImageBitmap(BitmapFactory.decodeStream(inputStream)) } } catch (e: Exception) {}
                layoutParams = LinearLayout.LayoutParams(100, 100).apply { rightMargin = 16 }
                setPadding(8, 8, 8, 8)
                setOnClickListener {
                    val lang = prefs.getString("language", "en") ?: "en"
                    activity.speakOut(if (lang == "en") result.moveData.englishName ?: "Unknown" else result.moveData.germanName ?: "Unbekannt")
                }
            }
            row.addView(speakerIv)
        }
        result.moveData.wurfel?.let { w ->
            if (w.contains("d4}") || w.contains("d8}")) {
                val dieIv = ImageView(activity).apply {
                    val dieType = if (w.contains("d4}")) "d4" else "d8"
                    try { activity.assets.open("move_symbols/$dieType.png").use { inputStream -> setImageBitmap(BitmapFactory.decodeStream(inputStream)) } } catch (e: Exception) {}
                    layoutParams = LinearLayout.LayoutParams(60, 60).apply { rightMargin = 16 }
                }
                row.addView(dieIv)
            }
        }
        val moveTextView = TextView(activity).apply {
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
            val arrowIv = ImageView(activity).apply {
                try { activity.assets.open(if (result.effectiveness > 0) "arrow_green.png" else "arrow_red.png").use { inputStream -> setImageBitmap(BitmapFactory.decodeStream(inputStream)) } } catch (e: Exception) {}
                layoutParams = LinearLayout.LayoutParams(40, 40).apply { leftMargin = 16 }
            }
            row.addView(arrowIv)
        }
        if (isTM && viewModel.ownPokemon.value?.isTrainerPokemon != true) {
            val deleteIv = ImageView(activity).apply {
                try { activity.assets.open("trash.png").use { inputStream -> setImageBitmap(BitmapFactory.decodeStream(inputStream)) } } catch (e: Exception) {}
                layoutParams = LinearLayout.LayoutParams(80, 80).apply { leftMargin = 16 }
                setOnClickListener { viewModel.ownPokemon.value?.move3 = null; refreshMoves(); viewModel.saveTeamData() }
            }
            row.addView(deleteIv)
        }
        movesLayout.addView(row)
    }

    private fun addTeraRow(pokemon: PokemonInfo) {
        val row = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 16, 0, 16) }
        }
        val teraIv = ImageView(activity).apply {
            try { activity.assets.open("tera/Tera Type - ${pokemon.teraType}.png").use { inputStream -> setImageBitmap(BitmapFactory.decodeStream(inputStream)) } } catch (e: Exception) {}
            layoutParams = LinearLayout.LayoutParams(150, 150)
            colorFilter = if (!pokemon.isTeraActivated) ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) else null
            setOnClickListener { pokemon.isTeraActivated = !pokemon.isTeraActivated; viewModel.setUpdateUI(); viewModel.saveTeamData() }
        }
        row.addView(teraIv)
        addDeleteButton(row) { pokemon.teraType = null; pokemon.isTeraActivated = false; refreshMoves(); viewModel.saveTeamData() }
        movesLayout.addView(row)
    }

    private fun addTypeEnhancerRow(pokemon: PokemonInfo) {
        val row = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 16, 0, 16) }
        }
        val iv = ImageView(activity).apply {
            try { activity.assets.open("type_enhancer/TypeEnhancer${pokemon.typeEnhancerType}.png").use { inputStream -> setImageBitmap(BitmapFactory.decodeStream(inputStream)) } } catch (e: Exception) {}
            layoutParams = LinearLayout.LayoutParams(150, 150)
        }
        row.addView(iv)
        addDeleteButton(row) { pokemon.typeEnhancerType = null; refreshMoves(); viewModel.saveTeamData() }
        movesLayout.addView(row)
    }

    private fun addBaseItemRow(pokemon: PokemonInfo) {
        val row = LinearLayout(activity).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { setMargins(0, 16, 0, 16) }
        }
        val iv = ImageView(activity).apply {
            try { activity.assets.open("base_items/${pokemon.baseItem}.png").use { inputStream -> setImageBitmap(BitmapFactory.decodeStream(inputStream)) } } catch (e: Exception) {}
            layoutParams = LinearLayout.LayoutParams(150, 150)
            colorFilter = if (!pokemon.isBaseItemActivated) ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) else null
            setOnClickListener {
                if (pokemon.baseItem in scanHandler.getToggleAbleItems()) pokemon.isBaseItemActivated = !pokemon.isBaseItemActivated
                if (pokemon.baseItem == "Mega") {
                    val mega = pokedexRepository.hasMegaEvolution(pokemon.id)
                    if (pokemon.isBaseItemActivated) {
                        pokemon.isBaseItemActivated = false
                        if (mega != null && !pokemon.isDynaActivated && !pokemon.isGigaDynaActivated) activity.evolvePokemon(mega, 0, "mega")
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
        val deleteIv = ImageView(activity).apply {
            try { activity.assets.open("trash.png").use { inputStream -> setImageBitmap(BitmapFactory.decodeStream(inputStream)) } } catch (e: Exception) {}
            layoutParams = LinearLayout.LayoutParams(80, 80).apply { leftMargin = 32 }
            setOnClickListener { onClick(); syncManager.syncViaP2P() }
        }
        row.addView(deleteIv)
    }

    fun showDice(all: Boolean) {
        diceContainer.removeAllViews()
        val own = viewModel.ownPokemon.value ?: return
        if (all) {
            for (i in 0..6) {
                val diceIv = ImageView(activity).apply {
                    try { activity.assets.open("blued6_$i.png").use { inputStream -> setImageBitmap(BitmapFactory.decodeStream(inputStream)) } } catch (e: Exception) {}
                    layoutParams = LinearLayout.LayoutParams(100, 100).apply { setMargins(8, 0, 8, 0) }
                    setOnClickListener { own.additionalLevel = i; showDice(false); refreshMoves(); viewModel.saveTeamData(); syncManager.syncViaP2P() }
                }
                diceContainer.addView(diceIv)
            }
        } else {
            val wrapper = FrameLayout(activity).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
            val diceIv = ImageView(activity).apply {
                try { activity.assets.open("blued6_${own.additionalLevel}.png").use { inputStream -> setImageBitmap(BitmapFactory.decodeStream(inputStream)) } } catch (e: Exception) {}
                layoutParams = FrameLayout.LayoutParams(150, 150).apply { gravity = Gravity.CENTER }
                setOnClickListener { showDice(true) }
            }
            wrapper.addView(diceIv)
            if (own.isDynaAvailable && !own.isGigaDynaActivated && !pokedexRepository.isMega(own.id)) {
                val dynaIv = ImageView(activity).apply {
                    try { activity.assets.open("G-Max Ball.png").use { inputStream -> setImageBitmap(BitmapFactory.decodeStream(inputStream)) } } catch (e: Exception) {}
                    layoutParams = FrameLayout.LayoutParams(120, 120).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; rightMargin = 64 }
                    if (!own.isDynaActivated) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                    setOnClickListener { own.isDynaActivated = !own.isDynaActivated; viewModel.saveTeamData(); viewModel.setUpdateUI() }
                }
                wrapper.addView(dynaIv)
            }
            if (own.isDynaAvailable && !own.isDynaActivated) {
                val gigaDyna = pokedexRepository.hasGMaxEvolution(own.id)
                if (gigaDyna != null || own.isGigaDynaActivated) {
                    val dynaIv = ImageView(activity).apply {
                        try { activity.assets.open("G-Max Symbol.png").use { inputStream -> setImageBitmap(BitmapFactory.decodeStream(inputStream)) } } catch (e: Exception) {}
                        layoutParams = FrameLayout.LayoutParams(120, 120).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; rightMargin = 64 + 120 }
                        if (!own.isGigaDynaActivated) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                        setOnClickListener {
                            if (gigaDyna != null) activity.evolvePokemon(gigaDyna, 0, "gmax")
                            else viewModel.lastSelectedIndex?.let { idx -> viewModel.setOwnPokemon(viewModel.teamPokemon.value[idx], idx); viewModel.setUpdateUI() }
                        }
                    }
                    wrapper.addView(dynaIv)
                }
            }
            diceContainer.addView(wrapper)
        }
    }

    fun updateStatusFieldIcons() {
        statusFieldContainer.removeAllViews()
        val own = viewModel.ownPokemon.value ?: return
        own.statusCondition?.let { status ->
            if (status.isNotEmpty()) {
                val path = "status_icons/$status.png"
                val statusIv = ImageView(activity).apply {
                    try { activity.assets.open(path).use { inputStream -> setImageBitmap(BitmapFactory.decodeStream(inputStream)) } } catch (e: Exception) {}
                    layoutParams = LinearLayout.LayoutParams(100, 100)
                    setOnClickListener { showDetailPopup(status, this, path) }
                }
                statusFieldContainer.addView(statusIv)
                val trashIv = ImageView(activity).apply {
                    try { activity.assets.open("trash.png").use { inputStream -> setImageBitmap(BitmapFactory.decodeStream(inputStream)) } } catch (e: Exception) {}
                    layoutParams = LinearLayout.LayoutParams(60, 60).apply { leftMargin = 4; rightMargin = 16 }
                    setOnClickListener { own.statusCondition = null; viewModel.saveTeamData(); viewModel.setUpdateUI() }
                }
                statusFieldContainer.addView(trashIv)
            }
        }
    }

    fun updateFieldIcons() {
        fieldEffectsContainer.removeAllViews()
        viewModel.ownWeather.value?.let { weather ->
            val path = "Field/$weather.png"
            val effectName = "{WEATHER} $weather"
            val weatherIv = ImageView(activity).apply {
                try { activity.assets.open(path).use { inputStream -> setImageBitmap(BitmapFactory.decodeStream(inputStream)) } } catch (e: Exception) {}
                layoutParams = LinearLayout.LayoutParams(100, 100)
                setOnClickListener { showDetailPopup(effectName, this, path) }
            }
            fieldEffectsContainer.addView(weatherIv)
            val trashIv = ImageView(activity).apply {
                try { activity.assets.open("trash.png").use { inputStream -> setImageBitmap(BitmapFactory.decodeStream(inputStream)) } } catch (e: Exception) {}
                layoutParams = LinearLayout.LayoutParams(60, 60).apply { leftMargin = 4; rightMargin = 16 }
                setOnClickListener { viewModel.setOwnWeather(null); viewModel.setUpdateUI() }
            }
            fieldEffectsContainer.addView(trashIv)
        }
    }

    fun updateEnemySprite(spriteUrl: String) {
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
            val protIv = ImageView(activity).apply {
                try { activity.assets.open("move_symbols/Black/Protection 1.png").use { inputStream -> setImageBitmap(BitmapFactory.decodeStream(inputStream)) } } catch (e: Exception) {}
                layoutParams = LinearLayout.LayoutParams(120, 120).apply { bottomMargin = 8 }
                alpha = if (viewModel.enemyUsesProtect) 1.0f else 0.3f
                setOnClickListener { viewModel.enemyUsesProtect = !viewModel.enemyUsesProtect; viewModel.setUpdateUI() }
            }
            enemyStatusContainer.addView(protIv)
        } else { viewModel.enemyUsesProtect = false }

        viewModel.enemyWeather.value?.let { weather ->
            val path = "Field/$weather.png"
            val weatherIv = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(80, 80).apply { bottomMargin = 8 }
                try { activity.assets.open(path).use { inputStream -> setImageBitmap(BitmapFactory.decodeStream(inputStream)) } } catch (e: Exception) {}
                setOnClickListener { showDetailPopup("{WEATHER} $weather", this, path) }
            }
            enemyStatusContainer.addView(weatherIv, 0)
        }
        enemy?.statusCondition?.let { status ->
            if (status.isNotEmpty()) {
                val path = "status_icons/$status.png"
                val statusIv = ImageView(activity).apply {
                    try { activity.assets.open(path).use { inputStream -> setImageBitmap(BitmapFactory.decodeStream(inputStream)) } } catch (e: Exception) {}
                    layoutParams = LinearLayout.LayoutParams(80, 80)
                    setOnClickListener { showDetailPopup(status, this, path) }
                }
                enemyStatusContainer.addView(statusIv)
            }
        }
        uiMapper.updateEnemyTypeIcons(enemy, enemyTypesContainer)
        activity.lifecycleScope.launch {
            (activity.getPokemonBitmap(spriteUrl) ?: withContext(Dispatchers.IO) {
                try {
                    val b = BitmapFactory.decodeStream(URL(spriteUrl).openStream())
                    if (b != null) activity.saveBitmapToCache(spriteUrl, b)
                    b
                } catch (e: Exception) { null }
            })?.let { enemySpriteView.setImageBitmap(it); clearEnemyButton.visibility = View.VISIBLE }
        }
    }

    fun updatePokedexButtonText() {
        viewModel.ownPokemon.value?.let {
            pokedexButton.text = if (it.pokedexEntries.isNotEmpty()) "Pokédex (${if (it.nextPokedexIndex == 0) it.pokedexEntries.size else it.nextPokedexIndex}/${it.pokedexEntries.size})" else "Pokédex"
        } ?: run { pokedexButton.text = "Pokédex" }
    }

    fun updateEvolutionViews() {
        val own = viewModel.ownPokemon.value
        if (own == null || own.isTrainerPokemon) { evolutionsContainer.removeAllViews(); preEvolutionsContainer.removeAllViews(); return }
        activity.lifecycleScope.launch(Dispatchers.IO) {
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
        val iv = ImageView(activity).apply { layoutParams = LinearLayout.LayoutParams(120, 120); setPadding(8, 8, 8, 8) }
        container.addView(iv)
        activity.lifecycleScope.launch {
            (activity.getPokemonBitmap(spriteUrl) ?: withContext(Dispatchers.IO) {
                try {
                    val b = BitmapFactory.decodeStream(URL(spriteUrl).openStream())
                    if (b != null) activity.saveBitmapToCache(spriteUrl, b)
                    b
                } catch (e: Exception) { null }
            })?.let { iv.setImageBitmap(it); iv.setOnClickListener { activity.get_pokedex(number, spriteUrl, "https://www.serebii.net/pokemon/art/$number.png") } }
        }
    }

    private fun showDetailPopup(key: String, anchorView: View, imagePath: String? = null) {
        val description = detailsRepository.getDetails(key) ?: arrayOf(key, "unknown data")
        val popupView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(android.R.drawable.toast_frame)
            background.setTint(Color.parseColor("#EE333333"))
            setPadding(32, 24, 32, 24)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val headLineView = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        val titleTv = TextView(activity).apply { text = description[0]; textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD); setPadding(16, 0, 0, 8) }
        if (imagePath != null) {
            val iv = ImageView(activity).apply { layoutParams = LinearLayout.LayoutParams(120, 60).apply { bottomMargin = 4 } }
            try { activity.assets.open(imagePath).use { inputStream -> iv.setImageBitmap(BitmapFactory.decodeStream(inputStream)) } } catch (e: Exception) {}
            headLineView.addView(iv)
        }
        headLineView.addView(titleTv)
        popupView.addView(headLineView)
        popupView.addView(TextView(activity).apply { text = description[1]; textSize = 16f; setTextColor(Color.LTGRAY); maxWidth = 800; setPadding(0, 0, 0, 0) })
        PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply { elevation = 10f; animationStyle = android.R.style.Animation_Dialog }.showAsDropDown(anchorView, 0, 10)
    }
}
