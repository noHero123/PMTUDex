package com.example.pmtu

import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class TeamViewManager(
    private val activity: ResultActivity,
    private val viewModel: ResultViewModel,
    private val moveRepository: MoveRepository,
    private val imageManager: ImageManager
) {
    var isSelectingSlot = false

    fun populateTeam(
        container: ViewGroup,
        team: List<PokemonInfo?>,
        currentIndex: Int?,
        isSelecting: Boolean
    ) {
        this.isSelectingSlot = isSelecting
        container.removeAllViews()
        val enemy = viewModel.enemyPokemon.value

        for (i in 0 until 6) {
            val slotContainer = FrameLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(120, 120).apply {
                    setMargins(16, 0, 16, 0)
                }
                setBackgroundColor(if (currentIndex == i) Color.BLUE else Color.TRANSPARENT)
            }
            val slotIv = ImageView(activity).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                ).apply {
                    if (currentIndex == i) setMargins(8, 8, 8, 8)
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            slotContainer.addView(slotIv)

            val pokemon = team.getOrNull(i)
            if (isSelecting) {
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
                    val ownEff = moveRepository.getPokemonEffectiveness(pokemon, enemy)
                    if (ownEff == 1) addArrow(slotContainer, "arrow_green.png", Gravity.BOTTOM or Gravity.START)
                    if (ownEff == -1) addArrow(slotContainer, "arrow_red.png", Gravity.BOTTOM or Gravity.START)
                    
                    val enemyEff = moveRepository.getPokemonEffectiveness(enemy, pokemon)
                    if (enemyEff == 1) addArrow(slotContainer, "arrow_red.png", Gravity.BOTTOM or Gravity.END)
                    if (enemyEff == -1) addArrow(slotContainer, "arrow_green.png", Gravity.BOTTOM or Gravity.END)
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
            container.addView(slotContainer)
        }
    }

    private fun addArrow(container: FrameLayout, assetName: String, gravity: Int) {
        val arrow = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(40, 40).apply {
                this.gravity = gravity
            }
            setAssetImage(assetName)
        }
        container.addView(arrow)
    }

    private fun loadTeamSprite(pokemon: PokemonInfo, index: Int, imageView: ImageView) {
        val url = if (pokemon.spriteUrl.isNotEmpty()) pokemon.spriteUrl else "https://www.serebii.net/pokedex-sv/icon/${pokemon.id}.png"
        val existing = imageManager.getPokemonBitmap(url)
        if (existing != null) {
            pokemon.spriteBitmap = existing
            imageView.setBackgroundColor(Color.WHITE)
            imageView.setImageBitmap(existing)
            imageView.setOnClickListener {
                viewModel.setOwnPokemon(pokemon, index)
                viewModel.setUpdateUI()
            }
        } else {
            activity.lifecycleScope.launch {
                imageManager.downloadImage(url)?.let {
                    pokemon.spriteBitmap = it
                    imageView.setBackgroundColor(Color.WHITE)
                    imageView.setImageBitmap(it)
                    imageView.setOnClickListener {
                        viewModel.setOwnPokemon(pokemon, index)
                        viewModel.setUpdateUI()
                    }
                }
            }
        }
    }
}
