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
                pokedexRepository, moveRepository
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
                val toggleableItems = arrayOf("Dyna", "Left", "Quic", "Wide", "Mega", "Evio")
                if (pokemon.baseItem in toggleableItems) pokemon.isBaseItemActivated = !pokemon.isBaseItemActivated
                if (pokemon.baseItem == "Mega") {
                    val mega = pokedexRepository.hasMegaEvolution(pokemon.id)
                    if (pokemon.isBaseItemActivated) {
                        pokemon.isBaseItemActivated = false
                        if (mega != null && !pokemon.isDynaActivated && !pokemon.isGigaDynaActivated) evolutionHandler.evolvePokemon(mega, 0, "mega")
                    } else if (pokedexRepository.isMega(pokemon.id)) {
                        viewModel.lastSelectedIndex?.let { idx -> viewModel.setOwnPokemon(viewModel.teamPokemon.value[idx], idx) }
                        viewModel.setUpdateUI()
                    }
                } else {
                    viewModel.setUpdateUI()
                    viewModel.saveTeamData()
                    syncManager.syncViaP2P()
                }
            }
        }
        row.addView(iv)
        addDeleteButton(row) {
            pokemon.baseItem = null
            pokemon.isBaseItemActivated = false
            viewModel.setUpdateUI()
            viewModel.saveTeamData()
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
}
