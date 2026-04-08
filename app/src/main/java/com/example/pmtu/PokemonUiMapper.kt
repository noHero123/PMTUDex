package com.example.pmtu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

class PokemonUiMapper(private val context: Context) {

    fun updatePokemonImage(pokemon: PokemonInfo?, imageView: ImageView, defaultResId: Int) {
        if (pokemon?.spriteBitmap != null) {
            imageView.setImageBitmap(pokemon.spriteBitmap)
        } else {
            imageView.setImageResource(defaultResId)
        }
    }

    fun updateEnemyTypeIcons(pokemon: PokemonInfo?, container: LinearLayout) {
        container.removeAllViews()
        if (pokemon == null) return

        addTypeImage(pokemon.type1, container)
        if (pokemon.type2 != "None" && pokemon.type2.isNotBlank()) {
            addTypeImage(pokemon.type2, container)
        }
    }

    fun addTypeImage(type: String, container: LinearLayout) {
        val cleanType = type.replace("{", "").replace("}", "").trim()
        val iv = ImageView(context)
        val size = 60
        iv.layoutParams = LinearLayout.LayoutParams(size, size).apply {
            bottomMargin = 4
        }
        try {
            val inputStream = context.assets.open("$cleanType.png")
            val bitmap = BitmapFactory.decodeStream(inputStream)
            iv.setImageBitmap(bitmap)
        } catch (e: Exception) {
            val tv = TextView(context)
            tv.text = cleanType
            tv.textSize = 8f
            container.addView(tv)
            return
        }
        container.addView(iv)
    }

    fun formatMoveText(
        result: MoveRepository.PowerResult,
        textView: TextView,
        lang: String,
        enemyPokemon: PokemonInfo?,
        ownWeather: String?,
        enemyWeather: String?
    ): CharSequence {
        val moveData = result.moveData
        val powerval = result.power
        val effectiveness = result.effectiveness
        val cleanType = result.cleanType

        val builder = SpannableStringBuilder()
        val typeImagePath = "$cleanType.png"
        try {
            val inputStream = context.assets.open(typeImagePath)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            val drawable: Drawable = BitmapDrawable(context.resources, bitmap)
            val size = (textView.textSize * 1.5).toInt()
            drawable.setBounds(0, 0, (size * bitmap.width / bitmap.height), size)
            builder.append("  ")
            builder.setSpan(ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM), 0, 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.append(" ")
        } catch (e: Exception) {
            builder.append(moveData.type ?: "").append(" ")
        }

        val finalMoveName = if (lang == "de") moveData.germanName else moveData.englishName
        //val wurfel = moveData.wurfel ?: ""
        //val cleanWurfel = wurfel.replace(Regex("\\{.*?d[48]\\}"), "").trim()
        builder.append(" ").append(finalMoveName ?: "").append(" ")//.append(cleanWurfel)

        val start = builder.length
        builder.append(powerval.toString())
        val end = builder.length

        if (enemyPokemon != null) {
            if (effectiveness < -4) {
                 // Immunity or extremely ineffective
                 builder.setSpan(ForegroundColorSpan(Color.RED), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (effectiveness < 0) {
                builder.setSpan(ForegroundColorSpan(Color.RED), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            } else if (effectiveness > 0) {
                builder.setSpan(ForegroundColorSpan(Color.GREEN), start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        // Add effects
        if(enemyWeather == "Mist" && !moveData.effect1!!.contains("Dis"))
        {
            addEffectIcon(builder, moveData.effect1, textView)
        }
        if(enemyWeather == "Mist" && !moveData.effect2!!.contains("Dis"))
        {
            addEffectIcon(builder, moveData.effect2, textView)
        }


        if(ownWeather == "Renewal")
        {
            addEffectIcon(builder, "W Life", textView)
        }

        return builder
    }

    private fun addEffectIcon(builder: SpannableStringBuilder, effect: String?, textView: TextView) {
        if (effect.isNullOrBlank()) return
        
        val cleanEffect = effect.replace("{", "").replace("}", "").trim()
        if (cleanEffect.isEmpty()) return

        var folder: String? = null
        var imageName: String = cleanEffect

        if (cleanEffect.startsWith("B ")) {
            folder = "Black"
            imageName = cleanEffect.substring(2).trim()
        } else if (cleanEffect.startsWith("W ")) {
            folder = "White"
            imageName = cleanEffect.substring(2).trim()
        } else if (cleanEffect.startsWith("B")) {
            folder = "Black"
            imageName = cleanEffect.substring(1).trim()
        } else if (cleanEffect.startsWith("W")) {
            folder = "White"
            imageName = cleanEffect.substring(1).trim()
        }

        // Common mappings for abbreviations in CSV to actual filenames
        var mappedName = imageName
        mappedName = mappedName.replace("DoubAdv", "3Dice")
        mappedName = mappedName.replace("DoubDis", "3Dice")
        mappedName = mappedName.replace("Adv", "2Dice")
        mappedName = mappedName.replace("Dis", "2Dice")
        mappedName = mappedName.replace("Add", "AddDice")
        mappedName = mappedName.replace("Extra", "Additional")


        mappedName = mappedName.replace("Status", "StatusHeal")
        mappedName = mappedName.replace("Life", "Life Drain")
        mappedName = mappedName.replace("You", "You Icon")
        mappedName = mappedName.replace("Opp", "Opponent Icon")
        mappedName = mappedName.replace("Block", "Block Item")
        mappedName = mappedName.replace("Ignore", "Ignore Type")
        mappedName = mappedName.replace("Prot 1", "Protection")
        mappedName = mappedName.replace("Burn", "Burned")
        mappedName = mappedName.replace("Conf", "Confused")
        mappedName = mappedName.replace("Pois", "Poison")
        mappedName = mappedName.replace("Para", "Paralyse")
        mappedName = mappedName.replace("Freeze", "Frozen")
        mappedName = mappedName.replace("Lock", "Esc Prevention")
        mappedName = mappedName.replace("StatDown", "StatDown")
        mappedName = mappedName.replace("Rage", "Rage")
        mappedName = mappedName.replace("Priority", "Priority")
        mappedName = mappedName.replace("Sleep", "Sleep")
        mappedName = mappedName.replace("Boost", "Condition Boost")




        // Renewal
        // Rainy
        //Priority - Priority
        //StatDown- StatDown





        if (mappedName.contains( "AdvDis"))
        {
            mappedName = "AdvDis 6"
        }

        val pathsToTry = mutableListOf<String>()
        if (folder != null) {
            pathsToTry.add("move_symbols/$folder/$mappedName.png")
            // Try with space mapping too if it looks like "Word Number"
            if (mappedName.contains(" ")) {
                 // already handled
            } else if (imageName != mappedName) {
                pathsToTry.add("move_symbols/$folder/$imageName.png")
            }
        } else {
            pathsToTry.add("move_symbols/$cleanEffect.png")
        }

        var loaded = false
        for (path in pathsToTry) {
            try {
                val inputStream = context.assets.open(path)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                val drawable: Drawable = BitmapDrawable(context.resources, bitmap)
                val size = (textView.textSize * 1.5).toInt()
                drawable.setBounds(0, 0, (size * bitmap.width / bitmap.height), size)
                
                builder.append("  ")
                builder.setSpan(
                    ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM),
                    builder.length - 2,
                    builder.length - 1,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                loaded = true
                break
            } catch (e: Exception) {
                // Continue
            }
        }

        if (!loaded) {
            builder.append(" ").append(effect)
        }
    }
}
