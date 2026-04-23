package com.example.pmtu

import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.setPadding
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PokemonViewManager(
    private val activity: ResultActivity,
    private val viewModel: ResultViewModel,
    private val pokedexRepository: PokedexRepository,
    private val moveRepository: MoveRepository,
    private val scanHandler: ScanHandler,
    private val uiMapper: PokemonUiMapper,
    private val detailsRepository: DetailsRepository,
    private val syncManager: SyncManager,
    private val evolutionHandler: EvolutionHandler,
    private val imageManager: ImageManager,
    private val onNewScanRequested: () -> Unit,
    private val onSettingsRequested: () -> Unit
) {
    lateinit var imageView: ImageView
    lateinit var textView: TextView
    lateinit var pokedexButton: Button
    lateinit var addRemoveButton: Button
    lateinit var fightButton: ImageView
    lateinit var syncInfoRow: LinearLayout
    lateinit var connectionCountTv: TextView

    lateinit var enemyProtectView: ImageView
    lateinit var enemySpriteView: ImageView
    lateinit var clearEnemyButton: ImageView
    lateinit var enemyTypesContainer: LinearLayout
    lateinit var enemyStatusContainer: LinearLayout
    lateinit var movesLayout: LinearLayout
    lateinit var diceContainer: LinearLayout
    lateinit var evolutionsContainer: LinearLayout
    lateinit var preEvolutionsContainer: LinearLayout
    lateinit var teamContainer: LinearLayout
    lateinit var statusFieldContainer: LinearLayout
    lateinit var fieldEffectsContainer: LinearLayout

    private var evolutionJob: Job? = null
    private var enemySpriteJob: Job? = null

    private val moveViewFactory = MoveViewFactory(
        activity, viewModel, moveRepository, pokedexRepository, uiMapper, 
        evolutionHandler, syncManager, 
        { key, anchor, path -> showDetailPopup(key, anchor, path) },
        { text -> activity.speakOut(text) }
    )

    private val teamViewManager = TeamViewManager(activity, viewModel, moveRepository, imageManager)

    fun setupUI(): View {
        val rootLayout = FrameLayout(activity)
        rootLayout.setSize(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)

        val mainContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setSize(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Top bar
        val topBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setSize(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setPadding(16, 16, 64, 16)
        }
        val settingsButton = ImageView(activity).apply {
            setSize(80, 80)
            setImageResource(android.R.drawable.ic_menu_preferences)
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
        fightButton.setSize(dp150, dp100)
        syncInfoRow.addView(fightButton)

        connectionCountTv = TextView(activity).apply {
            textSize = 14f
            setTextColor(Color.CYAN)
            text = "Connections: ${P2PSyncService.activeConnections}"
        }
        syncInfoRow.addView(connectionCountTv)
        mainContainer.addView(syncInfoRow)

        // Team
        teamContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setSize(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        ViewCompat.setOnApplyWindowInsetsListener(teamContainer) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updateLayoutParams<LinearLayout.LayoutParams> { topMargin = (insets.top + 32).coerceAtLeast(0) }
            windowInsets
        }
        mainContainer.addView(teamContainer)

        addRemoveButton = Button(activity)
        mainContainer.addView(LinearLayout(activity).apply { gravity = Gravity.CENTER_HORIZONTAL; addView(addRemoveButton) })
        mainContainer.addView(View(activity).apply { setSize(1, 64) })

        val centerContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1.0f)
            setPadding(32, 0, 32, 32)
        }
        diceContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setSize(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setMargins(bottom = 16)
        }
        mainContainer.addView(diceContainer)

        val imageEvoLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setSize(ViewGroup.LayoutParams.MATCH_PARENT, 600)
        }
        imageView = ImageView(activity).apply {
            setImageBitmap(imageManager.getDefaultImage())
            setSize(600, 600)
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
            setSize(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setMargins(top = 32, bottom = 32)
        }
        statusFieldContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        }
        pokedexRow.addView(statusFieldContainer)

        pokedexButton = Button(activity).apply {
            text = "Pokédex"
            setSize(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setMargins(left = 16, right = 16)
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
            setSize(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        mainContainer.addView(movesLayout)

        textView = TextView(activity).apply {
            textSize = 20f
            gravity = Gravity.CENTER
        }
        movesLayout.addView(textView)
        mainContainer.addView(centerContainer)

        //enemy info section
        val enemySectionRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            // Set margins to match your other rows
            setMargins(64, 0, 64, 16)
            setPadding(0, 0, 64, 0)
            setSize(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val enemyLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            // Use WRAP_CONTENT instead of the shared weight-based buttonLayoutParams
            setSize(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        //###################
        val enemyInfoContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setSize(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        enemyProtectView = ImageView(activity).apply {
            setSize(120, 120)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        enemyInfoContainer.addView(enemyProtectView)

        enemyStatusContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setSize(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setMargins(right = 8)
        }
        enemyInfoContainer.addView(enemyStatusContainer)

        enemyTypesContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setSize(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setMargins(right = 8)
        }
        enemyInfoContainer.addView(enemyTypesContainer)


        enemySpriteView = ImageView(activity).apply { setSize(120, 120) }
        enemyInfoContainer.addView(enemySpriteView)

        clearEnemyButton = ImageView(activity).apply {
            setAssetImage("trash.png")
            setSize(100, 100)
            setMargins(left = 8)
            visibility = View.GONE
            setOnClickListener { viewModel.clearEnemy(); viewModel.setUpdateUI() }
        }
        enemyInfoContainer.addView(clearEnemyButton)
        enemyLayout.addView(enemyInfoContainer)
        enemySectionRow.addView(enemyLayout)
        mainContainer.addView(enemySectionRow)

        //buttons on bottom
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
        val switchToEnemyButton = Button(activity).apply {
            text = "Switch to Enemy"
            layoutParams = buttonLayoutParams
            setOnClickListener { viewModel.switchWithEnemy(); viewModel.setUpdateUI() }
        }

        buttonContainer.addView(newScanButton)
        buttonContainer.addView(View(activity).apply { setSize(32, 1) })
        buttonContainer.addView(switchToEnemyButton)

        mainContainer.addView(buttonContainer)
        rootLayout.addView(mainContainer)
        return rootLayout
    }

    fun refreshUI() {
        showDice(false)
        movesLayout.removeAllViews()
        moveViewFactory.createMoveViews().forEach { movesLayout.addView(it) }
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
            fightButton.setAssetImage(assetName)
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
                teamViewManager.isSelectingSlot = true
                Toast.makeText(activity, "Select a slot to save ${current.name}", Toast.LENGTH_SHORT).show()
                updateTeamView()
            }
        }
    }

    fun updateTeamView() {
        teamViewManager.populateTeam(teamContainer, viewModel.teamPokemon.value.toList(), viewModel.currentTeamIndex.value, teamViewManager.isSelectingSlot)
    }

    fun showDice(all: Boolean) {
        diceContainer.removeAllViews()
        val own = viewModel.ownPokemon.value ?: return
        if (all) {
            for (i in 0..6) {
                val diceIv = ImageView(activity).apply {
                    setAssetImage("blued6_$i.png")
                    setSize(100, 100)
                    setMargins(left = 8, right = 8)
                    setOnClickListener { own.additionalLevel = i; showDice(false); refreshUI(); viewModel.saveTeamData(); syncManager.syncViaP2P() }
                }
                diceContainer.addView(diceIv)
            }
        } else {
            val wrapper = FrameLayout(activity).apply { setSize(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT) }
            val diceIv = ImageView(activity).apply {
                setAssetImage("blued6_${own.additionalLevel}.png")
                layoutParams = FrameLayout.LayoutParams(150, 150).apply { gravity = Gravity.CENTER }
                setOnClickListener { showDice(true) }
            }
            wrapper.addView(diceIv)
            if (own.isDynaAvailable && !own.isGigaDynaActivated && !pokedexRepository.isMega(own.id)) {
                val dynaIv = ImageView(activity).apply {
                    setAssetImage("G-Max Ball.png")
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
                        setAssetImage("G-Max Symbol.png")
                        layoutParams = FrameLayout.LayoutParams(120, 120).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.END; rightMargin = 64 + 120 }
                        if (!own.isGigaDynaActivated) colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
                        setOnClickListener {
                            if (gigaDyna != null) evolutionHandler.evolvePokemon(gigaDyna, 0, "gmax")
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
                    setAssetImage(path)
                    setSize(100, 100)
                    setOnClickListener { showDetailPopup(status, this, path) }
                }
                statusFieldContainer.addView(statusIv)
                val trashIv = ImageView(activity).apply {
                    setAssetImage("trash.png")
                    setSize(60, 60)
                    setMargins(left = 4, right = 16)
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
                setAssetImage(path)
                setSize(100, 100)
                setOnClickListener { showDetailPopup(effectName, this, path) }
            }
            fieldEffectsContainer.addView(weatherIv)
            val trashIv = ImageView(activity).apply {
                setAssetImage("trash.png")
                setSize(60, 60)
                setMargins(left = 4, right = 16)
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
            enemyProtectView.setImageDrawable(null)
            return
        }
        enemyStatusContainer.removeAllViews()
        val enemy = viewModel.enemyPokemon.value
        val own = viewModel.ownPokemon.value
        if (enemy != null && own != null && moveRepository.hasProtection(enemy, own, viewModel.enemyWeather.value, viewModel.ownWeather.value, pokedexRepository)) {
            /*val protIv = ImageView(activity).apply {
                setAssetImage("move_symbols/Black/Protection 1.png")
                setSize(120, 120)
                setMargins(bottom = 8)
                alpha = if (viewModel.enemyUsesProtect) 1.0f else 0.3f
                setOnClickListener { viewModel.enemyUsesProtect = !viewModel.enemyUsesProtect; viewModel.setUpdateUI() }
            }
            enemyStatusContainer.addView(protIv)*/
            enemyProtectView.visibility= View.VISIBLE
            enemyProtectView.setAssetImage("move_symbols/Black/Protection 1.png")
            //enemyProtectView.setMargins(bottom = 8)
            enemyProtectView.setSize(120, 120)
            enemyProtectView.alpha = if (viewModel.enemyUsesProtect) 1.0f else 0.3f
            enemyProtectView.setOnClickListener { viewModel.enemyUsesProtect = !viewModel.enemyUsesProtect; viewModel.setUpdateUI() }

        } else {
            enemyProtectView.visibility= View.GONE
            viewModel.enemyUsesProtect = false }

        enemyStatusContainer.setMargins(right = 8)
        viewModel.enemyWeather.value?.let { weather ->
            val path = "Field/$weather.png"
            val weatherIv = ImageView(activity).apply {
                setSize(80, 80)
                setMargins(bottom = 8)
                setAssetImage(path)
                setOnClickListener { showDetailPopup("{WEATHER} $weather", this, path) }
            }
            enemyStatusContainer.addView(weatherIv, 0)
        }
        enemy?.statusCondition?.let { status ->
            if (status.isNotEmpty()) {
                val path = "status_icons/$status.png"
                val statusIv = ImageView(activity).apply {
                    setAssetImage(path)
                    setSize(80, 80)
                    setMargins(bottom = 8)
                    setOnClickListener { showDetailPopup(status, this, path) }
                }
                enemyStatusContainer.addView(statusIv)
            }
        }
        uiMapper.updateEnemyTypeIcons(enemy, enemyTypesContainer)
        
        val existing = activity.imageManager.getPokemonBitmap(spriteUrl)
        if (existing != null) {
            enemySpriteView.setImageBitmap(existing)
            clearEnemyButton.visibility = View.VISIBLE
            enemySpriteJob?.cancel()
        } else {
            enemySpriteJob?.cancel()
            enemySpriteJob = activity.lifecycleScope.launch {
                activity.imageManager.downloadImage(spriteUrl)?.let { 
                    enemySpriteView.setImageBitmap(it)
                    clearEnemyButton.visibility = View.VISIBLE 
                }
            }
        }
    }

    fun updatePokedexButtonText() {
        viewModel.ownPokemon.value?.let {
            pokedexButton.visibility = View.VISIBLE
            pokedexButton.text = if (it.pokedexEntries.isNotEmpty()) "Pokédex (${if (it.nextPokedexIndex == 0) it.pokedexEntries.size else it.nextPokedexIndex}/${it.pokedexEntries.size})" else "Pokédex"
        } ?: run {
            pokedexButton.visibility = View.GONE
            pokedexButton.text = "Pokédex" }
    }

    fun updateEvolutionViews() {
        val own = viewModel.ownPokemon.value
        if (own == null || own.isTrainerPokemon) { evolutionsContainer.removeAllViews(); preEvolutionsContainer.removeAllViews(); return }
        
        evolutionJob?.cancel()
        evolutionJob = activity.lifecycleScope.launch(Dispatchers.IO) {
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
        val iv = ImageView(activity).apply { setSize(120, 120); setPadding(8, 8, 8, 8) }
        container.addView(iv)
        
        val existing = activity.imageManager.getPokemonBitmap(spriteUrl)
        if (existing != null) {
            iv.setImageBitmap(existing)
            iv.setOnClickListener { activity.get_pokedex(number, spriteUrl, "https://www.serebii.net/pokemon/art/$number.png") }
        } else {
            activity.lifecycleScope.launch {
                activity.imageManager.downloadImage(spriteUrl)?.let {
                    iv.setImageBitmap(it)
                    iv.setOnClickListener { activity.get_pokedex(number, spriteUrl, "https://www.serebii.net/pokemon/art/$number.png") } 
                }
            }
        }
    }

    private fun showDetailPopup(key: String, anchorView: View, imagePath: String? = null) {
        val description = detailsRepository.getDetails(key) ?: arrayOf(key, "unknown data")
        val popupView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(android.R.drawable.toast_frame)
            background.setTint(Color.parseColor("#EE333333"))
            setPadding(32, 24, 32, 24)
            setSize(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val headLineView = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setSize(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT) }
        val titleTv = TextView(activity).apply { text = description[0]; textSize = 18f; setTextColor(Color.WHITE); setTypeface(null, android.graphics.Typeface.BOLD); setPadding(16, 0, 0, 8) }
        if (imagePath != null) {
            val iv = ImageView(activity).apply { setSize(120, 60); setMargins(bottom = 4) }
            iv.setAssetImage(imagePath)
            headLineView.addView(iv)
        }
        headLineView.addView(titleTv)
        popupView.addView(headLineView)
        popupView.addView(TextView(activity).apply { text = description[1]; textSize = 16f; setTextColor(Color.LTGRAY); maxWidth = 800; setPadding(0, 0, 0, 0) })
        PopupWindow(popupView, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply { elevation = 10f; animationStyle = android.R.style.Animation_Dialog }.showAsDropDown(anchorView, 0, 10)
    }
}
