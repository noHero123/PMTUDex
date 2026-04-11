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
    private val type1: String,
    private val type2: String,
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
    var isTrainerPokemon: Boolean = false,
    var statusCondition: String? = null
):Parcelable {
    @Transient
    @IgnoredOnParcel
    var spriteBitmap: Bitmap? = null

    fun hasTypelessMove(): Boolean {
        return statusCondition.equals("Froz", ignoreCase = true) ||
                statusCondition.equals("Para", ignoreCase = true) ||
                statusCondition.equals("Slee", ignoreCase = true)
    }

    fun getType1(): String{
        if(isTeraActivated)
        {
            return teraType!!
        }
        return type1
    }

    fun getType2(): String{
        if(isTeraActivated)
        {
            return "None"
        }
        return type2
    }

}
