package com.example.pmtu

import android.graphics.Bitmap

data class PokemonInfo(
    val id: String,
    var name: String,
    val base_level: Int,
    val type1: String,
    val type2: String,
    var pokedexEntries: List<String>,
    val move1: String,
    val move2: String,
    val spriteUrl: String,
    val artUrl: String,
    var spriteBase64: String? = null,
    var additionalLevel: Int = 0,
    var nextPokedexIndex: Int = 0,
    var move3: String? = null,
    var teraType: String? = null,
    var isTeraActivated: Boolean = false,
    var typeEnhancerType: String? = null
) {
    @Transient
    var spriteBitmap: Bitmap? = null
}
