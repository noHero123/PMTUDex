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
    var nextPokedexIndex: Int = 0,
    var isTrainerPokemon: Boolean = false,
    // values not based on pokemon id
    var additionalLevel: Int = 0,
    var move3: String? = null,
    var teraType: String? = null,
    var isTeraActivated: Boolean = false,
    var typeEnhancerType: String? = null,
    var baseItem: String? = null,
    var isBaseItemActivated: Boolean = false,
    var statusCondition: String? = null,
    var isDynaAvailable: Boolean = false,
    var isDynaActivated: Boolean = false,
    var isGigaDynaActivated: Boolean = false
):Parcelable {
    @Transient
    @IgnoredOnParcel
    var spriteBitmap: Bitmap? = null

    fun copyStateFrom(other: PokemonInfo) {
        this.additionalLevel = other.additionalLevel
        this.move3= other.move3
        this.teraType= other.teraType
        this.isTeraActivated= other.isTeraActivated
        this.typeEnhancerType= other.typeEnhancerType
        this.baseItem= other.baseItem
        this.isBaseItemActivated= other.isBaseItemActivated
        this.statusCondition= other.statusCondition
        this.isDynaAvailable= other.isDynaAvailable
        this.isDynaActivated= other.isDynaActivated
        this.isGigaDynaActivated= other.isGigaDynaActivated
    }

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

    fun resetPokedex(pokedexRepository: PokedexRepository) {
        nextPokedexIndex = 0
        pokedexEntries = pokedexRepository.getGermanText(this.id)
        name = pokedexRepository.getGermanName(this.id)
    }

}
