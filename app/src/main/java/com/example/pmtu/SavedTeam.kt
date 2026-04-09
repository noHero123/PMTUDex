package com.example.pmtu
import kotlinx.parcelize.Parcelize
import android.os.Parcelable

@Parcelize
data class SavedTeam(
    //val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val pokemon: Array<PokemonInfo?>
): Parcelable
