package com.example.pmtu

import android.content.Context
import android.content.res.AssetManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.IOException
import java.io.InputStream


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

        addTypeImage(pokemon.getType1(), container)
        if (pokemon.getType2() != "None" && pokemon.getType2().isNotBlank()) {
            addTypeImage(pokemon.getType2(), container)
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
        ownPokemon: PokemonInfo?,
        enemyPokemon: PokemonInfo?,
        ownWeather: String?,
        enemyWeather: String?,
        pokedexRepository: PokedexRepository,
        moveRepo: MoveRepository,
        onEffectClicked: (String, View, String?) -> Unit
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
        var finalMoveName = if (lang == "de") moveData.germanName else moveData.englishName
        val isSpecialMove = moveData.powerStr!!.contains("*")
        if(isSpecialMove)
        {
            finalMoveName+="*"
        }
        var detailName = moveData.englishName!!
        if(moveData.wurfel != null )
        {
            if(moveData.wurfel != null && moveData.wurfel.contains("G-Max"))
            {
                detailName = "{G-MAX} " + detailName
            }
            else
            {
                if(moveData.wurfel != null && moveData.wurfel.contains("Max"))
                {
                    detailName = "{MAX} " + detailName
                }
            }
        }

        val startText = builder.length
        builder.append(finalMoveName ?: "").append(" ")//.append(cleanWurfel)
        val endText = builder.length
        if (onEffectClicked != null && isSpecialMove) {
            val clickableSpan = object : android.text.style.ClickableSpan() {
                override fun onClick(widget: View) {
                    onEffectClicked(detailName, widget, null)
                }
                override fun updateDrawState(ds: android.text.TextPaint) {
                    ds.isUnderlineText = false // Remove the default link underline
                }
            }
            builder.setSpan(clickableSpan, startText, endText, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }


        val start = builder.length
        builder.append(powerval.toString())
        val end = builder.length
        builder.append(" ")

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

        val allEffects = moveRepo.getAllEffects(result, ownPokemon, enemyPokemon, ownWeather, enemyWeather, pokedexRepository)
        for(effect in allEffects)
        {
            addEffectIcon(builder, effect.first, textView, effect.second, onEffectClicked)
        }

        return builder
    }



    private fun isAssetExists(pathInAssetsDir: String): Boolean {

        return try {
             context.assets.open(pathInAssetsDir).use{true}
        } catch (e: IOException) {
            false
        }
    }

    fun get_add_effect_icon_path(effect: String?):String?
    {
        if (effect.isNullOrBlank()) return null

        val cleanEffect = effect.replace("{", "").replace("}", "").trim()
        if (cleanEffect.isEmpty()) return null

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

        mappedName = mappedName.replace("Switch", "Switch")
        mappedName = mappedName.replace("KO", "KO")
        mappedName = mappedName.replace("Status", "StatusHeal")
        mappedName = mappedName.replace("Life", "Life Drain")
        mappedName = mappedName.replace("You", "You Icon")
        mappedName = mappedName.replace("Opp", "Opponent Icon")
        mappedName = mappedName.replace("Block", "Block Item")
        mappedName = mappedName.replace("Ignore", "Ignore Type")
        mappedName = mappedName.replace("Prot", "Protection")
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

        mappedName = mappedName.replace("Clear", "Field Clear")

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
        pathsToTry.add("Field/$cleanEffect.png")
        pathsToTry.add("Field/$mappedName.png")
        val path = context.filesDir.path

        for (path in pathsToTry) {
            if (isAssetExists(path)) {
                return path
            }
        }
        return null
    }
    private fun addEffectIcon(builder: SpannableStringBuilder, effect: String?, textView: TextView, altEffectName:String = "", onEffectClicked: ((String, View, String?) -> Unit)? = null) {
        if (effect.isNullOrBlank()) return
        val cleanEffect = effect.replace("{", "").replace("}", "").trim()
        if (cleanEffect.isEmpty()) return

        val image_path = get_add_effect_icon_path(effect)
        if (image_path == null) {
            builder.append(" ").append(effect)
            return
        }
        try {
            val inputStream = context.assets.open(image_path)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            val drawable: Drawable = BitmapDrawable(context.resources, bitmap)
            val size = (textView.textSize * 1.5).toInt()
            drawable.setBounds(0, 0, (size * bitmap.width / bitmap.height), size)
            val start = builder.length
            builder.append("  ")
            val end = builder.length - 1
            builder.setSpan(
                ImageSpan(drawable, ImageSpan.ALIGN_BOTTOM),
                start,
                end,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            // 2. The ClickableSpan (The logic)
            if (onEffectClicked != null) {
                val clickableSpan = object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) {
                        var effectName = cleanEffect
                        if(cleanEffect.contains(" Extra "))
                        {
                            effectName = altEffectName
                        }
                        onEffectClicked(effectName, widget, image_path)
                    }
                    override fun updateDrawState(ds: android.text.TextPaint) {
                        ds.isUnderlineText = false // Remove the default link underline
                    }
                }
                builder.setSpan(clickableSpan, start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } catch (e: Exception) {
        }
    }

}
