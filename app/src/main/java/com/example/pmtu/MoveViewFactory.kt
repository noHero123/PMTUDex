package com.example.pmtu

import android.content.Context
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class MoveViewFactory(
    private val context: Context,
    private val viewModel: ResultViewModel,
    private val moveRepository: MoveRepository,
    private val pokedexRepository: PokedexRepository,
    private val uiMapper: PokemonUiMapper,
    private val evolutionHandler: EvolutionHandler,
    private val syncManager: SyncManager,
    private val onDetailPopupRequested: (String, View, String?) -> Unit,
    private val speakOut: (String) -> Unit
) {

    fun createMoveViews(): List<View> {
        val views = mutableListOf<View>()
        val own = viewModel.ownPokemon.value ?: return views
        
        if (own.hasTypelessMove()) {
            views.add(createMoveRow("Typeless"))
        } else {
            views.add(createMoveRow(own.move1))
            views.add(createMoveRow(own.move2))
            own.move3?.let { views.add(createMoveRow(it, true)) }
        }
        
        own.teraType?.let { views.add(createTeraRow(own)) }
        own.typeEnhancerType?.let { views.add(createTypeEnhancerRow(own)) }
        own.baseItem?.let { views.add(createBaseItemRow(own)) }
        
        return views
    }

    private fun createMoveRow(moveName: String, isTM: Boolean = false): View {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setSize(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setMargins(top = 8, bottom = 8)
        }

        val result = moveRepository.calculateMovePower(
            moveName,
            viewModel.ownPokemon.value!!,
            viewModel.enemyPokemon.value,
            viewModel.ownWeather.value,
            viewModel.enemyWeather.value, viewModel.enemyUsesProtect
        ) ?: return row

        if (prefs.getBoolean("show_speakers", false)) {
            val speakerIv = ImageView(context).apply {
                setSize(100, 100)
                setMargins(right = 16)
                setPadding(8, 8, 8, 8)
                setAssetImage("speaker.png")
                setOnClickListener {
                    val lang = prefs.getString("language", "en") ?: "en"
                    speakOut(if (lang == "en") result.moveData.englishName ?: "Unknown" else result.moveData.germanName ?: "Unbekannt")
                }
            }
            row.addView(speakerIv)
        }

        result.moveData.wurfel?.let { w ->
            if (w.contains("d4}") || w.contains("d8}")) {
                val dieIv = ImageView(context).apply {
                    val dieType = if (w.contains("d4}")) "d4" else "d8"
                    setSize(60, 60)
                    setMargins(right = 16)
                    setAssetImage("move_symbols/$dieType.png")
                }
                row.addView(dieIv)
            }
        }

        val moveTextView = TextView(context).apply {
            movementMethod = android.text.method.LinkMovementMethod.getInstance()
            highlightColor = Color.TRANSPARENT
            textSize = 20f
            text = uiMapper.formatMoveText(
                result, this, prefs.getString("language", "en") ?: "en",
                viewModel.ownPokemon.value, viewModel.enemyPokemon.value,
                viewModel.ownWeather.value, viewModel.enemyWeather.value,
                pokedexRepository, moveRepository, viewModel.enemyUsesProtect
            ) { effectName, view, path -> onDetailPopupRequested(effectName, view, path) }
        }
        row.addView(moveTextView)

        if (viewModel.enemyPokemon.value != null && result.effectiveness != 0) {
            val arrowIv = ImageView(context).apply {
                setSize(40, 40)
                setMargins(left = 16)
                setAssetImage(if (result.effectiveness > 0) "arrow_green.png" else "arrow_red.png")
            }
            row.addView(arrowIv)
        }

        if (isTM && viewModel.ownPokemon.value?.isTrainerPokemon != true) {
            val deleteIv = ImageView(context).apply {
                setSize(80, 80)
                setMargins(left = 16)
                setAssetImage("trash.png")
                setOnClickListener {
                    viewModel.ownPokemon.value?.move3 = null
                    viewModel.setUpdateUI() // This will trigger refresh
                    viewModel.saveTeamData()
                }
            }
            row.addView(deleteIv)
        }
        return row
    }

    private fun createTeraRow(pokemon: PokemonInfo): View {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            setSize(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setMargins(top = 16, bottom = 16)
        }
        val teraIv = ImageView(context).apply {
            setSize(150, 150)
            setAssetImage("tera/Tera Type - ${pokemon.teraType}.png")
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
            viewModel.setUpdateUI()
            viewModel.saveTeamData()
        }
        return row
    }

    private fun createTypeEnhancerRow(pokemon: PokemonInfo): View {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            setSize(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setMargins(top = 16, bottom = 16)
        }
        val iv = ImageView(context).apply {
            setSize(150, 150)
            setAssetImage("type_enhancer/TypeEnhancer${pokemon.typeEnhancerType}.png")
        }
        row.addView(iv)
        addDeleteButton(row) {
            pokemon.typeEnhancerType = null
            viewModel.setUpdateUI()
            viewModel.saveTeamData()
        }
        return row
    }

    private fun createBaseItemRow(pokemon: PokemonInfo): View {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            setSize(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setMargins(top = 16, bottom = 16)
        }
        val iv = ImageView(context).apply {
            setSize(150, 150)
            setAssetImage("base_items/${pokemon.baseItem}.png")
            colorFilter = if (!pokemon.isBaseItemActivated) ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) else null
            setOnClickListener {
                if (pokemon.baseItem == "Mega") {
                    val isMegaCurrently = pokedexRepository.isMega(pokemon.id) || pokemon.isBaseItemActivated
                    if (isMegaCurrently) {
                        evolutionHandler.devolvePokemon("mega")
                        syncManager.syncViaP2P()
                    } else {
                        val megaList = pokedexRepository.getMegaEvolutions(pokemon.id)
                        if (megaList.isNotEmpty() && !pokemon.isDynaActivated && !pokemon.isGigaDynaActivated) {
                            if (megaList.size == 1) {
                                evolutionHandler.evolvePokemon(megaList[0], 0, "mega")
                                syncManager.syncViaP2P()
                            } else {
                                showMegaEvolutionSelectionDialog(megaList)
                            }
                        }
                    }
                } else {
                    val toggleableItems = arrayOf("Dyna", "Left", "Quic", "Wide", "Evio")
                    if (pokemon.baseItem in toggleableItems) pokemon.isBaseItemActivated = !pokemon.isBaseItemActivated
                    viewModel.setUpdateUI()
                    viewModel.saveTeamData()
                    syncManager.syncViaP2P()
                }
            }
        }
        row.addView(iv)
        addDeleteButton(row) {
            if (pokedexRepository.isMega(pokemon.id)) {
                evolutionHandler.devolvePokemon("mega")
            }
            val current = viewModel.ownPokemon.value ?: pokemon
            current.baseItem = null
            current.isBaseItemActivated = false
            val teamIdx = viewModel.currentTeamIndex.value
            if (teamIdx != null) {
                viewModel.teamPokemon.value[teamIdx] = current
                viewModel.saveTeamData()
            }
            viewModel.setUpdateUI()
        }
        return row
    }

    private fun addDeleteButton(row: LinearLayout, onClick: () -> Unit) {
        val deleteIv = ImageView(context).apply {
            setSize(80, 80)
            setMargins(left = 32)
            setAssetImage("trash.png")
            setOnClickListener {
                onClick()
                syncManager.syncViaP2P()
            }
        }
        row.addView(deleteIv)
    }

    private fun showMegaEvolutionSelectionDialog(megaIds: List<String>) {
        val scrollContainer = android.widget.HorizontalScrollView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val dialogView = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16, 24, 16, 24)
        }
        scrollContainer.addView(dialogView)

        val dialog = androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle("Choose Mega Evolution")
            .setView(scrollContainer)
            .setNegativeButton("Cancel", null)
            .create()

        megaIds.forEach { megaId ->
            val megaContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(16, 16, 16, 16)
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
                setOnClickListener {
                    dialog.dismiss()
                    evolutionHandler.evolvePokemon(megaId, 0, "mega")
                    syncManager.syncViaP2P()
                }
            }

            val spriteIv = ImageView(context).apply {
                setSize(150, 150)
                val spriteUrl = "https://www.serebii.net/pokedex-sv/icon/$megaId.png"
                val existing = (context as? ResultActivity)?.imageManager?.getPokemonBitmap(spriteUrl)
                if (existing != null) {
                    setImageBitmap(existing)
                } else {
                    setAssetImage("defaultpicture.png")
                    (context as? androidx.lifecycle.LifecycleOwner)?.lifecycleScope?.launch {
                        val downloaded = (context as? ResultActivity)?.imageManager?.downloadImage(spriteUrl)
                        if (downloaded != null) {
                            setImageBitmap(downloaded)
                        }
                    }
                }
            }
            megaContainer.addView(spriteIv)
            dialogView.addView(megaContainer)
        }

        dialog.show()
    }
}
