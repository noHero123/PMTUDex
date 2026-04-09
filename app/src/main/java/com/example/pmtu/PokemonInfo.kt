package com.example.pmtu
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.IgnoredOnParcel
import android.os.Parcelable
import android.graphics.Bitmap
@Parcelize
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
    @IgnoredOnParcel
    var spriteBase64: String? = null,
    var additionalLevel: Int = 0,
    var nextPokedexIndex: Int = 0,
    var move3: String? = null,
    var teraType: String? = null,
    var isTeraActivated: Boolean = false,
    var typeEnhancerType: String? = null,
    var baseItem: String? = null,
    var isBaseItemActivated: Boolean = false,
    var isTrainerPokemon: Boolean = false
):Parcelable {
    @Transient
    @IgnoredOnParcel
    var spriteBitmap: Bitmap? = null
}
